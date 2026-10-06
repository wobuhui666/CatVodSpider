# 共用 Cloudflare Snippet 反代

当前入口为 `https://proxy.ciallo0d000721.cc.cd/kazumi?url=<encodeURIComponent(原站URL)>`。所有已允许站点共用一个 Snippet；新增站点或真实媒体 CDN 时更新 `hosts.json`，重新生成并更新同一个 `kazumi_proxy`，无需新增域名或每站函数。

## 生成

```sh
python3 cloudflare/kazumi-proxy/build.py
```

生成 `dist/kazumi-proxy.js` 及包含哈希的 metadata。构建器限制包小于 32 KiB；模板和清单可独立使用，不依赖本机私有文件。这里只保存可公开源码，不保存 Cloudflare 凭据、现有 Zone 规则备份或私有 WEX 配置。

## 请求契约

GET、HEAD、POST 使用单次上游请求，HTML、JSON、图片和媒体原样流式转发；保留 Range / If-Range / 206。重定向 Location 改写回同一入口，客户端最多跟随 5 次，避免在一次 Snippet 中连续子请求。默认仅允许清单中的精确 HTTPS 域名，不接受任意公网/私网 URL、IP、用户信息或非默认端口。

调用方普通 User-Agent 应使用浏览器值以适配现有 Zone 的 Browser Integrity Check。`X-Kazumi-User-Agent` 指定上游 UA，`X-Kazumi-Referer` / `X-Kazumi-Origin` 指定该源的来源信息。普通 Authorization、Cookie、Proxy-Authorization 不会转发；源规则确需认证时，只能由适配器显式发送 `X-Kazumi-Credential-Host` 和 `X-Kazumi-Upstream-Authorization` / `X-Kazumi-Upstream-Apikey` / `X-Kazumi-Upstream-Cookie`，目标 hostname 必须匹配。跨站跳转不携带旧站的认证信息。

响应 `X-Kazumi-Upstream-URL` 提供原站 base；`X-Kazumi-Set-Cookie` 为 URL 编码的 cookie JSON 数组，由适配器按原站 cookie jar 保存，不能设到共用反代域名。认证、POST 与带 Cookie 的响应禁止缓存；当前 Snippets 不支持 Workers 的 `cache:no-store` 请求选项，因此源码使用受支持的负状态 TTL 和 no-store headers，已做线上认证 POST 验证。

HLS 清单中的相对 variant、segment、KEY、MAP 和 AUDIO 地址由 Java 适配器的本地媒体代理改写。此入口不执行页面 JavaScript、不解决验证码，也不是任意动态 WebView 网站的透明代理。已有同 Zone Snippet 镜像不能作为套娃目标，应转发其原始上游站点。

## 部署与更新

Snippets 要求已支持该功能的 Cloudflare Zone；当前 Business 每次最多 3 次子请求、5 ms CPU、2 MB 内存，本实现只发一次 fetch，不缓冲视频正文。

初次部署需要一个独立橙云 hostname 和限定该 hostname + `/kazumi` 的规则。**更新 `snippet_rules` 会替换整张列表，必须先备份并保留所有既有规则及顺序。** 当前部署保留了原 47 条规则，仅追加共用入口；后续仅更新 `kazumi_proxy` 内容与清单，通常不需要再写 DNS 或规则表。

API：`PUT /zones/{zone_id}/snippets/kazumi_proxy`，metadata 的 `main_module` 为 `kazumi-proxy.js`，上传构建后的同名模块。认证由部署环境提供，勿写进源码或配置 URL。

线上已验证公开 JSON、认证 POST、HTML、跨域重定向、Range 206 与直连内容哈希一致。源解析与设备解码另行验证，不能用 HTTP 200 代替播放成功。
