# MD-A14：局域网 mDNS 自动发现与启动连接询问

## 状态

代码、Node 纯逻辑/组合扫描测试、Android JVM 测试、Lint、debug 构建、项目结构检查和 macOS 便携包结构检查已完成。真机以及不同路由器的 mDNS 现场验证仍待完成，不能由 CI 或本地环回测试替代。

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

## 未完成现场验证

仍需在至少一台真实 Android 设备和不同路由器/交换网络环境验证：NsdManager 发布与解析、Android/Node 跨设备互发现、多宿主提示、网络切换、组播隔离、路由器对 mDNS 的过滤行为、前后台及系统权限弹窗时序。当前未伪造这些结果。
