package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.InterruptedIOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** girigiri 愛動漫：HTML 分類與播放、公開搜尋建議 API。 */
public class Girigiri extends Spider {

    private static final String DEFAULT_HOST = "https://gl.ciallo0d000721.cc.cd";
    private static final String UA = "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private static final int PAGE_SIZE = 48;
    private static final long CATALOG_TTL = 60_000_000_000L;
    private static final int CATALOG_LIMIT = 12;
    private static final Pattern DEFAULT_PAGE = Pattern.compile("^(/show/\\d+)--------1---/$");
    private static final Pattern DETAIL = Pattern.compile("^/GV(\\d+)/?$");
    private static final Pattern PLAY = Pattern.compile("^/playGV\\d+-\\d+-\\d+/?$");
    private static final Pattern CATEGORY = Pattern.compile("^/show/(\\d+)-");
    private static final Pattern PAGES = Pattern.compile("共\\s*(\\d+)\\s*条数据\\s*[,，]\\s*当前\\s*(\\d+)\\s*/\\s*(\\d+)\\s*页");
    private volatile String host = DEFAULT_HOST;
    private volatile SearchResult cachedSearch;
    private final Set<Call> calls = new HashSet<>();
    private final Map<String, CatalogPage> catalog = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, String> observedFilters = new HashMap<>();
    private long generation;
    private boolean destroyed;

    private static final class CatalogPage {
        final Document document;
        final long expires;
        CatalogPage(Document document) {
            this.document = document.clone();
            this.expires = System.nanoTime() + CATALOG_TTL;
        }
    }

    private static final class RequestScope {
        final long generation;
        final String host;
        RequestScope(long generation, String host) {
            this.generation = generation;
            this.host = host;
        }
    }

    private static final class SearchResult {
        final String key;
        final String json;
        final long expires;
        SearchResult(String key, String json) {
            this.key = key;
            this.json = json;
            this.expires = System.nanoTime() + 60_000_000_000L;
        }
    }

    @Override
    public void init(Context context, String extend) throws Exception {
        JSONObject options = new JSONObject(extend == null || extend.trim().isEmpty() ? "{}" : extend);
        URI uri = new URI(options.optString("host", DEFAULT_HOST).trim());
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !(uri.getPath().isEmpty() || "/".equals(uri.getPath()))) throw new IllegalArgumentException("Girigiri host 必須是 HTTP(S) 站點網址");
        List<Call> pending;
        synchronized (calls) {
            generation++;
            host = uri.toString().replaceAll("/+$", "");
            destroyed = false;
            cachedSearch = null;
            catalog.clear();
            observedFilters.clear();
            pending = new ArrayList<>(calls);
            calls.clear();
        }
        for (Call call : pending) call.cancel();
    }

    private JSONObject headers() throws Exception {
        return new JSONObject().put("User-Agent", UA).put("Referer", host + "/");
    }

    private RequestScope scope() throws InterruptedIOException {
        synchronized (calls) {
            RequestScope scope = new RequestScope(generation, host);
            checkScope(scope);
            return scope;
        }
    }

    private void checkScope(RequestScope scope) throws InterruptedIOException {
        synchronized (calls) {
            if (destroyed || scope.generation != generation || Thread.currentThread().isInterrupted())
                throw new InterruptedIOException("Girigiri 已停止");
        }
    }

    private String get(String path, RequestScope scope) throws Exception {
        Request request = new Request.Builder().url(scope.host + path)
                .header("User-Agent", UA).header("Referer", scope.host + "/").tag(siteKey).build();
        // Use the legacy host client: its proxy, DNS and optional ECH stay effective.
        Call call = client().newCall(request);
        synchronized (calls) {
            checkScope(scope);
            calls.add(call);
        }
        try (Response response = call.execute()) {
            if (!response.isSuccessful()) throw new IllegalStateException("Girigiri HTTP " + response.code());
            if (response.body() == null) throw new IllegalStateException("Girigiri 回應缺少正文");
            String body = response.body().string();
            checkScope(scope);
            return body;
        } finally {
            synchronized (calls) {
                calls.remove(call);
            }
        }
    }

    @Override
    public void destroy() {
        List<Call> pending;
        synchronized (calls) {
            destroyed = true;
            generation++;
            cachedSearch = null;
            catalog.clear();
            observedFilters.clear();
            pending = new ArrayList<>(calls);
            calls.clear();
        }
        for (Call call : pending) call.cancel();
    }

    private Document document(String path) throws Exception {
        return document(path, scope());
    }

    private Document document(String path, RequestScope scope) throws Exception {
        // Only the exact unfiltered page-one spelling shares its category's base cache key.
        String key = path.equals("/") || path.startsWith("/show/")
                ? DEFAULT_PAGE.matcher(path).replaceFirst("$1-----------/") : null;
        synchronized (calls) {
            checkScope(scope);
            CatalogPage cached = key == null ? null : catalog.get(key);
            if (cached != null && cached.expires > System.nanoTime()) return cached.document.clone();
            if (cached != null) catalog.remove(key);
        }
        Document doc = Jsoup.parse(get(path, scope), scope.host + path);
        if (!doc.select(".ds-verify-img, .verify-submit, input[name=verify]").isEmpty())
            throw new IllegalStateException("Girigiri 要求驗證碼，請先在網站完成驗證");
        if (!doc.select("form[action*=login] input[type=password]").isEmpty())
            throw new IllegalStateException("Girigiri 要求登入");
        if (key != null) {
            if (path.equals("/")) {
                if (cards(doc).length() == 0) throw new IllegalStateException("Girigiri 首頁列表解析失敗");
            } else {
                String[] parts = path.substring("/show/".length()).split("/", 2)[0].split("-", -1);
                int page = parts.length > 8 && !parts[8].isEmpty() ? pageNumber(parts[8]) : 1;
                filters(doc);
                categoryResult(doc, page);
            }
        }
        synchronized (calls) {
            checkScope(scope);
            if (key != null) {
                catalog.put(key, new CatalogPage(doc));
                while (catalog.size() > CATALOG_LIMIT) catalog.remove(catalog.keySet().iterator().next());
            }
        }
        return doc;
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        RequestScope scope = scope();
        Document first = document("/show/2-----------/", scope);
        JSONArray classes = new JSONArray();
        Map<String, String> names = new LinkedHashMap<>();
        for (Element row : first.select(".nav-swiper")) {
            if (!row.select(".filter-text").text().equals("频道")) continue;
            for (Element link : row.select("a[href]")) {
                Matcher matcher = CATEGORY.matcher(link.attr("href"));
                if (matcher.find()) names.put(matcher.group(1), link.text());
            }
        }
        if (names.isEmpty()) throw new IllegalStateException("Girigiri 分類結構已改變");
        for (Map.Entry<String, String> entry : names.entrySet())
            classes.put(new JSONObject().put("type_id", entry.getKey()).put("type_name", entry.getValue()));
        JSONObject result = new JSONObject().put("class", classes);
        if (filter) result.put("filters", homeFilters(names, first, scope));
        checkScope(scope);
        return result.toString();
    }

    private JSONObject homeFilters(Map<String, String> names, Document first, RequestScope scope) throws Exception {
        JSONObject result = new JSONObject();
        for (String tid : names.keySet()) {
            checkScope(scope);
            JSONArray options;
            if (tid.equals("2")) {
                options = filters(first);
            } else {
                String observed;
                synchronized (calls) {
                    checkScope(scope);
                    observed = observedFilters.get(tid);
                }
                options = observed == null ? seedFilters(tid) : new JSONArray(observed);
                if (options == null) {
                    options = filters(document("/show/" + tid + "-----------/", scope));
                    rememberFilters(tid, options, scope);
                }
            }
            result.put(tid, options);
        }
        return result;
    }

    private void rememberFilters(String tid, JSONArray options, RequestScope scope) throws Exception {
        synchronized (calls) {
            checkScope(scope);
            observedFilters.put(tid, options.toString());
        }
    }

    private JSONArray filters(Document doc) throws Exception {
        JSONArray result = new JSONArray();
        for (Element row : doc.select(".nav-swiper")) {
            Elements items = row.select("li[data-type][data-val]");
            if (items.isEmpty()) continue;
            String key = items.first().attr("data-type");
            if (!Arrays.asList("class", "area", "year", "lang", "version", "state").contains(key)) continue;
            JSONArray values = new JSONArray();
            for (Element item : items) values.put(new JSONObject().put("n", item.text()).put("v", item.attr("data-val")));
            result.put(new JSONObject().put("key", key).put("name", row.select(".filter-text").text()).put("value", values));
        }
        if (result.length() == 0) throw new IllegalStateException("Girigiri 篩選結構已改變");
        return withSort(result);
    }

    private JSONArray withSort(JSONArray result) throws Exception {
        JSONArray sort = new JSONArray();
        for (String[] item : new String[][]{{"最新", "time"}, {"最熱", "hits"}, {"評分", "score"}})
            sort.put(new JSONObject().put("n", item[0]).put("v", item[1]));
        return result.put(new JSONObject().put("key", "by").put("name", "排序").put("value", sort));
    }

    // Verified site definitions from 2026-10-06; opened category pages replace these in memory.
    private JSONArray seedFilters(String tid) throws Exception {
        String genres, languages = null, lastYear;
        int newestYear;
        switch (tid) {
            case "3":
                genres = "搞笑|爱情|恐怖|动作|科幻|剧情|战争|奇幻|冒险|悬疑|校园|后宫|热血|运动";
                languages = "国语|英语";
                newestYear = 2025;
                lastYear = "2000至90年代";
                break;
            case "21":
                genres = "喜剧|爱情|恐怖|动作|科幻|剧情|战争|奇幻|冒险|悬疑|校园|后宫|热血|运动|百合|耽美|机甲|日常|魔法少女|异世界|爱抖露|音乐";
                languages = "日语|中文|英语";
                newestYear = 2025;
                lastYear = "2000至90年代";
                break;
            case "20":
                genres = "喜剧|爱情|恐怖|动作|科幻|剧情|战争|奇幻|冒险|悬疑|校园|后宫|热血|运动|职场|百合|乙女|机甲|日常|魔法少女|异世界|爱抖露|音乐|萌|纪录片";
                languages = "日语|英语|泰语";
                newestYear = 2026;
                lastYear = "2000-90年代";
                break;
            case "24":
                genres = "喜剧|爱情|恐怖|动作|科幻|剧情|战争|奇幻|冒险|悬疑|校园|后宫|热血|运动|百合|乙女|机甲|日常|魔法少女|异世界|爱抖露|音乐";
                newestYear = 2023;
                lastYear = "2000";
                break;
            case "26":
                genres = "演唱会|周边活动|其他";
                newestYear = 2023;
                lastYear = "2000";
                break;
            default:
                return null;
        }
        JSONArray result = new JSONArray().put(seedFilter("class", "类型", genres))
                .put(seedFilter("area", "季度", "一月|四月|七月|十月"));
        StringBuilder years = new StringBuilder();
        for (int year = newestYear; year >= 2001; year--) years.append(year).append('|');
        result.put(seedFilter("year", "年份", years.append(lastYear).toString()));
        if (languages != null) result.put(seedFilter("lang", "语言", languages));
        return withSort(result);
    }

    private JSONObject seedFilter(String key, String name, String choices) throws Exception {
        JSONArray values = new JSONArray().put(new JSONObject().put("n", "全部").put("v", ""));
        for (String value : choices.split("\\|"))
            values.put(new JSONObject().put("n", value).put("v", value));
        return new JSONObject().put("key", key).put("name", name).put("value", values);
    }

    @Override
    public String homeVideoContent() throws Exception {
        JSONArray items = cards(document("/"));
        if (items.length() == 0) throw new IllegalStateException("Girigiri 首頁列表解析失敗");
        return new JSONObject().put("list", items).toString();
    }

    private JSONArray cards(Document doc) throws Exception {
        Map<String, JSONObject> items = new LinkedHashMap<>();
        for (Element card : doc.select(".public-list-box")) {
            Element link = card.selectFirst("a.public-list-exp[href]");
            if (link == null) throw new IllegalStateException("Girigiri 影片卡片缺少連結");
            if (link.attr("href").matches("/topicdetail-\\d+/?")) continue;
            Matcher matcher = DETAIL.matcher(link.attr("href"));
            if (!matcher.matches()) throw new IllegalStateException("Girigiri 影片連結格式已改變");
            String id = matcher.group(1);
            String name = link.attr("title");
            Element picture = link.selectFirst("img[data-src]");
            if (name.isEmpty() || picture == null || picture.attr("data-src").isEmpty())
                throw new IllegalStateException("Girigiri 影片卡片資料不完整");
            items.put(id, new JSONObject().put("vod_id", id).put("vod_name", name)
                    .put("vod_pic", picture.absUrl("data-src")).put("vod_remarks", card.select(".public-list-prb").text()));
        }
        JSONArray result = new JSONArray();
        for (JSONObject item : items.values()) result.put(item);
        return result;
    }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        if (!tid.matches("\\d+")) throw new IllegalArgumentException("Girigiri 分類 ID 無效");
        int page = pageNumber(pg);
        String[] parts = new String[12];
        Arrays.fill(parts, "");
        parts[0] = tid;
        parts[8] = String.valueOf(page);
        String[] keys = {"area", "by", "class", "lang", "state", "year"};
        int[] indexes = {1, 2, 3, 4, 9, 11};
        for (int i = 0; i < keys.length; i++) parts[indexes[i]] = encode(extend == null ? "" : extend.getOrDefault(keys[i], ""));
        String path = "/show/" + String.join("-", parts) + "/";
        if (extend != null && !extend.getOrDefault("version", "").isEmpty()) path += "version/" + encode(extend.get("version")) + "/";
        RequestScope scope = scope();
        Document doc = document(path, scope);
        JSONObject result = categoryResult(doc, page);
        rememberFilters(tid, filters(doc), scope);
        return result.toString();
    }

    private JSONObject categoryResult(Document doc, int page) throws Exception {
        JSONArray items = cards(doc);
        if (items.length() == 0) {
            if (doc.select(".null img[alt=空列表]").isEmpty()) throw new IllegalStateException("Girigiri 分類列表解析失敗");
            return new JSONObject().put("list", items).put("page", page).put("pagecount", 0).put("limit", PAGE_SIZE).put("total", 0);
        }
        Matcher paging = PAGES.matcher(doc.select(".page-tip").text());
        if (!paging.find()) throw new IllegalStateException("Girigiri 分頁資料解析失敗");
        int total = Integer.parseInt(paging.group(1));
        int current = Integer.parseInt(paging.group(2));
        int count = Integer.parseInt(paging.group(3));
        if (current != page || count < current || total < items.length()) throw new IllegalStateException("Girigiri 分頁資料不一致");
        return new JSONObject().put("list", items).put("page", current).put("pagecount", count).put("limit", PAGE_SIZE).put("total", total);
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception {
        return searchContent(key, quick, "1");
    }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        int page = pageNumber(pg);
        String query = key.trim();
        if (query.isEmpty()) throw new IllegalArgumentException("Girigiri 搜尋詞不能為空");
        // suggest ignores page/pg: slice the complete finite response locally.
        RequestScope scope = scope();
        SearchResult cached;
        synchronized (calls) {
            checkScope(scope);
            cached = cachedSearch;
        }
        JSONObject data;
        if (cached != null && cached.key.equals(query) && cached.expires > System.nanoTime()) {
            data = new JSONObject(cached.json);
        } else {
            data = suggestions(query, 100, scope);
            int total = data.getInt("total");
            if (total > data.getJSONArray("list").length()) data = suggestions(query, total, scope);
            if (data.getJSONArray("list").length() != data.getInt("total"))
                throw new IllegalStateException("Girigiri 搜尋 API 未提供完整結果");
            synchronized (calls) {
                checkScope(scope);
                cachedSearch = new SearchResult(query, data.toString());
            }
        }
        JSONArray records = data.getJSONArray("list");
        JSONArray items = new JSONArray();
        long start = (long) (page - 1) * PAGE_SIZE;
        long end = Math.min(records.length(), start + PAGE_SIZE);
        for (long index = start; index < end; index++) {
            JSONObject item = records.getJSONObject((int) index);
            String id = String.valueOf(item.get("id"));
            String name = item.getString("name");
            String pic = item.getString("pic");
            if (!id.matches("\\d+") || name.isEmpty() || pic.isEmpty()) throw new IllegalStateException("Girigiri 搜尋資料不完整");
            items.put(new JSONObject().put("vod_id", id).put("vod_name", name)
                    .put("vod_pic", URI.create(host + "/").resolve(pic).toString()).put("vod_remarks", ""));
        }
        return new JSONObject().put("list", items).put("page", page).put("pagecount", Math.max(1, (records.length() + PAGE_SIZE - 1) / PAGE_SIZE))
                .put("limit", PAGE_SIZE).put("total", records.length()).toString();
    }

    private JSONObject suggestions(String key, int limit, RequestScope scope) throws Exception {
        JSONObject data = new JSONObject(get("/index.php/ajax/suggest?mid=1&wd=" + encode(key) + "&limit=" + limit, scope));
        if (data.optInt("code", 0) != 1 || data.optJSONArray("list") == null || !data.has("total") || data.getInt("total") < 0)
            throw new IllegalStateException("Girigiri 搜尋 API 回應無效");
        return data;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        String id = ids.get(0);
        if (!id.matches("\\d+")) throw new IllegalArgumentException("Girigiri 影片 ID 無效");
        Document doc = document("/GV" + id + "/");
        Element title = doc.selectFirst(".slide-info-title");
        Element picture = doc.selectFirst(".detail-pic img[data-src]");
        if (title == null || title.text().isEmpty() || picture == null) throw new IllegalStateException("Girigiri 詳情解析失敗");
        JSONObject item = new JSONObject().put("vod_id", id).put("vod_name", title.text()).put("vod_pic", picture.absUrl("data-src"))
                .put("vod_remarks", doc.select(".slide-info-remarks.cor5").text()).put("vod_content", doc.select("#height_limit").text());
        for (Element link : doc.select(".detail-info .slide-info-remarks a"))
            if (link.text().matches("\\d{4}")) item.put("vod_year", link.text());
        for (Element row : doc.select(".slide-info.partition")) {
            String label = row.select("strong").text();
            String value = String.join(" / ", row.select("a").eachText());
            if (label.contains("导演")) item.put("vod_director", value);
            else if (label.contains("演员")) item.put("vod_actor", value);
            else if (label.contains("类型")) item.put("type_name", value);
        }
        Elements tabs = doc.select(".anthology-tab a");
        Elements lines = doc.select(".anthology-list-box");
        if (tabs.isEmpty() || tabs.size() != lines.size()) throw new IllegalStateException("Girigiri 播放線路解析失敗");
        List<String> flags = new ArrayList<>();
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < tabs.size(); i++) {
            Element tab = tabs.get(i).clone();
            tab.select(".badge, i").remove();
            String name = clean(tab.text());
            List<String> episodes = new ArrayList<>();
            for (Element episode : lines.get(i).select(".anthology-list-play a[href]")) {
                String path = episode.attr("href");
                String label = clean(episode.text());
                if (!PLAY.matcher(path).matches() || label.isEmpty()) throw new IllegalStateException("Girigiri 集數資料無效");
                episodes.add(label + "$" + path);
            }
            if (name.isEmpty() || episodes.isEmpty()) throw new IllegalStateException("Girigiri 播放線路沒有可用集數");
            flags.add(name);
            urls.add(String.join("#", episodes));
        }
        item.put("vod_play_from", String.join("$$$", flags)).put("vod_play_url", String.join("$$$", urls));
        return new JSONObject().put("list", new JSONArray().put(item)).toString();
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        if (!PLAY.matcher(id).matches()) throw new IllegalArgumentException("Girigiri 播放 ID 無效");
        Document doc = document(id);
        JSONObject player = null;
        Pattern assignment = Pattern.compile("\\bvar\\s+player_\\w+\\s*=\\s*");
        for (Element script : doc.select("script")) {
            String source = script.data();
            Matcher match = assignment.matcher(source);
            if (!match.find()) continue;
            Object value = new JSONTokener(source.substring(match.end())).nextValue();
            if (value instanceof JSONObject) player = (JSONObject) value;
            break;
        }
        if (player == null) throw new IllegalStateException("Girigiri 播放資料解析失敗");
        String url = player.getString("url");
        int encrypt = player.getInt("encrypt");
        if (encrypt == 2) url = new String(Base64.decode(url, Base64.DEFAULT), StandardCharsets.UTF_8);
        if (encrypt == 1 || encrypt == 2) url = URLDecoder.decode(url, "UTF-8");
        else if (encrypt != 0) throw new IllegalStateException("Girigiri 播放加密格式不受支援");
        URI uri = new URI(url);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) || uri.getHost() == null)
            throw new IllegalStateException("Girigiri 未提供有效媒體網址");
        String path = uri.getPath().toLowerCase(java.util.Locale.ROOT);
        if (!path.endsWith(".m3u8") && !path.endsWith(".mp4")) throw new IllegalStateException("Girigiri 媒體格式需要新的解析方式");
        return new JSONObject().put("parse", 0).put("jx", 0).put("url", url).put("header", headers()).toString();
    }

    private int pageNumber(String value) {
        int number = Integer.parseInt(value);
        if (number < 1) throw new IllegalArgumentException("頁碼必須大於零");
        return number;
    }

    private String encode(String value) throws Exception {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("-", "%2D");
    }

    private String clean(String value) {
        return value.replace('$', '＄').replace('#', '＃').trim();
    }
}
