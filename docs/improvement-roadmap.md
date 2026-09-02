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
- 明确区分网页锁定、浏览器全屏、Electron kiosk 和系统 kiosk。

## Android-first：移动端强前台与专用设备模式

状态：计划中。Android 是当前唯一实施目标，普通投放模式先于专用设备模式交付。iOS/iPadOS 不纳入当前实现、验收或发布物，仅保留未来评估备注。

本规划面向未来的 Android 投放端程序，目标是在投放期间尽快恢复投放画面，但不把普通应用能力误认为可以绕过系统安全策略。浏览器/PWA 不作为满足强前台要求的实现。

### 技术方案

- **技术栈**：使用 Kotlin、Android SDK 和 Gradle，沿用 Android 官方生命周期、窗口和设备策略 API；在没有现成 Android/Gradle 工程的前提下，从 `android/` 目录建立独立工程。
- **应用形态**：单 Activity 的原生薄壳承载 WebView，加载用户选择的 MarkerDeck 服务上的 `markerdeck-screen.html?mode=display`，保留现有 HTML/JS、画布渲染和投放交互，不在 Android 端复制控制端或页面业务。
- **协议兼容**：继续使用现有 `deviceId`、`sessionId`、SSE 事件流和 HTTP 写入/断线恢复协议。首版不要求服务端新增 Android 专用接口，旧网页投放端与 v1.3.0 服务端必须继续兼容。
- **本地配置**：使用 DataStore 保存服务端地址、设备名和运行模式；`deviceId` 的长期身份和 `sessionId` 的页面身份仍遵循现有网页机制，除非后续兼容性验证证明必须迁移。
- **网络边界**：只有在用户明确输入或选择局域网 MarkerDeck 服务地址后才建立连接；未选择地址时不连接，不做未经确认的局域网扫描、公网发现、外部回退或遥测。
- **原生职责**：负责沉浸式全屏、`KEEP_SCREEN_ON`、Activity 生命周期恢复、Lock Task、Device Owner/DPC 能力检测及失败原因展示；WebView 继续负责投放画面、注册、SSE、HTTP 同步和现有网页锁定逻辑。
- **Foreground Service 边界**：不声称 Foreground Service 能强制把 Activity 拉回前台。只有在验证确实需要维持原生心跳或后台任务时才引入，并明确其不能绕过锁屏、不能替代生命周期恢复。
- **首版边界**：不重写控制端，不把 Node.js 或 FFmpeg 打进 Android APK；服务端仍运行在用户明确选择的局域网 MarkerDeck 主机上。

### 1. 普通 Android 模式

- 使用原生 Activity 作为现有投放页面的承载壳，默认加载 `markerdeck-screen.html?mode=display`。
- 投放期间由原生层请求沉浸式全屏并保持屏幕常亮；网页的 Wake Lock 作为补充，不能替代原生能力检测和状态展示。
- 用户完成 PIN、图案、密码或生物识别解锁后，在 `onResume` 中恢复 WebView、投放状态和全屏；普通应用不得宣称能跳过安全认证。
- 普通模式不以 Lock Task 或 `setKeyguardDisabled` 作为可用前提；检测不到受管能力时保持普通模式，并显示不可用原因。
- `setShowWhenLocked` 和 `setTurnScreenOn` 只能用于展示界面和点亮屏幕，不能绕过 PIN、图案、密码或生物识别。
- Foreground Service 只在有明确原生心跳或后台任务需求时考虑，不能保证强制将界面拉回前台。

### 2. Android 专用设备/Kiosk 模式（推荐满足“不显示锁屏”）

- 设备必须作为 Device Owner/DPC 受管设备配置，使用 allowlist 和 Lock Task 限制可运行范围。
- 仅在系统条件允许时，通过 `DevicePolicyManager.setKeyguardDisabled` 关闭 keyguard；设备设置了 PIN、图案或密码时，该调用可能无效，必须检查实际结果。
- 需要专用设备初始化、管理员授权和受管配置，不能在普通个人手机上静默获得 Device Owner 或 kiosk 能力。
- 只在能力检测通过后宣称已进入专用设备模式；进入、退出或恢复失败都必须向操作员显示具体原因。
- 即使在专用设备模式，也不声称可以屏蔽系统紧急功能或硬件电源行为。

### 3. iOS/iPadOS：当前不实施/未来评估

- 本轮不创建 iOS/iPadOS 客户端，不纳入 Android 任务依赖、验收标准或 Release 产物。
- 普通 App 无法自动覆盖系统锁屏，也不能自行回到前台。
- 后续如有现场需求，再评估 Guided Access、受监管设备的 Single App Mode 和 MDM 方案；评估顺序位于 Android 可发布版本之后。

### 4. 产品设置建议

- 将“保持屏幕常亮”“解锁后恢复投放”“专用设备模式”设计为三个独立的开关/状态。
- 控制端显示当前设备实际具备的能力，以及能力不可用或执行失败的具体原因。
- 普通模式、专用设备模式和网页锁定使用不同的状态文案；没有通过 Device Owner/DPC 与 Lock Task 检查时，禁止静默显示“已锁定”或“专用设备”。

### 5. 开发任务

每项任务都必须保留范围、产物、验收标准和依赖。任务编号用于 Issue、PR、测试记录和 Release 追踪。

#### MD-A01 Android 工程骨架与 CI

- **范围**：建立 `android/` 下的 Kotlin + Android SDK + Gradle 工程，确定包名、模块边界、min/target/compile SDK 和 JDK/Gradle 版本策略；加入基础 Manifest、调试构建、静态检查和最小单元测试入口。将 Android 检查接入 GitHub Actions，不改变现有 Node 检查流程。
- **产物**：可独立构建的 Android 工程、Gradle Wrapper、版本与本地构建说明、Android CI job 及最小 APK 构建产物。
- **验收标准**：从干净 checkout 按文档执行 Gradle 检查和 debug APK 构建均成功；CI 能报告失败而不是跳过 Android job；版本矩阵和未验证范围有明确记录；工程不包含 Node.js 或 FFmpeg 二进制。
- **依赖**：无功能开发依赖；以当前仓库结构、v1.3.0 HTTP/SSE 协议和现有 GitHub Actions 约定为基线。

#### MD-A02 普通投放端 MVP

- **范围**：完成普通 Android 模式的连接配置、设备命名、单 Activity WebView、沉浸式全屏和原生 `KEEP_SCREEN_ON`。用户明确选择服务端地址后，WebView 加载 `markerdeck-screen.html?mode=display`，继续使用现有 `deviceId`、`sessionId`、SSE 和 HTTP 协议；配置写入 DataStore，并提供地址无效、服务不可达等可见错误。
- **产物**：普通模式可安装 APK、服务地址/设备名/模式设置页面、WebView 加载与错误状态、协议兼容性测试记录。
- **验收标准**：未选择地址时不建立网络连接；重启应用后服务地址、设备名和模式可恢复；当前 v1.3.0 服务端能看到设备并通过 SSE/HTTP 更新画面和锁定状态；旧网页投放端仍可连接同一服务端；投放期间全屏和常亮生效，失败时显示原因。
- **依赖**：MD-A01；现有 `src/web/markerdeck-screen.html`、`deviceId/sessionId` 注册行为和 v1.3.0 服务端协议。

#### MD-A03 生命周期/灭屏解锁恢复与能力上报（P0 第一门禁）

状态：P0 屏幕打开即时可见性首片已实现；真实设备、20 次循环和 OEM 差异仍待现场验证。

- **P0/第一门禁**：当投放 Activity 和明确的投放状态仍然活动时，系统交付 screen-on/resume 回调后，MarkerDeck 应尽快恢复为可见的普通投放界面。该门禁优先于其他 A03 恢复、能力上报和后续专用设备工作。
- **范围**：处理 Activity 的 `onPause`、`onStop`、`onResume`、灭屏/亮屏和 WebView 进程异常后的恢复；重新建立页面、SSE 和状态同步；上报普通模式的全屏、常亮、WebView、`KeyguardManager` 实际状态与失败原因。普通模式不以后台服务、后台 Activity 启动或认证绕过冒充前台恢复。
- **产物**：生命周期恢复状态机、纯恢复/状态决策测试、能力诊断界面、普通模式恢复测试记录和异常状态提示。
- **P0 可测验收**：
  - Activity/投放仍活动时，屏幕打开后 UI 应在系统交付 screen-on/resume 回调后及时可见。
  - 目标为系统交付回调后 `<= 1` 秒；测试记录需包含回调、画面可见时间和设备/API/OEM。
  - 真实设备必须完成连续 20 次灭屏/亮屏循环；模拟器结果不能替代真实设备结果。
  - 安全锁必须保持活动；普通模式不得声称绕过 PIN、图案、密码或生物识别认证；OEM 的锁屏、窗口、WebView、电池和后台限制必须逐项记录。
- **后续 A03 验收**：断网重连和服务端重启后能重新同步；横竖屏切换及不同分辨率下画面不丢失；WebView renderer 退出后可恢复；任何能力不可用或恢复失败都显示原因，不静默宣称已锁定或 Kiosk。
- **依赖**：MD-A02；需要可重复控制灭屏/亮屏、解锁、断网和服务端重启的真实设备测试环境。

#### MD-A04 Device Owner/DPC + Lock Task 专用设备原型

- **范围**：在受管测试机上验证 Device Owner/DPC 配置、Lock Task allowlist、进入/退出 Lock Task、`setKeyguardDisabled` 条件检查和能力上报；区分“调用成功”“实际没有安全锁屏”和“被 PIN/图案/密码或 OEM 策略拒绝”。不为普通个人设备增加静默提权流程。
- **产物**：可重复初始化的专用设备原型、DPC/DevicePolicyManager 能力检测、Lock Task 进入/退出流程、受管测试机记录和失败原因清单。
- **验收标准**：只能在已配置 Device Owner/DPC 的受管测试机上进入专用设备模式；未通过 allowlist、Lock Task 或 keyguard 条件检查时明确拒绝并说明原因；已受管设备连续 20 次灭屏/亮屏循环均回到投放画面，且测试中没有 Home、通知栏或系统锁屏页面中断；不声称屏蔽系统紧急功能或硬件电源行为。
- **依赖**：MD-A02、MD-A03；需要可重置的 Device Owner/DPC 测试机和管理员配置权限。MD-A02 完成前不得开始产品级 Kiosk 验收。

#### MD-A05 专用设备配置/退出/恢复工具与操作文档

- **范围**：补齐专用设备初始化、服务地址和模式配置、进入/退出 Kiosk、应用崩溃/WebView 异常/服务端不可达后的恢复流程；提供授权的配置/退出工具或脚本及现场操作文档，明确普通手机、受管测试机和不同 OEM 的边界。
- **产物**：专用设备配置与退出工具、恢复/回滚步骤、现场操作手册、故障码或可读错误信息、Device Owner 清理和测试机复位说明。
- **验收标准**：在一台新重置的受管测试机上，操作员按文档可完成配置、验证、退出和恢复；应用重启、服务端重启、断网后可按文档恢复；缺少 Device Owner/DPC 或 Lock Task 条件时不会伪装成成功；文档标出需管理员授权、可能清除设备数据及 OEM 差异。
- **依赖**：MD-A04、MD-A02、MD-A03；需要可重置的受管测试机和明确的现场管理员流程。

#### MD-A06 可靠性、OEM 与弱网测试

- **范围**：建立覆盖普通模式和专用模式的测试矩阵，至少包含 Pixel、三星、小米，或记录清楚的同等级不同 OEM 差异化设备；覆盖与 A01 锁定的 minSdk、一个中间 API 和 target API 代表环境。测试灭屏/亮屏、解锁恢复、断网重连、服务端重启、SSE 恢复、横竖屏、不同分辨率、WebView 异常和 OEM 电池/后台限制。
- **产物**：设备与 API 矩阵、弱网和故障注入用例、每次运行的日志/结果、已知限制和支持声明；未测试的 OEM/API 必须标记为未验证。
- **验收标准**：普通模式和专用模式分别完成连续 20 次灭屏/亮屏测试；断网重连、服务端重启、横竖屏和分辨率用例均有结果；设备差异导致的失败显示具体原因并登记，不把未验证环境写成已支持；现有旧网页投放端与 v1.3.0 服务端兼容性回归通过。
- **依赖**：MD-A02 至 MD-A05；需要至少三类 OEM 设备或等价替代设备、可控局域网和测试服务器。

#### MD-A07 签名 APK、GitHub Actions 和 Release 产物

- **范围**：为 Android Release 配置受保护的签名密钥与 CI secrets，构建可安装的签名 APK，生成校验值并上传 GitHub Actions/Release 产物；保持现有 macOS、Windows 和服务端 Release 流程不回归。APK 只包含 Android 客户端，不打包 Node.js 或 FFmpeg。
- **产物**：签名配置说明、GitHub Actions Android 构建/上传 job、签名 APK、SHA-256 校验值、Release 说明和安装/升级/回滚说明。
- **验收标准**：从发布标签或明确的手动构建入口生成可验证签名的 APK，并上传到对应 Release；密钥不进入仓库或日志；在 A06 已验证的设备上完成安装和启动，首次连接仍要求用户选择局域网服务；现有桌面 Release 产物和 v1.3.0 服务端兼容性不受影响。
- **依赖**：MD-A01、MD-A06；需要受保护的签名密钥、GitHub Actions 权限和已通过前置门禁的测试结果。

### 6. 关键门禁与共同验收

- MD-A02 普通投放端 MVP 完成并通过兼容性验收前，不做产品级 Kiosk，不在文档、界面或 Release 说明中暗示普通模式具备专用设备能力。
- MD-A03 必须先让普通模式达到“用户解锁后 1 秒内恢复投放画面”，再把生命周期恢复结果作为后续专用模式的基线。
- MD-A04 只能在已配置 Device Owner/DPC 的受管测试机上验收；普通个人手机不作为 Kiosk 验收对象，能力不足时必须明确显示降级原因，不能记录为 Kiosk 成功。
- 普通模式与专用模式均须覆盖连续 20 次灭屏/亮屏、断网重连、服务器重启、横竖屏切换和不同分辨率；每个用例记录设备、API、OEM、前置条件、结果和日志。
- 任何全屏、常亮、Lock Task、keyguard 或网络恢复失败都必须显示可读原因和当前降级模式；禁止静默宣称“已锁定”“不显示锁屏”或“已进入专用设备”。
- 旧网页投放端与 v1.3.0 服务端继续兼容；首版 Android 不要求控制端重写，也不新增必须安装的 Node.js/FFmpeg 运行时。

### 7. 建议目录结构

以下是后续实现的建议边界，本轮不创建这些 Android 源码或工程文件：

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
│   │       │   ├── java/.../device/           # 能力检测、Lock Task
│   │       │   └── java/.../dpc/              # A04 所需的管理组件
│   │       └── test/
│   ├── tools/                                 # 受管设备配置与恢复工具
│   └── README.md                              # 构建、测试和现场边界
├── src/                                       # 继续复用现有服务端和网页
└── .github/workflows/                         # Android check/release job
```

### 8. 里程碑与顺序

不预设虚假工期，以任务完成和门禁通过作为进入下一里程碑的条件：

- **M1 普通模式可拍摄**：MD-A01、MD-A02、MD-A03 完成；普通模式通过连接、常亮、全屏、解锁后 1 秒恢复、20 次循环和 v1.3.0 兼容性门禁。
- **M2 专用 Kiosk 可控**：MD-A04、MD-A05 完成；仅在受管测试机上能可靠进入、退出、恢复和解释失败原因，并完成专用模式测试门禁。
- **M3 可发布 APK**：MD-A06 完成并冻结支持矩阵，MD-A07 生成签名 APK、校验值和 GitHub Release 产物；未验证的 Android API/OEM 不写入支持声明。

建议依赖顺序为 `MD-A01 -> MD-A02 -> MD-A03 -> MD-A04 -> MD-A05 -> MD-A06 -> MD-A07`。MD-A06 的兼容性测试可在 MD-A04/MD-A05 的原型稳定后分批开始，但不得绕过前置门禁发布。

浏览器/PWA 不作为满足强前台的实现。

### 官方参考

- [Android Lock Task Mode](https://developer.android.com/work/dpc/dedicated-devices/lock-task-mode)
- [`DevicePolicyManager.setKeyguardDisabled`](https://developer.android.com/reference/android/app/admin/DevicePolicyManager#setKeyguardDisabled(android.content.ComponentName,boolean))
- [Apple Guided Access](https://support.apple.com/guide/iphone/iph7fad0d10/ios)
- [Apple Configurator: Set Single App Mode](https://support.apple.com/guide/apple-configurator-mac/set-single-app-mode-cadbf9c172/mac)

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
