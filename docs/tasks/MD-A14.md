# MD-A14：局域网 mDNS 自动发现与启动连接询问

## 状态

代码、Node 纯逻辑/组合扫描测试、Android JVM 测试、Lint、debug 构建、项目结构检查和 macOS 便携包结构检查已完成。USB Android 真机上的 Node/Android 双向 mDNS、自动发现询问、命名连接、远程状态同步和 Android 宿主后台发布已完成现场验证；不同路由器、网络切换和组播隔离场景仍待验证。

## 目标与边界

- 在保留 UDP `8766` 与手动地址输入的基础上，增加 DNS-SD 服务 `_markerdeck._tcp.local`。
- Node 宿主和 Android LAN 宿主发布相同的 TXT 元数据：`service=markerdeck`、`protocolVersion=1`、`instanceId`，并携带安全的显示名称。
- Node `/api/hosts` 和 Android 设置页同时执行 mDNS 与 UDP 扫描；候选只用于定位，最终必须通过观测地址上的 nonce `/api/discovery` HTTP 验真。
- Android 默认打开时进行一次短时、非阻塞扫描。发现一个或多个宿主后，先询问是否连接；多个宿主显示可选列表。拒绝、无结果或 UI 不适合弹窗时回到现有模式选择页。
- 启动会话内同一宿主只自动提示一次；刷新或用户主动扫描允许再次选择。二维码连接仍使用同一宿主确认、设备命名和投放入口。
- 不改变投放协议，不把 Node.js、FFmpeg 或桌面运行时放入 Android APK。

## 实现

### Node

- `src/markerdeck-mdns.js` 独立封装 bonjour-service 的发布、浏览、TXT 解码和候选标准化。Node API 使用 `_markerdeck._tcp.local` 作为完整服务类型，bonjour-service 发布时使用其 API 所需的 `type: "markerdeck"` 与 `protocol: "tcp"`。
- HTTP 监听成功后才发布 mDNS。发布失败仅记录并保留 HTTP、UDP 与手动地址能力；`/api/info` 的 `mdnsDiscovery`、`udpDiscovery` 按实际可用状态上报。
- `src/markerdeck-host-discovery.js` 组合 mDNS/UDP 候选，按 `instanceId` 和观测地址去重，限制安全文本、版本、实例 ID、端口、私有/回环 IPv4，排除自身，并用观测地址构造 origin。候选不会因广播名称、URL 或 TXT 直接获得信任。

### Android

- `MarkerDeckMdnsPublisher.kt` 使用 `NsdManager` 发布 `_markerdeck._tcp`（Android API 不要求 `.local` 后缀），可靠处理注册回调、停止、注销和过期回调。
- `MarkerDeckMdnsScanner.kt` 独立管理浏览/解析，最多三个并发 resolve，有界超时和 generation 防止旧回调污染新扫描。`DiscoveryScanner.kt` 同时运行 mDNS 与 UDP，收集后复用纯逻辑校验和 nonce HTTP 验真。
- `HostLifecycleController` 为一次宿主会话生成一个 `instanceId`，把它同时传给 Android HTTP host、UDP responder 和 mDNS publisher；停止宿主时按生命周期注销 mDNS、停止 UDP 并释放多播锁。
- `DiscoveryAutoConnect.kt` 负责纯逻辑的启动/刷新提示决策。`MainActivity` 只在设置页、生命周期可交互且系统权限引导/相机扫码弹窗不冲突时显示中文连接询问，并复用 QR 宿主确认、设备命名和 `display` 投放路径。

## 安全与兼容性断言

- mDNS/UDP 的名称、URL、TXT 和服务地址均不可信；最终信任边界是候选观测 IPv4 上的 nonce `/api/discovery` 响应。
- 只接受协议版本 `1`、安全文本、合法实例 ID、`1..65535` 端口和私有/回环 IPv4；mDNS 出错、不可用或超时自动回退 UDP/手动输入。
- `/api/info` 保留 `udpDiscovery`，新增准确的 `mdnsDiscovery`；旧网页和现有 QR 地址格式继续兼容。
- macOS/Windows 便携服务包和 Electron `extraResources` 都携带生产依赖及完整依赖树，CI 在打包前执行 `npm ci`，结构检查确认 bonjour-service 及其依赖存在。

## 验收与验证

已执行或纳入 CI 的门禁：

```text
npm run check
JAVA_HOME=... ANDROID_HOME=... ./gradlew --no-daemon :app:test :app:lintDebug :app:assembleDebug
git diff --check
```

另执行了 macOS 便携 ZIP 的内容检查，确认 Node runtime、服务端源码和 `app/node_modules/bonjour-service` 及其运行时依赖同时存在。测试不依赖真实 mDNS 网络，使用假的浏览器/HTTP 响应和 Android 纯逻辑模型覆盖去重、回退、生命周期、过期回调以及启动提示决策。

## 现场验证

已在 USB 连接的 Android 16 真机（22127RK46C）与同一局域网内的 macOS Node 宿主完成以下验证：

- Android 启动扫描发现 Node 宿主并显示中文连接询问，地址为实际局域网地址而非 `localhost`。
- 确认连接后显示设备名称输入框；以 `USB-Test-Phone` 连接后，Node `/api/devices` 返回在线投放端及正确的 CSS 尺寸、DPR、`deviceId` 和 `sessionId`。
- Node 通过 SSE 远程切换蓝底绿十字后，Android 投放页面立即更新。
- Android 启动内置宿主后，Node 的纯 mDNS 浏览器从 `_markerdeck._tcp.local` 解析到 Android 宿主 `192.168.0.137:8765`，TXT 中的服务、协议版本和 `instanceId` 正确。
- Android Activity 退到后台后，内置宿主仍能被 Node 发现；停止服务后 HTTP 端口关闭。

仍需在不同路由器/交换网络环境验证：多个宿主同时出现时的选择弹窗、Wi-Fi 切换、组播隔离、路由器过滤 mDNS，以及更多 OEM 对 NSD 和系统权限弹窗时序的影响。
