# Kazumi 旧接口适配

`csp_Kazumi` 使用旧版 `Spider.client()` 和 Java Spider 方法，同一份 JAR 可供旧版及增加新 SDK 的 FongMi TV 使用。没有改动已有 Girigiri、Sorani 源。

这里固定保存上游 `Predidit/KazumiRules@fce5e15a2e6b57500912d41b3f1c65bb3eb7d392` 的 16 个有效规则，保留 MIT 许可。上游另外 70 个规则已标记 deprecated，不默认启用。`manifest.json` 记录来源和复用关系；Giri/Sorani 使用现有源，aafun 与 moonci 同站，保留较新的 moonci。三星实测还确认 xfdmneo 的旧 HTML 入口跳转到官方门户，旧版域名也转向 Next，因此不默认启用该旧入口；保留其原始快照和独立的 xfdmnext API 规则。`sites.json` 最终提供 12 个新增独立源。

`sites.json` 是供合并的站点片段。发布时必须为这些条目指定包含 Kazumi 的共享 JAR；不要用它替换原配置的全局爬虫。

## 配置

```json
{
  "key": "Kazumi_moonci",
  "name": "Kazumi · moonci",
  "type": 3,
  "api": "csp_Kazumi",
  "searchable": 1,
  "quickSearch": 1,
  "filterable": 0,
  "ext": {
    "rule": { "...": "完整的上游规则对象" },
    "proxy": "https://proxy.ciallo0d000721.cc.cd/kazumi",
    "proxyMode": "fallback",
    "mediaProxy": true
  }
}
```

默认内联 `rule`，打开源不会额外下载规则。也允许传固定版本的 HTTPS 规则 JSON URL。`proxyMode` 支持 `fallback`、`always`、`off`；`mediaProxy` 默认开启。

规则本身只提供搜索、章节和播放页，没有首页、分类、筛选或通用翻页协议。请用全局搜索；第二页为空并明确返回 `page=2/pagecount=1`。不伪造推荐列表或分类。

## 适配范围

- XPath 列表、文本节点、相对上下文、多线路，以及 `self::` / `following-sibling::` / union。
- API 8 的 GET/POST、JSON/form、规则标头、受限 JSONPath、嵌套和分隔字符串线路、响应变量及剧集页模板。
- 原站 Cookie 隔离、已有 WebView Cookie、可取消网络、短期有界缓存。
- 直链、MacCMS player JSON、Artplayer/DPlayer 配置、video/source 和 iframe 直链参数静态解析；其余返回旧接口 `parse=1` 给 App 自带 WebView。
- 已取得的媒体可经旧版已存在的本地代理接口播放。HLS 的主清单、子清单、分片、KEY/MAP/音轨 URI 都改写；分片按原 Range 流式读取，不整片载入内存。本地媒体链接由当前源会话签名，不能任意替换目标网址。直接连接失败时尝试同一个 CF 入口；媒体 CDN 必须加入该入口允许清单。只有原线路失败且 CF 成功时才记住该媒体 origin 60 秒，后续分片直接走 CF；CF 失败即清除记忆并尝试原线路一次。路由记忆最多 32 项，换源或销毁时清空。

直连元数据的连接/读取/整次上限为 2.5/6/10 秒，CF 代理为 5/10/15 秒。流式媒体保留 30 秒读取等待，不使用整段下载总时限。取消后不再自动回退。

## 明确的边界

验证码、403、无可播放章节及无法判定的空 HTML 会报错。CF 反代不会自动完成验证码。规则提供的 CAPTCHA 标记用于检测；交互验证仍需原站浏览器。规则中的 `adBlocker` 不会导致适配器自动删除 HLS discontinuity 分组，避免误删正片。

通用 CF 入口只传输原始 HTTP 内容，不执行网页 JS，也不对整页 JS、CSS、资源、Cookie 做跨域重写。未能静态解析时，App WebView 仍打开原站播放页；原站不可达、复杂 blob 或站点定制脚本可能需要进一步适配。`parse=1` 只代表交给嗅探，不能视为已经播放成功。

## 实际验证

主机使用真实规则和真实搜索响应验证了 XPath/API 8；30 项无网协议检查通过，包括受限 JSONPath、模板类型与编码、XPath 上下文和线路格式。13 个新源均执行了真实搜索、详情以及可用的前两集解析，另把整条链强制经已部署 CF 入口复查。静态解析成功与需要 App WebView 的结果分别记录，没有把 HTTP 200 当成全部功能通过。

本地真实 HTTP 服务的 31 项路由记忆检查通过，包括首次回退、后续跳过原线、到期、CF 失败回原线、全失败不记忆、取消、always/off 和容量限制。另 12 项媒体检查通过：HLS KEY/MAP/音轨/分片 URI、完整清单、Range 206 字节和取消中的流。线上通用 CF 入口的 206/128 字节检查，以及 MXdm 真实 HLS 获取和本地 URI 改写也通过；这仍不等于 Android 已完成解码。

当前 mgnacg、mutefun 需要验证码，dalvdm 在直连及 CF 出口仍返回 403。baimao 部分搜索标题明确没有播放资源。其余站点的 Android WebView/实际解码结果以设备测试记录为准；主机接口检查不能证明设备播放成功。

## Android 实测补充（2026-10-06）

修复了 Android Harmony DOM 与 jsoup W3CDom 的 `Document.setUserData` 空指针：现在直接从 jsoup 节点构造保留父子与兄弟关系的 DOM，不解析外部 XML 实体。Windows `spiderJar` 构建与 JAR 检查通过。

最初 13 个候选在三星 Android 13 上通过旧 JarLoader ABI 和实际 BaseLoader 装载，9 个通过搜索、详情及前两集解析；dalvdm、mgnacg、mutefun 遇到验证码，xfdmneo 跳到旧站迁移门户，已从默认列表剔除。静态 HLS 源通过 App 实际本地代理、清单重写与 Range 206 验证。

MXdm 的 Exo 双集真实硬解、首帧与画面、双向拖动、切集、音效开关、倍速和音量检查通过。TV App 的 HLS 预读修复后，MXdm 的 MPV 原严格双集播放、双向定位、切集、音效与倍速测试也全部通过。DM84 已完成 App 原生 WebView 嗅探后的 Exo 双集真实播放与控制检查；AGE、baimao、ezdmw、xfdmnext 当前嗅探超时，不能声称已经播放。动态页面的可用性依赖站点脚本和 App WebView，并不是共用 HTTP 反代能解决的全部问题。

## xfdmnext 播放补充（2026-10-06）

当前站点网页实际调用 `issue-web-playback` 取得短期 MP4 候选。适配器只对 `next.xifanacg.com` 及其 `api.xifanacg.com` 章节接口启用同一流程：从真实章节响应选择当前线路和剧集，复用现有 HTTP、CF 回退和取消链，不依赖网页嗅探。章节元数据使用已有有界缓存保存最多 60 秒，播放票据不缓存；校验影片、剧集、线路、有效期及媒体类型，错误不会伪装成空列表或播放成功。

返回仍为旧接口 `parse=0`、`url`、`header` 和 `format=video/mp4`，复用已有本地媒体代理，旧 App 无需新接口。媒体请求不携带章节 API 的认证标头。此站专用实现不改变其他规则的 WebView 行为。

主机已通过真实前两集的直连/自动回退模式和强制 CF API 模式：四次播放器输出均为 MP4，实际媒体 Range 206、1024 字节与 `ftyp` 格式签名符合预期。新增 19 项本地检查覆盖票据不缓存、过期、剧集/线路不匹配、无候选、HLS 类型冲突、带凭据 URL、取消和凭据域名绑定；原 30 项协议、31 项路由及 12 项媒体检查保持通过。新流程的 Android 解码结果仍需设备回归，不能以这些主机检查代替。
