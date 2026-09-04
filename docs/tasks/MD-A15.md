# MD-A15：局域网发现提速与启动询问稳定性

## 状态

代码、Node 测试、Android JVM 测试、Lint、debug 构建和 diff 检查已完成。USB 真机 `f2d6d6dc` 上的单宿主冷启动、用户刷新和熄屏期间 pending prompt 恢复已通过；多宿主、不同网络/路由器和强制首包丢失仍待验证。

## 基线与根因

- Node `/api/hosts` 基线约 `1.805s`；Android 手动发现约 `1.90-1.95s`，连接询问约 `2.10s`，冷启动列表约 `3.0s`。
- Android 曾观察到一次发现成功但未弹出启动询问。固定等待 mDNS/UDP 全部超时后才验真，使首台可用宿主被最慢发现源拖住；网络回调还可能覆盖 `STARTUP` trigger。
- Activity 暂停、系统引导或扫码/确认对话框占用 UI 时，旧逻辑会清掉尚未展示的 pending prompt，恢复后没有稳定补偿入口。
- Node UDP-only 单宿主五次实测为 `254/227/228/1800/225ms`。其中一次完整超时表明单个 UDP 请求会偶发丢失；旧实现没有有限重发，且某一发送目标的回调错误会过早结束整个扫描。
- Node/Android 的旧聚合路径缺少统一的取消和 generation 边界，晚到的 mDNS、UDP 或 HTTP 验真结果存在污染新扫描、重复验真或延迟资源释放的风险。

## 实现

### Node

- mDNS 与 UDP 候选到达后立即进入最多四路并发的 nonce `/api/discovery` 验真。首个候选通过后启动 `220ms` 多宿主收集宽限，不再等待固定发现超时；宽限内完成验真的其他宿主一并返回。
- 候选在进入队列前统一重验安全文本、协议、实例 ID、端口、私有/回环 IPv4 和 self exclusion，并按实例、观测地址和端口去重；HTTP 最终地址仍只由观测源地址构造。
- 扫描完成、宽限到期或硬截止时统一 abort mDNS、UDP 和未完成 HTTP 验真，移除监听器并关闭 socket/Bonjour 资源。已结束 generation 的晚到回调不能写入结果。
- UDP 初次发送后约 `300ms` 使用同一 nonce 最多重发一次，不延长总扫描时限；abort 后清除重发计时器。单个广播目标的 `socket.send` 回调错误只忽略该目标，不再结束其他目标的发送和接收窗口。

### Android

- `DiscoveryScanner` 改为 mDNS/UDP 候选事件流和最多四路 HTTP 验真队列。首个验真宿主立即通知设置页，并保留 `240ms` 多宿主宽限；宽限到期可取消仍在排队或进行中的验真，不被慢源拖回完整超时。
- 扫描、延迟网络刷新和 mDNS 回调使用独立 generation/token。`start()` 先建立 `STARTUP` 扫描再注册网络回调；网络变化只合并 pending trigger，不能覆盖启动或用户刷新，也不能让旧 generation 发布结果或停止新扫描。
- `stop()` 和扫描 `finally` 取消延迟任务、UDP worker、HTTP 验真与 mDNS，关闭事件通道并释放 Wi-Fi multicast lock。候选按实例、观测地址和端口去重，最终宿主按 identity 合并。
- UDP 使用单调时钟控制总时限，在初次发送约 `300ms` 后以同一 nonce 最多重发一次；stop、线程中断、扫描截止或 generation 取消后不再发送。单目标发送异常不阻止其他目标和接收窗口。
- `STARTUP` prompt 作为带 trigger 的 pending request 保存。Activity 恢复、设置页重新可见、系统引导结束或 UI 暂不可用后，通过 generation 化主线程回调补提示；只有对话框实际显示后才消费 pending 并记入本 Activity 会话去重集合。相同宿主启动自动提示一次，用户刷新仍可再次询问。

## 安全与兼容性

- 不改变 MD-A14 的 mDNS/UDP/私有 IPv4/nonce/self exclusion 边界，不做端口扫描、公网发现、后台持续发现或未经验真的导航。
- UDP 重发复用同一次扫描 nonce，只有一次且受原总时限和 abort 控制；它提高偶发丢包容忍度，不引入持续广播。
- mDNS/UDP 重复候选只验真一次。名称、TXT、广播 URL 和 mDNS 地址本身仍不可信，只有观测地址上的 nonce HTTP 响应可以进入结果。

## 自动化验证

已执行：

```text
npm run check
JAVA_HOME=... ANDROID_HOME=... ANDROID_SDK_ROOT=... ./gradlew --no-daemon :app:test :app:lintDebug :app:assembleDebug
git diff --check
```

实际结果：Node `npm run check` 共 `44/44` 通过；Android `:app:test :app:lintDebug :app:assembleDebug` 成功。Node 测试覆盖首个宿主提前完成、宽限内第二台宿主、宽限到期取消未完成验真/发现资源、mDNS/UDP 去重、单目标发送错误隔离、同 nonce 单次重发和 abort 后不再发送。Android 纯逻辑测试覆盖 pending 验真在宽限前不退出、宽限到期退出、`STARTUP` trigger 不被网络回调覆盖、UI 暂不可用后保留 prompt、同会话启动提示去重、用户刷新可再次询问，以及重发只执行一次且 stop/截止后不执行。

## 实际单宿主验证

- 直接调用 Node scanner 的 UDP-only 路径连续 `10` 次均为 `223-250ms`。
- 完整 Node `/api/hosts` 路径在服务器刚启动后的首轮为 `1839ms`；此冷启动异常值明确保留。随后连续 `20` 次为 `224-277ms`，平均 `228ms`，`0` 次超过 `500ms`。
- debug APK 已成功安装到 USB 真机 `f2d6d6dc`。冷启动 `5/5` 均在启动后 `1.2s` 检查点出现“发现局域网宿主”，Activity `TotalTime` 为 `569-596ms`。
- 用户主动刷新后，`0.8s` 内再次弹出宿主询问，符合刷新可绕过同 Activity 会话自动提示去重的约束。
- 熄屏期间扫描完成后，手动进行无密码解锁并返回 Activity，保留的 pending prompt 随即补弹；该结果不涉及或声称绕过系统认证。

## 剩余真机验证

- 使用至少两台同网宿主确认首台验真后立即可见，第二台在 `220/240ms` 宽限内出现时同批展示，宽限外结果不引发重复提示抖动。
- 在不同网络、路由器、组播隔离和更多 OEM/Android 版本复测 mDNS/UDP 回退、网络切换、回调注销、多播锁释放及手动地址兜底。
- 通过受控丢包强制丢弃首个 UDP 请求，确认约 `300ms` 的同 nonce 单次重发实际触发并恢复结果，且 abort 后不再发送。服务器冷启动首轮 `1839ms` 仍作为未掩盖的现场异常值保留。

## MD-A14 现场状态

MD-A14 已在 USB Android 真机完成 Node/Android 双向 mDNS、启动询问、命名连接、远程状态同步和 Android 宿主后台发布验证。尚未完成的是不同路由器、网络切换、组播隔离及多宿主环境，不把这些场景记为已通过。
