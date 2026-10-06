package com.github.catvod.spider;

import android.content.Context;
import android.util.Base64;
import android.webkit.CookieManager;

import com.github.catvod.crawler.Spider;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** Kazumi's data rules on the legacy Spider ABI, usable by both old and new host Apps. */
public class Kazumi extends Spider {
    private static final String UA = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Pattern PLAYER = Pattern.compile("(?:var\\s+)?player_[A-Za-z0-9_]+\\s*=");
    private static final Pattern MEDIA = Pattern.compile("(?i)\\.(?:m3u8|mp4|mpd|flv|m4v|webm|mkv)(?:$|[?#])");
    private final Set<Call> calls = new HashSet<>();
    private final LinkedHashMap<String, Cached> cache = new LinkedHashMap<>(16, .75f, true);
    private final OriginCookies cookies = new OriginCookies();
    private final LinkedHashMap<String, Long> mediaRoutes = new LinkedHashMap<>(16, .75f, true);
    private final byte[] mediaSecret = new byte[32];

    public Kazumi() { new java.security.SecureRandom().nextBytes(mediaSecret); }
    private volatile KazumiRule rule;
    private volatile OkHttpClient http;
    private volatile String proxy = "";
    private volatile String proxyMode = "fallback";
    private volatile boolean mediaProxy = true;
    private long generation;
    private boolean destroyed;

    @Override
    public void init(Context context, String extend) throws Exception {
        JSONObject options = new JSONObject(extend == null || extend.trim().isEmpty() ? "{}" : extend);
        destroy();
        synchronized (calls) { destroyed = false; generation++; }
        http = client().newBuilder().cookieJar(cookies).connectTimeout(2500, TimeUnit.MILLISECONDS)
                .readTimeout(6, TimeUnit.SECONDS).writeTimeout(6, TimeUnit.SECONDS).build();
        String endpoint = options.optString("proxy", "").trim();
        if (!endpoint.isEmpty()) {
            HttpUrl parsed = HttpUrl.parse(endpoint);
            if (parsed == null || !parsed.isHttps() || !parsed.username().isEmpty() || !parsed.password().isEmpty()
                    || parsed.query() != null || parsed.fragment() != null) throw new IllegalArgumentException("Kazumi proxy must be a plain HTTPS endpoint");
            proxy = parsed.toString();
        } else proxy = "";
        proxyMode = options.optString("proxyMode", "fallback");
        mediaProxy = options.optBoolean("mediaProxy", true);
        if (!List.of("off", "fallback", "always").contains(proxyMode)) throw new IllegalArgumentException("Invalid Kazumi proxyMode");
        Object value = options.opt("rule");
        if (value instanceof JSONObject) rule = new KazumiRule((JSONObject) value);
        else if (value instanceof String && ((String) value).startsWith("https://")) {
            long scope = scope();
            Request request = new Request.Builder().url((String) value).header("User-Agent", UA).build();
            rule = new KazumiRule(new JSONObject(execute(request, scope).body));
        } else if (options.has("baseURL")) rule = new KazumiRule(options);
        else throw new IllegalArgumentException("Kazumi ext.rule must contain a rule object or pinned HTTPS JSON URL");
    }

    @Override
    public String homeContent(boolean filter) throws Exception {
        ready();
        return new JSONObject().put("class", new JSONArray()).put("list", new JSONArray())
                .put("msg", "此 Kazumi 规则提供搜索与播放，请使用全局搜索").toString();
    }

    @Override
    public String homeVideoContent() throws Exception { ready(); return page(new JSONArray()).toString(); }

    @Override
    public String categoryContent(String tid, String pg, boolean filter, HashMap<String, String> extend) throws Exception {
        ready(); return page(new JSONArray()).put("msg", "此规则没有分类接口，请使用搜索").toString();
    }

    @Override
    public String searchContent(String key, boolean quick) throws Exception { return searchContent(key, quick, "1"); }

    @Override
    public String searchContent(String key, boolean quick, String pg) throws Exception {
        ready();
        int requestedPage = Integer.parseInt(pg);
        if (requestedPage < 1) throw new IllegalArgumentException("Kazumi page must be positive");
        if (requestedPage != 1 || key == null || key.trim().isEmpty()) return page(new JSONArray()).put("page", requestedPage).toString();
        long scope = scope();
        String cacheKey = "search:" + key;
        String cached = cached(cacheKey, scope);
        if (cached != null) return cached;
        Fetch fetch = fetch(rule.request(true, key.trim()), scope);
        JSONArray items = new JSONArray();
        for (KazumiRule.Item item : rule.search(fetch.body)) {
            JSONObject id = new JSONObject().put("source", item.value).put("name", item.name);
            items.put(new JSONObject().put("vod_id", encode(id)).put("vod_name", item.name).put("vod_pic", ""));
        }
        String result = page(items).toString();
        remember(cacheKey, result, scope, 30_000);
        return result;
    }

    @Override
    public String detailContent(List<String> ids) throws Exception {
        ready(); if (ids == null || ids.isEmpty()) throw new IllegalArgumentException("Kazumi detail ID is required");
        JSONObject id = decode(ids.get(0));
        long scope = scope(); String cacheKey = "detail:" + ids.get(0); String cached = cached(cacheKey, scope);
        if (cached != null) return cached;
        String source = id.getString("source");
        Fetch fetch = fetch(rule.request(false, source), scope);
        List<KazumiRule.Road> roads = rule.chapters(fetch.body, source);
        List<String> names = new ArrayList<>(); List<String> playlist = new ArrayList<>();
        for (KazumiRule.Road road : roads) {
            names.add(label(road.name)); List<String> entries = new ArrayList<>();
            for (KazumiRule.Item episode : road.episodes) {
                JSONObject value = new JSONObject().put("url", episode.value).put("referer", fetch.url);
                entries.add(label(episode.name) + "$" + encode(value));
            }
            playlist.add(String.join("#", entries));
        }
        JSONObject vod = new JSONObject().put("vod_id", ids.get(0)).put("vod_name", id.optString("name", rule.name))
                .put("vod_pic", "").put("vod_play_from", String.join("$$$", names)).put("vod_play_url", String.join("$$$", playlist));
        String result = new JSONObject().put("list", new JSONArray().put(vod)).toString();
        remember(cacheKey, result, scope, 60_000);
        return result;
    }

    @Override
    public String playerContent(String flag, String id, List<String> vipFlags) throws Exception {
        ready(); JSONObject value = decode(id); String pageUrl = KazumiRule.url(value.getString("url"), rule.base);
        long scope = scope();
        if (media(pageUrl)) return player(pageUrl, value.optString("referer", rule.referer), false);
        Fetch page = fetch(new KazumiRule.RequestSpec("GET", pageUrl, new JSONObject(), "none", null), scope);
        String direct = staticMedia(page.body, page.url);
        if (!direct.isEmpty()) return player(direct, page.url, false);
        // The old App already owns a WebView sniffer; do not navigate a raw proxy HTML URL.
        return player(page.url, rule.referer, true);
    }

    private String player(String target, String referer, boolean sniff) throws Exception {
        JSONObject headers = new JSONObject().put("User-Agent", ua()).put("Referer", referer);
        String cookie = cookies.header(HttpUrl.get(target));
        if (!cookie.isEmpty()) headers.put("Cookie", cookie);
        String playback = !sniff && mediaProxy && !proxy.isEmpty() && !proxyMode.equals("off")
                ? localMedia(target, referer) : target;
        JSONObject result = new JSONObject().put("parse", sniff ? 1 : 0).put("jx", 0).put("url", playback).put("header", headers);
        if (!playback.equals(target) && HttpUrl.get(target).encodedPath().endsWith(".m3u8")) result.put("format", "application/x-mpegURL");
        return result.toString();
    }

    private String staticMedia(String html, String base) throws Exception {
        String trimmed = html.trim();
        if (trimmed.startsWith("#EXTM3U")) return base;
        Matcher player = PLAYER.matcher(html);
        while (player.find()) {
            try {
                Object parsed = new JSONTokener(html.substring(player.end()).trim()).nextValue();
                if (!(parsed instanceof JSONObject)) continue;
                JSONObject data = (JSONObject) parsed; String target = data.optString("url"); int encryption = data.optInt("encrypt");
                if (encryption == 1) target = URLDecoder.decode(target, "UTF-8");
                else if (encryption == 2) target = URLDecoder.decode(new String(Base64.decode(target, Base64.DEFAULT), StandardCharsets.UTF_8), "UTF-8");
                if (media(target)) return KazumiRule.url(target, base);
            } catch (Exception ignored) { }
        }
        Matcher configuredPlayer = Pattern.compile("new\\s+(?:Artplayer|DPlayer)\\s*\\(\\s*\\{[\\s\\S]{0,2048}?\\b(?:url|src)\\s*:\\s*(['\"])(https?://[^'\"]+)\\1").matcher(html);
        while (configuredPlayer.find()) if (media(configuredPlayer.group(2))) return KazumiRule.url(configuredPlayer.group(2), base);
        for (Element element : Jsoup.parse(html, base).select("video[src], source[src], iframe[src]")) {
            String target = element.absUrl("src");
            if (media(target)) return target;
            if (element.tagName().equals("iframe")) {
                HttpUrl frame = HttpUrl.parse(target);
                if (frame == null) continue;
                for (String key : List.of("url", "v", "vid", "play")) {
                    String candidate = frame.queryParameter(key);
                    if (candidate != null && media(candidate)) return KazumiRule.url(candidate, base);
                }
            }
        }
        return "";
    }

    private String localMedia(String target, String referer) throws Exception {
        // getUrl(boolean) predates the new Spider SDK and exists in the tested legacy host.
        try {
            if (com.github.catvod.Proxy.getPort() <= 0) return target;
            JSONObject value = new JSONObject().put("url", target).put("referer", referer);
            return HttpUrl.get(com.github.catvod.Proxy.getUrl(true)).newBuilder()
                    .addQueryParameter("do", "jar").addQueryParameter("siteKey", siteKey)
                    .addQueryParameter("kazumi", mediaToken(value)).addQueryParameter("ext", HttpUrl.get(target).encodedPath().endsWith(".m3u8") ? ".m3u8" : ".bin").build().toString();
        } catch (LinkageError unsupportedHost) {
            return target;
        }
    }

    private String mediaToken(JSONObject value) throws Exception {
        String payload = encode(value);
        return payload + "." + mediaSignature(payload);
    }

    private JSONObject mediaValue(String token) throws Exception {
        int split = token == null ? -1 : token.lastIndexOf('.');
        if (split < 0) throw new IllegalArgumentException("Invalid Kazumi media token");
        String payload = token.substring(0, split);
        if (!java.security.MessageDigest.isEqual(mediaSignature(payload).getBytes(StandardCharsets.US_ASCII),
                token.substring(split + 1).getBytes(StandardCharsets.US_ASCII)))
            throw new IllegalArgumentException("Expired or invalid Kazumi media token");
        return decode(payload);
    }

    private String mediaSignature(String payload) throws Exception {
        javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
        mac.init(new javax.crypto.spec.SecretKeySpec(mediaSecret, "HmacSHA256"));
        long scope = scope();
        return Base64.encodeToString(mac.doFinal((scope + ":" + payload).getBytes(StandardCharsets.UTF_8)),
                Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    @Override
    public Object[] proxy(Map<String, String> params) throws Exception {
        ready();
        if (!params.containsKey("kazumi")) throw new IllegalArgumentException("Kazumi media request is missing");
        JSONObject value = mediaValue(params.get("kazumi")); String target = KazumiRule.url(value.getString("url"), rule.base);
        String referer = value.optString("referer", rule.referer); long scope = scope();
        JSONObject headers = new JSONObject().put("Referer", referer);
        String range = params.get("range"); if (range == null) range = params.get("Range");
        String ifRange = params.get("if-range"); if (ifRange == null) ifRange = params.get("If-Range");
        if (!HttpUrl.get(target).encodedPath().endsWith(".m3u8")) {
            if (range != null) headers.put("Range", range);
            if (ifRange != null) headers.put("If-Range", ifRange);
        }
        KazumiRule.RequestSpec spec = new KazumiRule.RequestSpec("GET", target, headers, "none", null);
        OpenMedia media = openMedia(spec, scope, proxyMode.equals("always") && !proxy.isEmpty());
        Response response = media.response;
        String mime = response.header("Content-Type", "application/octet-stream");
        Map<String, String> outgoing = new LinkedHashMap<>(); outgoing.put("Cache-Control", "no-store");
        for (String key : List.of("Content-Range", "Accept-Ranges", "ETag", "Last-Modified")) {
            String header = response.header(key); if (header != null) outgoing.put(key, header);
        }
        if (response.isSuccessful() && (mime.toLowerCase(java.util.Locale.ROOT).contains("mpegurl")
                || HttpUrl.get(target).encodedPath().endsWith(".m3u8"))) {
            try {
                if (response.body() == null) throw new IOException("Kazumi HLS body is missing");
                ByteArrayOutputStream data = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int size;
                InputStream input = response.body().byteStream();
                while ((size = input.read(buffer)) != -1) { check(scope); if (data.size() + size > MAX_BODY) throw new IOException("Kazumi HLS is too large"); data.write(buffer, 0, size); }
                String manifest = data.toString("UTF-8");
                if (!manifest.trim().startsWith("#EXTM3U")) throw new IOException("Kazumi HLS response is not a playlist");
                String base = media.viaProxy ? response.header("X-Kazumi-Upstream-URL", target) : response.request().url().toString();
                String rewritten = rewriteHls(manifest, base, referer);
                outgoing.remove("Content-Range"); outgoing.remove("ETag"); outgoing.remove("Last-Modified");
                return new Object[]{200, "application/vnd.apple.mpegurl", new ByteArrayInputStream(rewritten.getBytes(StandardCharsets.UTF_8)), outgoing};
            } catch (IOException error) {
                if (media.viaProxy) forgetMediaRoute(mediaOrigin(target));
                throw error;
            } finally { media.close(); }
        }
        if (response.body() == null) { media.close(); throw new IOException("Kazumi media body is missing"); }
        InputStream stream = new FilterInputStream(response.body().byteStream()) {
            @Override public int read() throws IOException {
                check(scope);
                try { return super.read(); } catch (IOException error) { if (media.viaProxy) forgetMediaRoute(mediaOrigin(target)); throw error; }
            }
            @Override public int read(byte[] b, int offset, int length) throws IOException {
                check(scope);
                try { return super.read(b, offset, length); } catch (IOException error) { if (media.viaProxy) forgetMediaRoute(mediaOrigin(target)); throw error; }
            }
            @Override public void close() { media.close(); }
        };
        return new Object[]{response.code(), mime, stream, outgoing};
    }

    private String rewriteHls(String manifest, String base, String referer) throws Exception {
        StringBuilder result = new StringBuilder();
        Pattern uri = Pattern.compile("URI=\"([^\"]+)\"");
        for (String line : manifest.split("\r?\n", -1)) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) line = localMedia(KazumiRule.url(trimmed, base), referer);
            else if (trimmed.startsWith("#")) {
                Matcher matcher = uri.matcher(line); StringBuffer changed = new StringBuffer();
                while (matcher.find()) {
                    String value = matcher.group(1);
                    // Inline data keys and other non-network URI schemes must remain untouched.
                    if (value.startsWith("data:")) matcher.appendReplacement(changed, Matcher.quoteReplacement(matcher.group()));
                    else matcher.appendReplacement(changed, Matcher.quoteReplacement("URI=\"" + localMedia(KazumiRule.url(value, base), referer) + "\""));
                }
                line = matcher.appendTail(changed).toString();
            }
            result.append(line).append('\n');
        }
        return result.toString();
    }

    private OpenMedia openMedia(KazumiRule.RequestSpec spec, long scope, boolean forcedProxy) throws Exception {
        if (forcedProxy || proxy.isEmpty() || !proxyMode.equals("fallback")) return mediaAttempt(spec, scope, forcedProxy);
        String origin = mediaOrigin(spec.url);
        boolean firstProxy = preferredMediaRoute(origin, scope);
        OpenMedia first;
        try {
            first = mediaAttempt(spec, scope, firstProxy);
        } catch (IOException error) {
            check(scope);
            if (error instanceof InterruptedIOException && !(error instanceof java.net.SocketTimeoutException)) throw error;
            if (firstProxy) forgetMediaRoute(origin);
            OpenMedia second = mediaAttempt(spec, scope, !firstProxy);
            try {
                if (!firstProxy && second.response.isSuccessful()) rememberMediaRoute(origin, scope);
                return second;
            } catch (Exception failure) { second.close(); throw failure; }
        }
        if (firstProxy ? first.response.isSuccessful() : first.response.code() != 403 && first.response.code() < 500) return first;
        boolean canceled = first.call.isCanceled();
        first.close();
        check(scope);
        if (canceled) throw new InterruptedIOException("Kazumi media request canceled");
        if (firstProxy) forgetMediaRoute(origin);
        OpenMedia second = mediaAttempt(spec, scope, !firstProxy);
        try {
            if (!firstProxy && second.response.isSuccessful()) rememberMediaRoute(origin, scope);
            return second;
        } catch (Exception failure) { second.close(); throw failure; }
    }

    private OpenMedia mediaAttempt(KazumiRule.RequestSpec spec, long scope, boolean viaProxy) throws Exception {
        Request request = request(spec, viaProxy);
        // Streaming has no total-call timeout; preserve a normal read timeout for active playback.
        OkHttpClient transport = http.newBuilder().connectTimeout(viaProxy ? 5 : 3, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS).build();
        Call call = transport.newCall(request); synchronized (calls) { check(scope); calls.add(call); }
        try {
            Response response = call.execute();
            return new OpenMedia(call, response, viaProxy);
        } catch (IOException error) {
            synchronized (calls) { calls.remove(call); }
            check(scope);
            if (call.isCanceled()) {
                InterruptedIOException canceled = new InterruptedIOException("Kazumi media request canceled");
                canceled.initCause(error); throw canceled;
            }
            throw error;
        }
    }

    private static String mediaOrigin(String target) {
        HttpUrl url = HttpUrl.get(target);
        return url.scheme() + "://" + url.host() + ":" + url.port();
    }

    private boolean preferredMediaRoute(String origin, long scope) throws Exception {
        synchronized (calls) {
            check(scope);
            Long expiry = mediaRoutes.get(origin);
            if (expiry == null) return false;
            if (expiry > System.nanoTime()) return true;
            mediaRoutes.remove(origin); return false;
        }
    }

    private void rememberMediaRoute(String origin, long scope) throws Exception {
        synchronized (calls) {
            check(scope); mediaRoutes.put(origin, System.nanoTime() + TimeUnit.SECONDS.toNanos(60));
            while (mediaRoutes.size() > 32) mediaRoutes.remove(mediaRoutes.keySet().iterator().next());
        }
    }

    private void forgetMediaRoute(String origin) { synchronized (calls) { mediaRoutes.remove(origin); } }

    private final class OpenMedia {
        final Call call; final Response response; final boolean viaProxy;
        OpenMedia(Call call, Response response, boolean viaProxy) { this.call = call; this.response = response; this.viaProxy = viaProxy; }
        void close() { response.close(); synchronized (calls) { calls.remove(call); } }
    }

    private static boolean media(String url) { return url != null && (url.startsWith("https://") || url.startsWith("http://") || url.startsWith("//")) && MEDIA.matcher(url).find(); }
    private String ua() { return rule.userAgent.isEmpty() ? UA : rule.userAgent; }

    private Fetch fetch(KazumiRule.RequestSpec spec, long scope) throws Exception {
        Request request = request(spec, false);
        if (!proxy.isEmpty() && proxyMode.equals("always")) return checked(execute(request(spec, true), scope, true));
        Fetch direct;
        try {
            direct = execute(request, scope);
        } catch (IOException failure) {
            check(scope);
            if (failure instanceof InterruptedIOException && !(failure instanceof java.net.SocketTimeoutException)
                    || proxy.isEmpty() || !proxyMode.equals("fallback")) throw failure;
            return checked(execute(request(spec, true), scope, true));
        }
        if (direct.code >= 200 && direct.code < 300) return direct;
        if (!proxy.isEmpty() && proxyMode.equals("fallback") && (direct.code == 403 || direct.code == 429 || direct.code >= 500))
            return checked(execute(request(spec, true), scope, true));
        return checked(direct);
    }

    private Fetch checked(Fetch result) throws IOException {
        if (result.code < 200 || result.code >= 300) throw new IOException(rule.name + " HTTP " + result.code);
        return result;
    }

    private Request request(KazumiRule.RequestSpec spec, boolean viaProxy) throws Exception {
        HttpUrl upstream = HttpUrl.get(spec.url);
        Request.Builder request = new Request.Builder().url(viaProxy ? HttpUrl.get(proxy).newBuilder().addQueryParameter("url", upstream.toString()).build() : upstream).tag(siteKey);
        Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (Iterator<String> keys = spec.headers.keys(); keys.hasNext();) { String key = keys.next(); headers.put(key, spec.headers.getString(key)); }
        headers.putIfAbsent("User-Agent", ua());
        headers.putIfAbsent("Referer", rule.referer);
        String cookie = cookies.header(upstream);
        if (!cookie.isEmpty()) headers.putIfAbsent("Cookie", cookie);
        if (viaProxy) request.header("X-Kazumi-Credential-Host", upstream.host()).header("User-Agent", UA);
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            String key = entry.getKey(); String value = entry.getValue(); String lower = key.toLowerCase(java.util.Locale.ROOT);
            if (List.of("host", "proxy-authorization", "connection", "content-length", "transfer-encoding").contains(lower)) continue;
            String target = key;
            if (viaProxy) {
                if (lower.equals("authorization")) target = "X-Kazumi-Upstream-Authorization";
                else if (lower.equals("apikey")) target = "X-Kazumi-Upstream-Apikey";
                else if (lower.equals("cookie")) target = "X-Kazumi-Upstream-Cookie";
                else if (lower.equals("referer")) target = "X-Kazumi-Referer";
                else if (lower.equals("origin")) target = "X-Kazumi-Origin";
                else if (lower.equals("user-agent")) target = "X-Kazumi-User-Agent";
                else if (!List.of("accept", "accept-language", "content-type", "range", "if-range", "connect-protocol-version").contains(lower)) continue;
            }
            request.header(target, value);
        }
        if (spec.method.equals("POST")) {
            RequestBody body;
            if (spec.bodyType.equals("form")) {
                FormBody.Builder form = new FormBody.Builder();
                JSONObject values = spec.body instanceof JSONObject ? (JSONObject) spec.body : new JSONObject();
                for (Iterator<String> keys = values.keys(); keys.hasNext();) { String key = keys.next(); form.add(key, KazumiRule.scalar(values.get(key))); }
                body = form.build();
            } else if (spec.bodyType.equals("json")) body = RequestBody.create(spec.body == null ? "null" : spec.body.toString(), MediaType.get("application/json; charset=utf-8"));
            else body = RequestBody.create(new byte[0], null);
            request.post(body);
        }
        return request.build();
    }

    private Fetch execute(Request request, long scope) throws Exception { return execute(request, scope, false); }

    private Fetch execute(Request request, long scope, boolean viaProxy) throws Exception {
        OkHttpClient transport = viaProxy ? http.newBuilder().connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS).writeTimeout(10, TimeUnit.SECONDS).build() : http;
        Call call = transport.newCall(request); call.timeout().timeout(viaProxy ? 15 : 10, TimeUnit.SECONDS);
        synchronized (calls) { check(scope); calls.add(call); }
        try (Response response = call.execute()) {
            if (response.body() == null) throw new IOException("Kazumi response body is missing");
            if (response.body().contentLength() > MAX_BODY) throw new IOException("Kazumi metadata response is too large");
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream input = response.body().byteStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = input.read(buffer)) != -1) { check(scope); if (bytes.size() + count > MAX_BODY) throw new IOException("Kazumi metadata response is too large"); bytes.write(buffer, 0, count); }
            }
            String finalUrl = viaProxy ? response.header("X-Kazumi-Upstream-URL", specOrigin(request)) : response.request().url().toString();
            HttpUrl original = HttpUrl.parse(finalUrl);
            if (original == null) throw new IOException("Kazumi upstream URL is invalid");
            String encodedCookies = viaProxy ? response.header("X-Kazumi-Set-Cookie") : null;
            if (encodedCookies != null) {
                JSONArray values = new JSONArray(URLDecoder.decode(encodedCookies, "UTF-8")); List<Cookie> received = new ArrayList<>();
                for (int i = 0; i < values.length(); i++) { Cookie cookie = Cookie.parse(original, values.getString(i)); if (cookie != null) received.add(cookie); }
                cookies.saveFromResponse(original, received);
            }
            java.nio.charset.Charset charset = response.body().contentType() == null ? StandardCharsets.UTF_8 : response.body().contentType().charset(StandardCharsets.UTF_8);
            check(scope); return new Fetch(response.code(), finalUrl, bytes.toString(charset.name()));
        } catch (IOException failure) {
            check(scope);
            if (call.isCanceled()) {
                if (failure instanceof InterruptedIOException && "timeout".equals(failure.getMessage())) {
                    java.net.SocketTimeoutException timeout = new java.net.SocketTimeoutException("Kazumi metadata call timed out");
                    timeout.initCause(failure); throw timeout;
                }
                InterruptedIOException canceled = new InterruptedIOException("Kazumi request canceled");
                canceled.initCause(failure); throw canceled;
            }
            throw failure;
        } finally { synchronized (calls) { calls.remove(call); } }
    }

    private static String specOrigin(Request request) throws IOException {
        String origin = request.url().queryParameter("url");
        if (origin == null || HttpUrl.parse(origin) == null) throw new IOException("Kazumi proxy target is missing");
        return origin;
    }

    private long scope() throws InterruptedIOException { synchronized (calls) { check(generation); return generation; } }
    private void check(long scope) throws InterruptedIOException { synchronized (calls) { if (destroyed || scope != generation || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Kazumi request canceled"); } }
    private void ready() throws Exception { scope(); if (rule == null) throw new IllegalStateException("Kazumi is not initialized"); }
    private String cached(String key, long scope) throws Exception { synchronized (calls) { check(scope); Cached value = cache.get(key); if (value == null) return null; if (value.expires > System.nanoTime()) return value.value; cache.remove(key); return null; } }
    private void remember(String key, String value, long scope, long ttl) throws Exception {
        synchronized (calls) {
            check(scope);
            if (value.length() > 2 * 1024 * 1024) return;
            cache.put(key, new Cached(value, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(ttl)));
            long size = 0; for (Cached item : cache.values()) size += item.value.length();
            while (cache.size() > 24 || size > 2 * 1024 * 1024) size -= cache.remove(cache.keySet().iterator().next()).value.length();
        }
    }

    @Override
    public void destroy() {
        List<Call> pending;
        synchronized (calls) { destroyed = true; generation++; cache.clear(); mediaRoutes.clear(); pending = new ArrayList<>(calls); calls.clear(); }
        for (Call call : pending) call.cancel();
        cookies.clear(); rule = null;
    }

    private static JSONObject page(JSONArray items) throws Exception { return new JSONObject().put("list", items).put("page", 1).put("pagecount", 1).put("limit", Math.max(1, items.length())); }
    private static String label(String value) { return value.replace('$', '＄').replace('#', '＃').replace('\n', ' ').replace('\r', ' ').trim(); }
    private static String encode(JSONObject value) { return "kzm:" + Base64.encodeToString(value.toString().getBytes(StandardCharsets.UTF_8), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING); }
    private static JSONObject decode(String value) throws Exception { if (value == null || !value.startsWith("kzm:")) throw new IllegalArgumentException("Invalid Kazumi ID"); return new JSONObject(new String(Base64.decode(value.substring(4), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING), StandardCharsets.UTF_8)); }
    private static final class Cached { final String value; final long expires; Cached(String value, long expires) { this.value = value; this.expires = expires; } }
    private static final class Fetch { final int code; final String url, body; Fetch(int code, String url, String body) { this.code = code; this.url = url; this.body = body; } }

    private static final class OriginCookies implements CookieJar {
        private final List<Cookie> values = new ArrayList<>();
        synchronized void clear() { values.clear(); }
        @Override public synchronized void saveFromResponse(HttpUrl url, List<Cookie> cookies) {
            for (Cookie cookie : cookies) { values.removeIf(old -> old.name().equals(cookie.name()) && old.domain().equals(cookie.domain()) && old.path().equals(cookie.path())); if (cookie.expiresAt() > System.currentTimeMillis()) values.add(cookie); }
            while (values.size() > 128) values.remove(0);
        }
        @Override public synchronized List<Cookie> loadForRequest(HttpUrl url) {
            values.removeIf(cookie -> cookie.expiresAt() <= System.currentTimeMillis());
            List<Cookie> result = new ArrayList<>();
            for (Cookie cookie : values) if (cookie.matches(url)) result.add(cookie);
            try {
                String browser = CookieManager.getInstance().getCookie(url.toString());
                if (browser != null) for (String item : browser.split(";")) {
                    Cookie cookie = Cookie.parse(url, item.trim());
                    if (cookie != null) { result.removeIf(old -> old.name().equals(cookie.name())); result.add(cookie); }
                }
            } catch (RuntimeException ignored) { }
            return result;
        }
        synchronized String header(HttpUrl url) {
            List<String> parts = new ArrayList<>();
            for (Cookie cookie : loadForRequest(url)) parts.add(cookie.name() + "=" + cookie.value());
            return String.join("; ", parts);
        }
    }
}
