# MD-A16：远程锁定与 Android 移动宿主响应优化

## 状态

代码、Node/网页测试、Android JVM/HTTP 集成测试、Node 检查和 Android `:app:test` 已完成；`lintDebug`、`assembleDebug` 与 `git diff --check` 在本次收尾门禁中执行。USB Android、Android LAN Host `192.168.0.137:8765` 及多投放端/慢客户端现场复测仍待主代理验收，本记录不将其写成已通过。

## 基线与定位

- Node Host 同机 Playwright 控制页/投放页的可见锁定通常为 `10-23ms`，峰值 `66ms`。
- Android Host 的原始现场证据需要区分两个阶段：`curl -N /api/events` 的 `connected` 在建连约 `6ms` 可见，并非必须等待心跳；但建连完成后等待 `1s` 再 POST，`lock-command` 仍曾在约下一秒才出现在 trace。浏览器两页中一次解锁到 display `body` locked 状态改变约 `1367ms`，控制端先显示 `解锁确认 0/1，1 个未响应`，随后由 `1.5s` register fallback 更新。两页 `EventSource.readyState=1` 不能证明事件已经及时被消费。
- 因此不能把问题归结为 SSE header 或 heartbeat 缓冲。NanoHTTPD `ChunkedOutputStream` 不对每个 chunk 单独 flush，但底层写入是 socket stream；本任务将事件到达拆成建连、连续事件、目标 session 过滤、可见应用和 ACK 五段计时。Android 的 `PipedInputStream/PipedOutputStream` 生产者路径改为明确的有界 blocking `InputStream` 队列，避免 `available/read` 语义或慢网络写入把请求线程和其他客户端串联；LAN 现场根因和最终收益仍需实机复测。

## 实现

### 共享网页投放端

- 抽出 `markerdeck-lock-flow.js` 纯执行编排：先同步应用 DOM/canvas/native 可见锁定状态，再启动 ACK；Fullscreen、wake lock、display state publish 等慢副作用不再阻塞成功 ACK。只有可见状态确实应用成功时才发送成功 ACK；失败仍发送失败 ACK。
- 本地用户锁定继续允许浏览器 Fullscreen，本地解锁继续释放 Fullscreen/wake lock 和显示 Android 紧急控制；远程命令不请求无用户手势的 Fullscreen。远程锁定同时写入 `forceLock`，保持注册 heartbeat 和状态拉取的持久 baseline。
- 保留 command 去重、global/target command ID、三击解锁、Electron bridge 和 Android bridge；控制端设备列表在 GET 进行中收到多个 devices 事件时记录 pending refresh，最终仍会完整刷新。

### Node Host

- `/api/lock-command` 与 `/api/lock-broadcast` 继续先持久化并立即推送 `lock-command`/初始 `lock-ack`；静态资源 allowlist 增加共享执行编排脚本。
- 每个 lock ACK 的 `lock-ack` 仍立即推送；设备状态仍更新并持久化，但对应 `devices` fanout 使用 `60ms` 合并窗口，批量 ACK 不再触发刷新风暴，窗口结束后一定发送最终刷新。

### Android Host

- `MarkerDeckHostSseHub` 为每个客户端使用独立、有界的队列型 `InputStream`，容量为 `64` 个事件且最多 `256KiB`。生产者只做非阻塞入队；队列超限、关闭或不可可靠投递时清理并断开该客户端，让 EventSource 重连，不阻塞其他客户端。连接初始 `retry`/`connected` 与后续事件在同一事件锁下入队，保持顺序和 target session 匹配；不创建每客户端常驻线程。
- Android lock delivery 保留 `lock-command`、初始 `lock-ack`、global/target 字段和设备持久状态；ACK 自身立即推送，设备刷新使用与 Node 等价的 `60ms` 去抖。`1.5s` register heartbeat 与 `15s` state pull 的兜底语义未改变。
- Android 静态资源映射同步加入 `markerdeck-lock-flow.js`；停止宿主时同时取消设备事件去抖器和 SSE 资源。

## 兼容与非目标

- 不改 WebSocket、发现协议、HTTP 路径、SSE 事件名称或既有字段；Node Host 与 Android Host 继续兼容旧控制端/投放端。新增 `global` 字段仍可被旧客户端忽略。
- 不改变 UI 视觉，不提高 heartbeat/fallback 频率，不删除离线或设备状态更新，不引入第三方运行时。
- 有界队列的保护策略是断开无法可靠投递的慢客户端，而不是静默丢弃锁定事件或无限堆积内存。

## 自动化验证

覆盖内容：

- Node `tests/lock-flow.test.js` 验证可见应用、快速 ACK、慢副作用并行和应用失败不虚报成功。
- Node `tests/server.test.js` 验证已连接 SSE 的 targeted `lock-command` 到达时序、每个 `lock-ack` 即时推送、批量 ACK 的 devices 刷新合并、最终设备状态和协议字段。
- Android `HostSseHubTest` 验证 connected/连续 payload 入队后立即可读、队列上限触发关闭和清理、慢客户端不阻塞其他客户端、去抖合并与立即 flush。
- Android `AndroidHostServerTest` 通过真实 NanoHTTPD/`HttpURLConnection` 验证连接后的 connected、连续 targeted lock events 和 session 过滤路径。

本次收尾执行：

```text
npm run check
JAVA_HOME=/Applications/Android Studio.app/Contents/jbr/Contents/Home \
ANDROID_HOME=/Users/andrower/Library/Android/sdk \
./gradlew --no-daemon :app:test :app:lintDebug :app:assembleDebug
git diff --check
```

Node 主代理已独立报告 `48/48` 通过，Android `:app:test` 已通过；`lintDebug`、`assembleDebug` 和 diff 检查以本次收尾命令结果为准。

## 剩余现场验证

- 在 Android Host `192.168.0.137:8765` 重新执行严格单 shell 的 `curl -N` 建连/连续事件计时，确认 connected 约 `6ms` 的基线和建连后 lock command 不再出现约 `1s` 延迟。
- USB 真机完成控制页/投放页锁定与解锁闭环，检查可见状态、ACK 状态和 1.5s fallback；当前未在本任务自动化门禁中声称已通过。
- 至少两台投放端、一个慢/暂停读取的 SSE 客户端和多个并发 ACK 场景下确认目标 session 不串线、慢客户端被重连隔离、控制端最终只执行合并后的设备列表 GET。
