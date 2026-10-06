package com.github.catvod.spider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.w3c.dom.Node;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.HttpUrl;

/** Data-only XPath/API rule interpreter. No new host SDK dependency or JavaScript execution. */
final class KazumiRule {
    private static final Pattern VARIABLE = Pattern.compile("(?<![A-Za-z0-9_])@([A-Za-z_][A-Za-z0-9_]*)");
    final JSONObject json;
    final String name;
    final String base;
    final String userAgent;
    final String referer;

    KazumiRule(JSONObject json) throws Exception {
        this.json = json;
        name = json.getString("name");
        int api = Integer.parseInt(json.optString("api", "1"));
        if (api < 1 || api > 8) throw new IllegalArgumentException("Unsupported Kazumi API " + api);
        base = url(json.getString("baseURL"), "");
        userAgent = json.optString("userAgent").trim();
        referer = json.optString("referer").isEmpty() ? base : url(json.getString("referer"), base);
    }

    boolean api(boolean search) { return "api".equals(json.optString(search ? "searchMode" : "chapterMode", "xpath")); }

    RequestSpec request(boolean search, String value) throws Exception {
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put(search ? "keyword" : "source", value);
        if (api(search)) return apiRequest(json.getJSONObject(search ? "searchApiConfig" : "chapterApiConfig").getJSONObject("request"), vars);
        String target = search ? json.getString("searchURL").replace("@keyword", encode(value)) : url(value, base);
        HttpUrl parsed = HttpUrl.get(target);
        JSONObject query = new JSONObject();
        if (search && json.optBoolean("usePost")) {
            for (String key : parsed.queryParameterNames()) query.put(key, parsed.queryParameter(key));
            return new RequestSpec("POST", parsed.newBuilder().query(null).build().toString(), new JSONObject(), "form", query);
        }
        return new RequestSpec("GET", target, new JSONObject(), "none", null);
    }

    private RequestSpec apiRequest(JSONObject config, Map<String, Object> vars) throws Exception {
        String method = config.optString("method", "GET").toUpperCase(java.util.Locale.ROOT);
        if (!method.equals("GET") && !method.equals("POST")) throw new IllegalArgumentException("Kazumi only supports GET/POST metadata");
        HttpUrl.Builder url = HttpUrl.get(template(config.getString("url"), vars, true)).newBuilder();
        JSONObject query = (JSONObject) render(config.optJSONObject("query") == null ? new JSONObject() : config.getJSONObject("query"), vars);
        for (Iterator<String> keys = query.keys(); keys.hasNext();) {
            String key = keys.next();
            url.setQueryParameter(key, scalar(query.get(key)));
        }
        JSONObject headers = config.optJSONObject("headers");
        String type = config.optString("bodyType", "none");
        if (!List.of("none", "json", "form").contains(type)) throw new IllegalArgumentException("Unsupported Kazumi body type");
        return new RequestSpec(method, url.build().toString(), headers == null ? new JSONObject() : (JSONObject) render(headers, vars), type,
                method.equals("POST") && !type.equals("none") ? render(config.opt("body"), vars) : null);
    }

    List<Item> search(String raw) throws Exception {
        ArrayList<Item> result = new ArrayList<>();
        if (api(true)) {
            JSONObject config = json.getJSONObject("searchApiConfig");
            for (Object node : path(decode(raw), config.getString("listPath"))) {
                String name = scalar(first(node, config.getString("namePath")));
                String source = scalar(first(node, config.getString("sourcePath")));
                if (!name.isEmpty() && !source.isEmpty()) result.add(new Item(name, source));
            }
        } else {
            KazumiXPath html = new KazumiXPath(raw);
            rejectCaptcha(raw, html, true);
            for (Node node : html.nodes(html.root, json.getString("searchList"))) {
                String name = html.text(node, json.getString("searchName"));
                String source = html.href(node, json.getString("searchResult"));
                if (!name.isEmpty() && !source.isEmpty()) result.add(new Item(name, url(source, base)));
            }
        }
        if (result.isEmpty() && !api(true)) {
            String text = org.jsoup.Jsoup.parse(raw).text();
            if (!Pattern.compile("暂无|没有找到|未找到|没有搜索|无搜索|no (?:results|matches)|0 results|共\\s*0\\s*(?:个|条|部)", Pattern.CASE_INSENSITIVE).matcher(text).find())
                throw new IllegalStateException(name + " 搜索页面未匹配到结果，请检查规则或站点验证");
        }
        return result;
    }

    List<Road> chapters(String raw, String source) throws Exception {
        ArrayList<Road> roads = new ArrayList<>();
        if (!api(false)) {
            KazumiXPath html = new KazumiXPath(raw);
            rejectCaptcha(raw, html, false);
            for (Node road : html.nodes(html.root, json.getString("chapterRoads"))) {
                ArrayList<Item> episodes = new ArrayList<>();
                for (Node episode : html.nodes(road, json.getString("chapterResult"))) {
                    String link = KazumiXPath.attribute(episode, "href");
                    if (link.isEmpty()) continue;
                    String label = episode.getTextContent().replaceAll("\\s+", "");
                    episodes.add(new Item(label.isEmpty() ? "第" + (episodes.size() + 1) + "集" : label, url(link, base)));
                }
                if (!episodes.isEmpty()) roads.add(new Road("播放线路" + (roads.size() + 1), episodes));
            }
        } else {
            JSONObject config = json.getJSONObject("chapterApiConfig");
            Object document = decode(raw);
            Map<String, Object> variables = new LinkedHashMap<>();
            variables.put("source", source);
            JSONObject captures = config.optJSONObject("variables");
            if (captures != null) for (Iterator<String> keys = captures.keys(); keys.hasNext();) {
                String key = keys.next(); Object value = first(document, captures.getString(key));
                if (value == null || value == JSONObject.NULL) throw new IllegalArgumentException("Kazumi chapter variable missing: " + key);
                variables.put(key, value);
            }
            if ("delimited".equals(config.optString("format"))) parseDelimited(document, config, variables, roads);
            else {
                String path = config.optString("roadsPath", "$.data.roads[*]");
                List<Object> groups = path.isEmpty() ? List.of(document) : path(document, path);
                for (int r = 0; r < groups.size(); r++) {
                    Object group = groups.get(r);
                    String roadName = config.optString("roadNamePath", "$.name");
                    roadName = roadName.isEmpty() ? "" : scalar(first(group, roadName));
                    List<Object> nodes = path(group, config.optString("episodesPath", "$.episodes[*]"));
                    ArrayList<Item> episodes = new ArrayList<>();
                    for (int e = 0; e < nodes.size(); e++) {
                        String title = scalar(first(nodes.get(e), config.optString("episodeNamePath", "$.name")));
                        String valuePath = config.optString("episodeUrlPath", "$.url");
                        String value = valuePath.isEmpty() ? "" : scalar(first(nodes.get(e), valuePath));
                        String target = episode(config, variables, value, r, e);
                        if (!target.isEmpty()) episodes.add(new Item(title.isEmpty() ? "第" + (e + 1) + "集" : title, target));
                    }
                    if (!episodes.isEmpty()) roads.add(new Road(roadName.isEmpty() ? "播放线路" + (roads.size() + 1) : roadName, episodes));
                }
            }
        }
        if (roads.isEmpty()) throw new IllegalStateException(name + " 没有可播放章节（或章节规则已失效）");
        return roads;
    }

    private void parseDelimited(Object document, JSONObject config, Map<String, Object> vars, List<Road> roads) throws Exception {
        String separator = config.optString("roadSeparator", "$$$");
        String episodeSeparator = config.optString("episodeSeparator", "#");
        String fieldSeparator = config.optString("fieldSeparator", "$");
        if (separator.isEmpty() || episodeSeparator.isEmpty() || fieldSeparator.isEmpty()) throw new IllegalArgumentException("Kazumi separators must not be empty");
        String[] names = scalar(first(document, config.getString("roadNamesPath"))).split(Pattern.quote(separator), -1);
        String[] groups = scalar(first(document, config.getString("roadEpisodesPath"))).split(Pattern.quote(separator), -1);
        for (int r = 0; r < groups.length; r++) {
            ArrayList<Item> episodes = new ArrayList<>(); String[] entries = groups[r].split(Pattern.quote(episodeSeparator), -1);
            for (int e = 0; e < entries.length; e++) {
                int split = entries[e].indexOf(fieldSeparator); if (split < 0) continue;
                String name = entries[e].substring(0, split).trim();
                String target = episode(config, vars, entries[e].substring(split + fieldSeparator.length()).trim(), r, e);
                if (!target.isEmpty()) episodes.add(new Item(name.isEmpty() ? "第" + (e + 1) + "集" : name, target));
            }
            if (!episodes.isEmpty()) roads.add(new Road(r < names.length && !names[r].isEmpty() ? names[r] : "播放线路" + (r + 1), episodes));
        }
    }

    private String episode(JSONObject config, Map<String, Object> root, String value, int road, int episode) throws Exception {
        JSONObject page = config.optJSONObject("episodePage");
        if (page == null) return value.isEmpty() ? "" : url(value, base);
        Map<String, Object> variables = new LinkedHashMap<>(root);
        variables.put("episodeUrl", value); variables.put("roadIndex", road); variables.put("roadNumber", road + 1);
        variables.put("episodeIndex", episode); variables.put("episodeNumber", episode + 1);
        HttpUrl.Builder target = HttpUrl.get(url(template(page.getString("url"), variables, true), base)).newBuilder();
        JSONObject query = page.optJSONObject("query");
        if (query != null) for (Iterator<String> keys = query.keys(); keys.hasNext();) {
            String key = keys.next(); target.setQueryParameter(key, scalar(render(query.get(key), variables)));
        }
        return target.build().toString();
    }

    void rejectCaptcha(String raw, KazumiXPath html, boolean search) throws Exception {
        JSONObject config = json.optJSONObject("antiCrawlerConfig");
        if (search && config != null && config.optBoolean("enabled")) {
            String value = config.optString("captchaDetectValue"); int type = config.optInt("captchaDetectType", 1);
            boolean matched = false;
            if (!value.isEmpty()) matched = type == 2 ? raw.contains(value) : type == 3
                    ? Pattern.compile(value, Pattern.CASE_INSENSITIVE | Pattern.DOTALL).matcher(raw).find()
                    : !html.nodes(html.root, value).isEmpty();
            else for (String key : List.of("captchaImage", "captchaButton")) {
                String selector = config.optString(key); if (!selector.isEmpty() && !html.nodes(html.root, selector).isEmpty()) matched = true;
            }
            if (matched) throw new IllegalStateException(name + " 需要在原站完成验证码后重试");
        }
        // Observed generic challenge markers also cover stale antiCrawlerConfig selectors.
        if (!html.nodes(html.root, "//form[@id='challenge-form'] | //title[contains(.,'验证') or contains(.,'Just a moment') or contains(.,'Attention Required')]").isEmpty())
            throw new IllegalStateException(name + " 需要在原站完成验证后重试");
    }

    static Object decode(String raw) throws Exception {
        Object value = new JSONTokener(raw).nextValue();
        if (!(value instanceof JSONObject) && !(value instanceof JSONArray)) throw new IllegalArgumentException("Kazumi API response is not JSON");
        return value;
    }

    static String url(String value, String base) {
        HttpUrl parsed = base.isEmpty() ? HttpUrl.parse(value) : HttpUrl.get(base).resolve(value);
        if (parsed == null || !parsed.username().isEmpty() || !parsed.password().isEmpty()) throw new IllegalArgumentException("Kazumi URL must be HTTP(S) without credentials");
        if (!base.isEmpty()) {
            HttpUrl origin = HttpUrl.get(base);
            if (origin.isHttps() && !parsed.isHttps() && parsed.port() == 80 && parsed.host().equals(origin.host()))
                parsed = parsed.newBuilder().scheme("https").port(443).build();
        }
        return parsed.toString();
    }

    static String scalar(Object value) { return value == null || value == JSONObject.NULL ? "" : value.toString().trim(); }
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }

    static String template(String input, Map<String, Object> variables, boolean encode) {
        Matcher matcher = VARIABLE.matcher(input); StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1); if (!variables.containsKey(key)) throw new IllegalArgumentException("Unknown Kazumi variable: " + key);
            String value = scalar(variables.get(key)); matcher.appendReplacement(result, Matcher.quoteReplacement(encode ? encode(value) : value));
        }
        return matcher.appendTail(result).toString();
    }

    static Object render(Object value, Map<String, Object> vars) throws Exception {
        if (value instanceof String) {
            String string = (String) value; Matcher exact = Pattern.compile("^@([A-Za-z_][A-Za-z0-9_]*)$").matcher(string);
            if (exact.matches()) { if (!vars.containsKey(exact.group(1))) throw new IllegalArgumentException("Unknown Kazumi variable"); return vars.get(exact.group(1)); }
            return template(string, vars, false);
        }
        if (value instanceof JSONObject) {
            JSONObject result = new JSONObject(); JSONObject object = (JSONObject) value;
            for (Iterator<String> keys = object.keys(); keys.hasNext();) { String key = keys.next(); result.put(template(key, vars, false), render(object.get(key), vars)); }
            return result;
        }
        if (value instanceof JSONArray) { JSONArray result = new JSONArray(); for (int i = 0; i < ((JSONArray) value).length(); i++) result.put(render(((JSONArray) value).get(i), vars)); return result; }
        return value;
    }

    static Object first(Object value, String path) throws Exception { List<Object> values = path(value, path); return values.isEmpty() ? null : values.get(0); }

    /** Kazumi's restricted JSONPath deliberately excludes filters, recursive descent and expressions. */
    static List<Object> path(Object value, String expression) throws Exception {
        if (expression == null || !expression.startsWith("$")) throw new IllegalArgumentException("Kazumi JSONPath must start with $");
        List<Object> current = new ArrayList<>(); current.add(value); int index = 1;
        while (index < expression.length()) {
            String key; boolean wildcard = false; boolean arrayIndex = false;
            if (expression.charAt(index) == '.') {
                int start = ++index; while (index < expression.length() && Character.toString(expression.charAt(index)).matches("[A-Za-z0-9_$-]")) index++;
                if (start == index) throw new IllegalArgumentException("Unsupported Kazumi JSONPath"); key = expression.substring(start, index);
            } else if (expression.charAt(index) == '[') {
                int start = ++index; char quote = 0; boolean escaped = false;
                for (; index < expression.length(); index++) { char c = expression.charAt(index); if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (quote != 0) { if (c == quote) quote = 0; } else if (c == '\'' || c == '"') quote = c; else if (c == ']') break; }
                if (index == expression.length()) throw new IllegalArgumentException("Unclosed Kazumi JSONPath"); key = expression.substring(start, index++).trim();
                wildcard = key.equals("*"); arrayIndex = key.matches("[0-9]+");
                if (!wildcard && !arrayIndex) { if (key.length() < 2 || !(key.startsWith("\"") && key.endsWith("\"") || key.startsWith("'") && key.endsWith("'"))) throw new IllegalArgumentException("Unsupported Kazumi JSONPath"); key = scalar(new JSONTokener(key).nextValue()); }
            } else throw new IllegalArgumentException("Unsupported Kazumi JSONPath");
            List<Object> next = new ArrayList<>();
            for (Object node : current) {
                if (wildcard && node instanceof JSONArray) for (int i = 0; i < ((JSONArray) node).length(); i++) next.add(((JSONArray) node).get(i));
                else if (wildcard && node instanceof JSONObject) for (Iterator<String> keys = ((JSONObject) node).keys(); keys.hasNext();) next.add(((JSONObject) node).get(keys.next()));
                else if (arrayIndex && node instanceof JSONArray) { int n = Integer.parseInt(key); if (n < ((JSONArray) node).length()) next.add(((JSONArray) node).get(n)); }
                else if (!arrayIndex && node instanceof JSONObject && ((JSONObject) node).has(key)) next.add(((JSONObject) node).get(key));
            }
            current = next;
        }
        return current;
    }

    static final class RequestSpec {
        final String method, url, bodyType; final JSONObject headers; final Object body;
        RequestSpec(String method, String url, JSONObject headers, String bodyType, Object body) { this.method = method; this.url = url; this.headers = headers; this.bodyType = bodyType; this.body = body; }
    }
    static final class Item {
        final String name, value;
        Item(String name, String value) { this.name = name; this.value = value; }
    }
    static final class Road {
        final String name; final List<Item> episodes;
        Road(String name, List<Item> episodes) { this.name = name; this.episodes = episodes; }
    }
}
