# MarkerDeck 改进路线

本文记录控制与投放组件的后续改进方向、实施顺序和验收标准。

## 第一优先级：通信可靠性

当前状态（2026-08-28）：已完成基础实现和自动测试。实时通道采用 SSE，客户端写操作继续使用 HTTP。

### 1. 物理设备与接收页面双层身份

状态：已完成。

- `deviceId` 表示一台手机、平板或电脑，在同一浏览器中长期保存。
- `sessionId` 表示一个独立投放页面，每个标签页单独生成。
- 同一设备打开多个投放页面时，控制端应分别显示和控制每个接收页面。
- 控制端默认按物理设备折叠，同一设备只显示一张父卡片；展开后显示独立接收页面。
- 父卡片可批量选择该设备全部页面，子卡片仍支持独立选择，并显示部分选择状态。
- 设备名称属于物理设备；接收页面使用序号或会话尾号辅助区分。
- 旧客户端只发送 `deviceId` 时，服务端继续兼容，将其视为单一会话。

验收标准：同一浏览器同时打开三个投放页面，控制端出现三个独立接收端，锁定和画面修改互不覆盖。

### 2. 实时事件通道

状态：已完成。

- 使用浏览器原生 SSE 建立服务端到客户端的持续事件流。
- 状态修改、设备上下线、锁定命令和确认结果立即推送。
- SSE 断开后由浏览器自动重连，并通过心跳检测连接可用性。
- 保留低频注册心跳用于设备在线判定，不再每 250ms 拉取状态。
- 客户端写操作仍通过 HTTP API，避免增加外部运行时依赖。

验收标准：控制端修改画面后投放端即时更新；后台标签页恢复后能继续接收；网络请求中不再持续出现高频 `/api/state` 轮询。

### 3. 命令确认 ACK

状态：已完成。

- 每条锁定或解锁命令拥有唯一 `commandId`。
- 每个接收页面执行后单独上报 ACK，不由其他页面代替。
- 控制端显示目标数、已确认数、未响应数和执行结果。
- ACK 超时只标记未响应，不重复切换锁定状态。

验收标准：批量锁定四个接收端时，控制端能显示类似“4/4 已锁定”；关闭其中一个页面后再次操作应显示“3/4 已确认，1 个未响应”。

## 第二优先级：现场可观察性与安全

- 二维码加入随机拍摄会话码，阻止同一局域网内的误接入。
- 设备诊断面板显示系统、浏览器、分辨率、DPR、全屏、Wake Lock、网络延迟和最后通信时间。
- 保存并恢复整场拍摄配置，包括名称、分组、预设和设备选择。
- 明确区分网页锁定、浏览器全屏与原生投放窗口状态。

## Android-first：移动端普通投放与锁屏恢复

状态：进行中。Android 是当前唯一实施目标，只交付普通投放和锁屏恢复；MD-A04 专用设备功能已取消。iOS/iPadOS 不纳入当前实现、验收或发布物，仅保留未来评估备注。

本规划面向 Android 投放端程序，目标是在用户已经进入实际投放页后尽快恢复投放画面，但不把普通应用能力误认为可以绕过系统安全策略。浏览器/PWA 不作为满足强前台要求的实现。

### 技术方案

- **技术栈**：使用 Kotlin、Android SDK 和 Gradle，沿用 Android 官方生命周期和窗口 API；在没有现成 Android/Gradle 工程的前提下，从 `android/` 目录建立独立工程。
- **应用形态**：单 Activity 的原生薄壳承载 WebView，加载用户选择的 MarkerDeck 服务上的 `markerdeck-screen.html?mode=display`，保留现有 HTML/JS、画布渲染和投放交互，不在 Android 端复制控制端或页面业务。
- **协议兼容**：继续使用现有 `deviceId`、`sessionId`、SSE 事件流和 HTTP 写入/断线恢复协议；MD-A08 只增加可选的版本化宿主发现 UDP/HTTP 握手端点，旧网页投放端与 v1.3.0 服务端接口保持兼容。
- **本地配置**：使用 DataStore 保存服务端地址和设备名；升级前遗留的模式字段被忽略，并在下一次保存配置时删除，所有新连接都进入普通投放。`deviceId` 的长期身份和 `sessionId` 的页面身份仍遵循现有网页机制，除非后续兼容性验证证明必须迁移。
- **网络边界**：设置页可在当前 Wi-Fi/以太网执行固定端口、短超时、nonce 关联的 MarkerDeck 宿主发现；只接受版本化响应并通过同源 HTTP 握手校验，未选择地址时不加载投放 WebView，不做盲目端口扫描、公网发现、外部回退或遥测。
- **原生职责**：负责沉浸式全屏、`KEEP_SCREEN_ON`、Activity 生命周期恢复和实际状态诊断；WebView 继续负责投放画面、注册、SSE、HTTP 同步和现有网页锁定逻辑。
- **Foreground Service 边界**：不声称 Foreground Service 能强制把 Activity 拉回前台。只有在验证确实需要维持原生心跳或后台任务时才引入，并明确其不能绕过锁屏、不能替代生命周期恢复。
- **首版边界**：不重写控制端，不把 Node.js 或 FFmpeg 打进 Android APK；服务端仍运行在用户明确选择或设置页发现的局域网 MarkerDeck 主机上。

### 1. 普通 Android 模式

- 使用原生 Activity 作为现有投放页面的承载壳，默认加载 `markerdeck-screen.html?mode=display`。
- 投放期间由原生层请求沉浸式全屏并保持屏幕常亮；网页的 Wake Lock 作为补充，不能替代原生能力检测和状态展示。
- 用户完成 PIN、图案、密码或生物识别解锁后，在 `onResume` 中恢复 WebView、投放状态和全屏；普通应用不得宣称能跳过安全认证。
- `setShowWhenLocked` 和 `setTurnScreenOn` 只能用于展示界面和点亮屏幕，不能绕过 PIN、图案、密码或生物识别。
- Activity 仍在前台或任务中时，screen-on、user-present、resume 和窗口重新获得焦点都会触发同一条投放恢复路径；不启动后台 Activity，不请求解除 keyguard。
- 进程被杀、用户 force-stop、系统后台启动限制或 OEM 安全策略生效时，不保证自动拉起或覆盖系统锁屏。

### 2. iOS/iPadOS：当前不实施/未来评估

- 本轮不创建 iOS/iPadOS 客户端，不纳入 Android 任务依赖、验收标准或 Release 产物。
- 普通 App 无法自动覆盖系统锁屏，也不能自行回到前台。
- 后续如有现场需求，再评估 Guided Access、受监管设备的 Single App Mode 和 MDM 方案；评估顺序位于 Android 可发布版本之后。

### 3. 产品设置建议

- 将“保持屏幕常亮”和“解锁后恢复投放”作为普通投放的实际状态处理，不增加设备管理模式开关。
- 控制端显示当前设备实际具备的能力，以及能力不可用或执行失败的具体原因。
- 普通投放、网页锁定和系统锁屏使用不同的状态文案；不得把网页锁定或窗口可见性描述为系统级锁定。

### 4. 开发任务

每项任务都必须保留范围、产物、验收标准和依赖。任务编号用于 Issue、PR、测试记录和 Release 追踪。

#### MD-A01 Android 工程骨架与 CI

- **范围**：建立 `android/` 下的 Kotlin + Android SDK + Gradle 工程，确定包名、模块边界、min/target/compile SDK 和 JDK/Gradle 版本策略；加入基础 Manifest、调试构建、静态检查和最小单元测试入口。将 Android 检查接入 GitHub Actions，不改变现有 Node 检查流程。
- **产物**：可独立构建的 Android 工程、Gradle Wrapper、版本与本地构建说明、Android CI job 及最小 APK 构建产物。
- **验收标准**：从干净 checkout 按文档执行 Gradle 检查和 debug APK 构建均成功；CI 能报告失败而不是跳过 Android job；版本矩阵和未验证范围有明确记录；工程不包含 Node.js 或 FFmpeg 二进制。
- **依赖**：无功能开发依赖；以当前仓库结构、v1.3.0 HTTP/SSE 协议和现有 GitHub Actions 约定为基线。

#### MD-A02 普通投放端 MVP

- **范围**：完成 Android 普通投放的连接配置、设备命名、单 Activity WebView、沉浸式全屏和原生 `KEEP_SCREEN_ON`。用户明确选择服务端地址后，WebView 加载 `markerdeck-screen.html?mode=display`，继续使用现有 `deviceId`、`sessionId`、SSE 和 HTTP 协议；配置写入 DataStore，并提供地址无效、服务不可达等可见错误。
- **产物**：普通投放可安装 APK、服务地址/设备名设置页面、WebView 加载与错误状态、协议兼容性测试记录。
- **验收标准**：未选择地址时不加载投放 WebView，设置页发现仅使用 MD-A08 的固定端口和短超时；重启应用后服务地址和设备名可恢复，但不会自动连接；当前 v1.3.0 服务端能看到设备并通过 SSE/HTTP 更新画面和锁定状态；旧网页投放端仍可连接同一服务端；投放期间全屏和常亮生效，失败时显示原因。
- **依赖**：MD-A01；现有 `src/web/markerdeck-screen.html`、`deviceId/sessionId` 注册行为和 v1.3.0 服务端协议。

#### MD-A03 生命周期/灭屏解锁恢复与能力上报（P0 第一门禁）

状态：P0 屏幕打开即时可见性首片已实现；真实设备、20 次循环和 OEM 差异仍待现场验证。

- **P0/第一门禁**：当投放 Activity 和明确的投放状态仍然活动时，系统交付 screen-on/resume 回调后，MarkerDeck 应尽快恢复为可见的普通投放界面。该门禁优先于其他 A03 恢复和能力上报工作。
- **范围**：处理 Activity 的 `onPause`、`onStop`、`onResume`、灭屏/亮屏和 WebView 进程异常后的恢复；重新建立页面、SSE 和状态同步；上报普通投放的全屏、常亮、WebView、`KeyguardManager` 实际状态与失败原因。不以后台服务、后台 Activity 启动或认证绕过冒充前台恢复。
- **产物**：生命周期恢复状态机、纯恢复/状态决策测试、能力诊断界面、普通投放恢复测试记录和异常状态提示。
- **P0 可测验收**：
  - Activity/投放仍活动时，屏幕打开后 UI 应在系统交付 screen-on/resume 回调后及时可见。
  - 目标为系统交付回调后 `<= 1` 秒；测试记录需包含回调、画面可见时间和设备/API/OEM。
  - 真实设备必须完成连续 20 次灭屏/亮屏循环；模拟器结果不能替代真实设备结果。
  - 安全锁必须保持活动；普通模式不得声称绕过 PIN、图案、密码或生物识别认证；OEM 的锁屏、窗口、WebView、电池和后台限制必须逐项记录。
- **后续 A03 验收**：断网重连和服务端重启后能重新同步；横竖屏切换及不同分辨率下画面不丢失；WebView renderer 退出后可恢复；任何能力不可用或恢复失败都显示原因，不静默宣称系统级锁定。
- **依赖**：MD-A02；需要可重复控制灭屏/亮屏、解锁、断网和服务端重启的真实设备测试环境。

#### MD-A04 专用设备功能

状态：已取消，不实施。

- **原因**：Device Owner/Lock Task 需要管理员配置，错误配置可能要求恢复出厂或清除设备数据；这类管理风险高于当前普通投放需求。
- **决定**：删除相关源码、Manifest 声明、资源、测试、入口和操作说明；不提供设备初始化命令，也不将普通投放描述为系统级专用设备。
- **影响**：MD-A05 原专用设备配置/退出/恢复工具任务一并取消，可靠性工作只覆盖普通投放的生命周期恢复和现场边界。

#### MD-A06 普通投放可靠性、OEM 与弱网测试

- **范围**：建立普通投放测试矩阵，至少包含 Pixel、三星、小米，或记录清楚的同等级不同 OEM 差异化设备；覆盖与 A01 锁定的 minSdk、一个中间 API 和 target API 代表环境。测试灭屏/亮屏、解锁恢复、断网重连、服务端重启、SSE 恢复、横竖屏、不同分辨率、WebView 异常和 OEM 电池/后台限制。
- **产物**：设备与 API 矩阵、弱网和故障注入用例、每次运行的日志/结果、已知限制和支持声明；未测试的 OEM/API 必须标记为未验证。
- **验收标准**：普通投放完成连续 20 次灭屏/亮屏测试；断网重连、服务端重启、横竖屏和分辨率用例均有结果；设备差异导致的失败显示具体原因并登记，不把未验证环境写成已支持；现有旧网页投放端与 v1.3.0 服务端兼容性回归通过。
- **依赖**：MD-A02、MD-A03、MD-A08；需要至少三类 OEM 设备或等价替代设备、可控局域网和测试服务器。

#### MD-A07 签名 APK、GitHub Actions 和 Release 产物

- **范围**：为 Android Release 配置受保护的签名密钥与 CI secrets，构建可安装的签名 APK，生成校验值并上传 GitHub Actions/Release 产物；保持现有 macOS、Windows 和服务端 Release 流程不回归。APK 只包含 Android 客户端，不打包 Node.js 或 FFmpeg。
- **产物**：签名配置说明、GitHub Actions Android 构建/上传 job、签名 APK、SHA-256 校验值、Release 说明和安装/升级/回滚说明。
- **验收标准**：从发布标签或明确的手动构建入口生成可验证签名的 APK，并上传到对应 Release；密钥不进入仓库或日志；在 A06 已验证的设备上完成安装和启动，单宿主可自动填充但仍要求用户明确点击连接，多宿主可选择且保留手动地址；现有桌面 Release 产物和 v1.3.0 服务端兼容性不受影响。
- **依赖**：MD-A01、MD-A06；需要受保护的签名密钥、GitHub Actions 权限和已通过前置门禁的测试结果。

#### MD-A08 局域网宿主自动发现与简化地址输入

状态：代码、纯逻辑单测和服务端 UDP/HTTP 集成测试已实现；真实 Android 无线设备和多宿主现场验收待完成。

- **范围**：服务端在固定 UDP `8766` 端口提供版本化 `markerdeck` 广播/多播响应，返回名称、HTTP 端口、HTTP origin、实例 ID 和 nonce；Android 设置页在 Wi-Fi/以太网前台执行短时扫描，获取候选后通过候选源地址的 `/api/discovery` HTTP 握手校验，再展示或建议填入宿主。手动地址继续接受裸 IP、`IP:端口`、`localhost:端口` 和完整 HTTP(S) 地址。
- **安全与边界**：不做全网端口扫描、公网发现、后台持续扫描或未经校验的导航；仅接受协议版本、nonce、实例、端口、HTTP origin 和局域网 IPv4 均匹配的响应，并使用观测到的 UDP 对端地址生成最终服务 origin。nonce 不是认证机制，现场仍需可信局域网和现有 URL/cleartext 约束。
- **产物**：UDP 发现服务、HTTP nonce 握手端点、Android 发现状态/刷新/多宿主选择 UI、网络回调和多播锁生命周期处理、地址标准化及发现响应/状态归并纯逻辑测试、构建与现场验收清单。
- **验收标准**：一个宿主自动填入空且未编辑的字段；多个宿主可点击选择；刷新、网络切换、Activity 暂停/恢复和发现失败不泄漏回调、不覆盖用户输入并回退手动输入；现有投放 URL 拼接、同源校验和 v1.3.0 HTTP/SSE 行为不回归。
- **依赖**：MD-A01、MD-A02；需要可访问的同一局域网服务端、Android Wi-Fi/以太网设备和多宿主/网络切换测试环境。

#### MD-A09 Android Host MVP 前台宿主

状态：第一纵切实现中；本阶段只承诺 Activity 前台宿主，不是 Foreground Service，也不保证后台或锁屏持续托管。

- **范围**：在 Android 设置/模式入口明确提供“本地投放”“连接局域网宿主”“本机作为宿主”三个互不重叠的入口。前者启动只绑定 loopback 的内置 HTTP 服务并加载 `127.0.0.1` 投放页；后者在 Activity 前台绑定 LAN HTTP 服务并加载 localhost 控制页，控制页通过 `/api/info` 显示本机 LAN 地址和二维码。同网浏览器或 Android 被控端可以使用现有网页注册、状态同步、SSE 和锁定命令闭环。
- **资产与协议**：Android Gradle `assets` sourceSet 直接引用 `src/web`，不复制第二份 HTML/CSS/经典 JS，并保留既有加载顺序、`file://` 行为和桌面 Node 服务。内置服务采用 NanoHTTPD 和 ZXing core，覆盖静态资源、`/api/info`、发现、注册/设备、状态、SSE、预设、设备名称/分组/清理、锁定/ACK、`/qr.svg` 和 `/api/shutdown`；Android `/api/video*` 明确返回 unsupported，桌面 Node 服务继续提供 FFmpeg 视频能力。
- **生命周期与清理**：宿主服务、SSE 客户端、设备连接和发现 responder 的运行状态以进程内为主；自定义预设和宿主名称/保留设置持久化。返回设置、Activity 离开前台或销毁时停止服务、SSE、UDP responder 和多播锁；不引入 Node Android runtime，不承诺后台常驻。可靠性、后台限制和锁屏行为另列后续任务，不作为本阶段支持声明。
- **产物**：模式入口 UI、共享 assets sourceSet、`AndroidHostServer`、纯状态/store、SSE hub、UDP responder、二维码生成器和 `HostLifecycleController`；补充 URL/模式决策、协议响应、状态/预设/锁命令及 HTTP 层 JVM 测试、结构检查和构建验收。
- **验收标准**：小屏设置页三个入口垂直排列且不重叠；本地投放无需外部电脑即可显示绿幕并响应当前页面控制；本机宿主控制页显示 LAN 地址/二维码，同网浏览器和被控端可注册并完成一次画面状态同步与锁定 ACK；远程连接、自动发现、设备名、普通 WebView 投放、锁屏恢复和三击退出流程不回归；宿主离开 Activity 前台后服务明确停止。
- **依赖**：MD-A01、MD-A02、MD-A03、MD-A08；需要 Android Studio JBR/SDK、同网浏览器或另一 Android 被控端完成现场闭环验证。

### 5. 关键门禁与共同验收

- MD-A02 普通投放端 MVP 完成并通过兼容性验收后，继续以普通 Activity 生命周期作为唯一恢复路径，不在文档、界面或 Release 说明中暗示系统级锁定能力。
- MD-A03 必须先让普通投放达到“系统交付回调后尽快恢复投放画面”的目标；目标时间和实际设备结果分开记录。
- 普通投放必须覆盖连续 20 次灭屏/亮屏、断网重连、服务器重启、横竖屏切换和不同分辨率；每个用例记录设备、API、OEM、前置条件、结果和日志。
- 任何全屏、常亮、窗口可见性或网络恢复失败都必须显示可读原因；安全锁仍由系统管理，应用不声称覆盖认证或自动拉起。
- 旧网页投放端与 v1.3.0 服务端继续兼容；首版 Android 不要求控制端重写，也不新增必须安装的 Node.js/FFmpeg 运行时。

### 6. 建议目录结构

以下是后续模块边界的建议，已实现部分仍保持在 Android 原生薄壳、服务端发现和现有网页业务的职责范围内：

```text
MarkerDeck/
├── android/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle/
│   ├── app/
│   │   ├── build.gradle.kts
│   │   └── src/
│   │       ├── main/
│   │       │   ├── java/.../MainActivity.kt
│   │       │   ├── java/.../storage/          # DataStore 配置
│   │       │   ├── java/.../web/              # WebView 与页面恢复
│   │       └── test/
│   └── README.md                              # 构建、测试和现场边界
├── src/                                       # 继续复用现有服务端和网页
└── .github/workflows/                         # Android check/release job
```

### 7. 里程碑与顺序

不预设虚假工期，以任务完成和门禁通过作为进入下一里程碑的条件：

- **M1 普通投放可拍摄**：MD-A01、MD-A02、MD-A03 完成；普通投放通过连接、常亮、全屏、解锁后 1 秒恢复、20 次循环和 v1.3.0 兼容性门禁。
- **M2 普通投放可靠性**：MD-A06 完成普通投放的设备/API 支持矩阵、20 次灭屏/亮屏、弱网和 WebView 异常记录。
- **M3 可发布 APK**：MD-A07 生成签名 APK、校验值和 GitHub Release 产物；未验证的 Android API/OEM 不写入支持声明。

建议依赖顺序为 `MD-A01 -> MD-A02 -> MD-A03 -> MD-A06 -> MD-A07`；MD-A08 作为可选发现能力并行维护，但不得绕过普通投放和兼容性门禁发布。

浏览器/PWA 不作为满足强前台的实现。

### 官方参考

- [`Activity.setShowWhenLocked`](https://developer.android.com/reference/android/app/Activity#setShowWhenLocked(boolean))
- [`Activity.setTurnScreenOn`](https://developer.android.com/reference/android/app/Activity#setTurnScreenOn(boolean))
- [`WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON`](https://developer.android.com/reference/android/view/WindowManager.LayoutParams#FLAG_KEEP_SCREEN_ON)

## 发布与维护

- Windows 完整目录 ZIP 在发布前检查主程序、`ffmpeg.dll`、`ffmpeg.exe` 和 `node.exe`。
- Windows 和 macOS 正式发布使用代码签名，降低安全软件误报。
- 自动测试覆盖多会话、断线重连、批量控制、锁定广播和 ACK 超时。
- Release 保留可回滚版本，并附带变更说明和文件校验值。

## 实施原则

- 保持现有 HTTP API 向后兼容。
- 不依赖公网，所有通信只发生在本机或局域网服务中。
- 不为实时通信引入必须单独安装的系统依赖。
- 控制端的轮询刷新不得覆盖用户正在输入的内容或改变滚动位置。
