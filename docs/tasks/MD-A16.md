# MD-A16：远程锁定与 Android 移动宿主响应优化

## 状态

代码、Node/网页测试、Android JVM/HTTP 集成测试、Lint 和 debug 构建门禁已完成。真机验收进一步定位到 Android NanoHTTPD 对 SSE 自动启用 gzip；禁用 SSE gzip 和控制端 ACK 状态单调合并的补丁已通过完整自动化门禁。修复后的 APK 已通过 root 覆盖安装到 Android LAN Host `192.168.0.137:8765`，单投放页和同一物理设备双投放页闭环均已通过。慢客户端内存背压、队列上限和清理由单元测试覆盖；现场灌满 TCP 发送缓冲区的验证未执行。

## 基线与定位

- Node Host 同机 Playwright 控制页/投放页的可见锁定通常为 `10-23ms`，峰值 `66ms`。
- Android Host 的原始现场证据需要区分客户端：`curl -N /api/events` 的 `connected` 在建连约 `6ms` 可见，两个并行 curl control SSE 都能收到初始和完成态 lock ACK；浏览器两页中一次解锁到 display `body` locked 状态改变约 `1367ms`，控制端持续显示 `解锁确认 0/1，1 个未响应`，随后由 `1.5s` register fallback 更新。重建 Chrome EventSource 后 display 可见锁定约 `579ms`，服务端状态已 complete，control 仍没有收到 lock ACK。
- Playwright request 47 最终给出决定性差异：Chromium 的 Android SSE 响应带 `content-encoding: gzip`，curl 默认未协商 gzip。NanoHTTPD 默认对所有 `text/*` 响应启用 gzip，把无限 SSE body 包进 `GZIPOutputStream`；小型 `connected`、`lock-command`、`lock-ack` 和 `devices` 块没有及时形成可解压输出。`EventSource.readyState=1` 只表示连接建立，不能证明事件已分派。
- 修复不使用 padding 掩盖问题。Android Host 仅对 `text/event-stream` 覆盖 NanoHTTPD gzip 策略，其他文本/JSON 响应保留默认压缩；同时保留有界队列以隔离慢客户端。实际 Node HTTP 探针在未压缩路径记录到 command POST 后约 `61ms` 的 display command、`92ms` 的完成态 control ACK 和 `160ms` 的 devices event，进一步证明 Hub 入队、target 过滤和裸 chunk 输出本身可及时工作。

## 实现

### 共享网页投放端

- 抽出 `markerdeck-lock-flow.js` 纯执行编排：先同步应用 DOM/canvas/native 可见锁定状态，再启动 ACK；Fullscreen、wake lock、display state publish 等慢副作用不再阻塞成功 ACK。只有可见状态确实应用成功时才发送成功 ACK；失败仍发送失败 ACK。
- 本地用户锁定继续允许浏览器 Fullscreen，本地解锁继续释放 Fullscreen/wake lock 和显示 Android 紧急控制；远程命令不请求无用户手势的 Fullscreen。远程锁定同时写入 `forceLock`，保持注册 heartbeat 和状态拉取的持久 baseline。
- 保留 command 去重、global/target command ID、三击解锁、Electron bridge 和 Android bridge；控制端设备列表在 GET 进行中收到多个 devices 事件时记录 pending refresh，最终仍会完整刷新。
- 控制端对同一 command ID 的状态使用单调 ACK 进度：若完成态先通过 SSE 到达，随后返回的创建命令 pending 快照不能再把 UI 覆盖回 `0/1`；新 command ID 和真实后续进度仍正常显示。

### Node Host

- `/api/lock-command` 与 `/api/lock-broadcast` 继续先持久化并立即推送 `lock-command`/初始 `lock-ack`；静态资源 allowlist 增加共享执行编排脚本。
- 每个 lock ACK 的 `lock-ack` 仍立即推送；设备状态仍更新并持久化，但对应 `devices` fanout 使用 `60ms` 合并窗口，批量 ACK 不再触发刷新风暴，窗口结束后一定发送最终刷新。

### Android Host

- `MarkerDeckHostSseHub` 为每个客户端使用独立、有界的队列型 `InputStream`，容量为 `64` 个事件且最多 `256KiB`。生产者只做非阻塞入队；队列超限、关闭或不可可靠投递时清理并断开该客户端，让 EventSource 重连，不阻塞其他客户端。连接初始 `retry`/`connected` 与后续事件在同一事件锁下入队，保持顺序和 target session 匹配；不创建每客户端常驻线程。
- `AndroidHostServer.useGzipWhenAccepted` 对 `text/event-stream` 明确返回 false，阻止 NanoHTTPD 用 `GZIPOutputStream` 缓冲无限流；`application/json` 和其他原有可压缩响应继续调用父类策略。没有增加 SSE padding 或改变事件协议。
- Android lock delivery 保留 `lock-command`、初始 `lock-ack`、global/target 字段和设备持久状态；ACK 自身立即推送，设备刷新使用与 Node 等价的 `60ms` 去抖。`1.5s` register heartbeat 与 `15s` state pull 的兜底语义未改变。
- Android 静态资源映射同步加入 `markerdeck-lock-flow.js`；停止宿主时同时取消设备事件去抖器和 SSE 资源。

## 兼容与非目标

- 不改 WebSocket、发现协议、HTTP 路径、SSE 事件名称或既有字段；Node Host 与 Android Host 继续兼容旧控制端/投放端。新增 `global` 字段仍可被旧客户端忽略。
- 不改变 UI 视觉，不提高 heartbeat/fallback 频率，不删除离线或设备状态更新，不引入第三方运行时。
- 有界队列的保护策略是断开无法可靠投递的慢客户端，而不是静默丢弃锁定事件或无限堆积内存。

## 自动化验证

覆盖内容：

- Node `tests/lock-flow.test.js` 验证可见应用、快速 ACK、慢副作用并行、应用失败不虚报成功，以及完成态 SSE 不被同 command ID 的 pending POST 快照回退。
- Node `tests/server.test.js` 验证已连接 SSE 的 targeted `lock-command` 到达时序、每个 `lock-ack` 即时推送、批量 ACK 的 devices 刷新合并、最终设备状态和协议字段。
- Android `HostSseHubTest` 验证 connected/连续 payload 入队后立即可读、队列上限触发关闭和清理、慢客户端不阻塞其他客户端、去抖合并与立即 flush。
- Android `AndroidHostServerTest` 通过真实 NanoHTTPD/`HttpURLConnection` 并显式发送 `Accept-Encoding: gzip`，验证 SSE 响应不含 `Content-Encoding: gzip`，connected、连续 targeted lock events 和完成态 control lock ACK 可立即读取；普通 JSON 响应仍保留 gzip。

自动化门禁记录：

```text
npm run check
JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home \
ANDROID_HOME=/Users/andrower/Library/Android/sdk \
./gradlew --no-daemon :app:test :app:lintDebug :app:assembleDebug
git diff --check
```

gzip 根因修复后，Node `npm run check` 为 `50/50` 通过；Android `:app:test :app:lintDebug :app:assembleDebug` 全部通过。

## 真机与 LAN 验收

- Android Host `192.168.0.137:8765` 接收带 `Accept-Encoding: gzip` 的 `/api/events` 请求时，响应不含 `Content-Encoding`，`connected` 事件立即返回。
- 单投放端连续 `20` 次锁定/解锁为 `20/20` 成功：visible 延迟最小 `16ms`、最大 `137ms`、平均 `39ms`、P95 `74ms`；ACK 延迟最小 `32ms`、最大 `184ms`、平均 `63ms`、P95 `166ms`；最终控制端显示“解锁确认 1/1”。
- 同一物理设备打开两个投放页面并折叠为一个设备、选中两个页面后，连续 `10` 次批量锁定/解锁为 `10/10` 成功：visible 最大 `106ms`，ACK 最大 `130ms`；最终控制端显示“解锁确认 2/2”。
- 两个并行 curl control SSE 都收到同一命令的 initial 与 completed 两个 `lock-ack`，多客户端 ACK fanout 正常。

## 未验证范围

- 慢客户端的内存背压、队列上限、断开和清理由 Android JVM 单元测试覆盖；本次未在真实 LAN 环境中通过暂停读取来灌满 TCP 发送缓冲区，因此不把现场 socket 背压隔离写成已验证。

## 2026-09-05 手机宿主性能复验

基于 `d3a7602` 完成单手机宿主与真实浏览器控制闭环、20 个模拟 SSE 接收会话、Wi-Fi/USB 对照和短时后台检查。正式测试事件全部收到，但持续高频更新出现约半秒的 P95 延迟，宿主控制页另有状态栏遮挡待修。方法、数值和未测边界见 [手机宿主性能记录](../verification/android-host-performance-2026-09-05.md)。
