// Generated with build.py from the shared host manifest. No secrets belong here.
const POLICY = __KAZUMI_POLICY_JSON__;
const SAFE_REQUEST_HEADERS = ["accept", "accept-language", "range", "if-range", "if-none-match", "if-modified-since", "cache-control"];
const SENSITIVE_ENVELOPE = {
    "x-kazumi-upstream-authorization": "authorization",
    "x-kazumi-upstream-apikey": "apikey",
    "x-kazumi-upstream-cookie": "cookie",
};
const REDIRECTS = new Set([301, 302, 303, 307, 308]);
const EXPOSE_HEADERS = "X-Kazumi-Upstream-URL, X-Kazumi-Set-Cookie, Content-Range, Accept-Ranges, Content-Length, ETag, Last-Modified, CF-Mitigated";

function error(status, code) {
    return new Response(JSON.stringify({error: code}), {status, headers: {
        "content-type": "application/json; charset=utf-8",
        "cache-control": "no-store",
        "access-control-allow-origin": "*",
    }});
}

function allowedTarget(raw, proxyHost) {
    if (!raw || raw.length > 8192 || /[\u0000-\u0020\u007f\\]/.test(raw)) throw new Error("target_invalid");
    const url = new URL(raw);
    const host = url.hostname.toLowerCase().replace(/\.$/, "");
    // Exact, reviewed public hostnames only. Never use a suffix wildcard here.
    if (url.username || url.password || url.port || !host.includes(".") || /[:]/.test(host)
        || /^[\d.]+$/.test(host) || /\.(?:localhost|local|internal|test|invalid)$/.test(host)
        || host === proxyHost || !Object.hasOwn(POLICY.hosts, host)) throw new Error("target_forbidden");
    if (url.protocol !== "https:" && !(url.protocol === "http:" && POLICY.hosts[host].http === true)) throw new Error("scheme_forbidden");
    url.hostname = host;
    url.hash = "";
    return url;
}

function explicitReferrer(value, target, proxyHost) {
    if (!value) return null;
    const ref = allowedTarget(value, proxyHost);
    // Do not disclose query/path information when the source switches origins.
    return ref.origin === target.origin ? ref.href : ref.origin + "/";
}

function upstreamHeaders(request, target, proxyHost) {
    const headers = new Headers();
    for (const name of SAFE_REQUEST_HEADERS) {
        const value = request.headers.get(name);
        if (value !== null) headers.set(name, value);
    }
    headers.set("user-agent", request.headers.get("x-kazumi-user-agent") || POLICY.userAgent);
    const referer = explicitReferrer(request.headers.get("x-kazumi-referer"), target, proxyHost);
    headers.set("referer", referer || target.origin + "/");
    const origin = request.headers.get("x-kazumi-origin");
    if (origin) headers.set("origin", allowedTarget(origin, proxyHost).origin);
    // Neither the incoming Host nor normal Authorization/Cookie/Proxy-* is copied.
    // The adapter must deliberately opt in with a hostname-bound envelope.
    const credentialHost = (request.headers.get("x-kazumi-credential-host") || "").trim().toLowerCase();
    let authenticated = false;
    if (credentialHost === target.hostname) {
        for (const [envelope, name] of Object.entries(SENSITIVE_ENVELOPE)) {
            const value = request.headers.get(envelope);
            if (value) {
                if (value.length > 16384) throw new Error("header_too_large");
                headers.set(name, value);
                authenticated = true;
            }
        }
    }
    if (request.method === "POST") headers.set("content-type", request.headers.get("content-type") || "application/octet-stream");
    if (authenticated) headers.set("cache-control", "no-store");
    return {headers, authenticated};
}

function cookiesFrom(headers) {
    if (typeof headers.getSetCookie === "function") return headers.getSetCookie();
    if (typeof headers.getAll === "function") return headers.getAll("Set-Cookie");
    const value = headers.get("set-cookie");
    return value === null ? [] : [value];
}

function proxyUrl(base, target, hop) {
    const url = new URL(POLICY.path, base);
    url.searchParams.set("url", target.href);
    url.searchParams.set("hop", String(hop));
    return url.href;
}

export default {
    async fetch(request) {
        const entry = new URL(request.url);
        if (entry.pathname !== POLICY.path) return error(404, "route_not_found");
        if (request.method === "OPTIONS") return new Response(null, {status: 204, headers: {
            "access-control-allow-origin": "*",
            "access-control-allow-methods": "GET, HEAD, POST, OPTIONS",
            "access-control-allow-headers": "Content-Type, Range, If-Range, If-None-Match, If-Modified-Since, X-Kazumi-User-Agent, X-Kazumi-Referer, X-Kazumi-Origin, X-Kazumi-Credential-Host, X-Kazumi-Upstream-Authorization, X-Kazumi-Upstream-Apikey, X-Kazumi-Upstream-Cookie",
            "access-control-max-age": "600",
        }});
        if (!["GET", "HEAD", "POST"].includes(request.method)) return error(405, "method_not_allowed");
        const hopText = entry.searchParams.get("hop") || "0";
        if (entry.searchParams.getAll("url").length !== 1 || !/^\d{1,2}$/.test(hopText)) return error(400, "request_invalid");
        const hop = Number(hopText);
        if (hop > POLICY.maxRedirects) return error(508, "redirect_limit");
        let target, prepared;
        try {
            target = allowedTarget(entry.searchParams.get("url"), entry.hostname);
            prepared = upstreamHeaders(request, target, entry.hostname);
        } catch (_) {
            return error(400, "target_or_headers_invalid");
        }
        let upstream;
        try {
            // Exactly one subrequest per invocation; bodies remain unbuffered.
            upstream = await fetch(target.href, {
                method: request.method,
                headers: prepared.headers,
                body: request.method === "POST" ? request.body : undefined,
                redirect: "manual",
                // Snippets rejects RequestInit.cache, even though Workers supports it.
                // Negative status TTL disables edge caching without forcing a 0-second cache.
                cf: prepared.authenticated || request.method === "POST"
                    ? {cacheEverything: false, cacheTtlByStatus: {"100-599": -1}}
                    : {cacheEverything: false},
            });
        } catch (exception) {
            // Expose a fixed diagnostic code, never an exception message or URL.
            const code = /cache/i.test(String(exception?.message || "")) ? "cache_option_rejected" : "upstream_unavailable";
            return error(502, code);
        }
        const headers = new Headers(upstream.headers);
        const cookies = cookiesFrom(upstream.headers);
        for (const name of ["set-cookie", "set-cookie2", "clear-site-data", "alt-svc", "report-to", "nel", "x-kazumi-set-cookie"]) headers.delete(name);
        headers.set("x-kazumi-upstream-url", target.href);
        headers.set("access-control-allow-origin", "*");
        headers.delete("access-control-allow-credentials");
        headers.set("access-control-expose-headers", EXPOSE_HEADERS);
        // Set-Cookie belongs to the target origin, never the shared proxy hostname.
        if (cookies.length) headers.set("x-kazumi-set-cookie", encodeURIComponent(JSON.stringify(cookies)));
        if (prepared.authenticated || cookies.length) headers.set("cache-control", "private, no-store");
        if (REDIRECTS.has(upstream.status) && headers.has("location")) {
            let next;
            try {
                next = allowedTarget(new URL(headers.get("location"), target).href, entry.hostname);
            } catch (_) {
                await upstream.body?.cancel();
                return error(502, "redirect_target_forbidden");
            }
            if (hop >= POLICY.maxRedirects) {
                await upstream.body?.cancel();
                return error(508, "redirect_limit");
            }
            headers.set("location", proxyUrl(entry.origin, next, hop + 1));
            headers.set("cache-control", "no-store");
        }
        // Raw HTML/JSON/images/HLS/segments retain bytes, encoding, validators and Range.
        // HLS references are rewritten in the adapter's local proxy, not in a 5 ms Snippet.
        return new Response(request.method === "HEAD" ? null : upstream.body, {
            status: upstream.status,
            statusText: upstream.statusText,
            headers,
        });
    },
};
