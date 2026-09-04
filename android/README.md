# MarkerDeck Android

这是 MD-A09 Android Host MVP、MD-A10 Android 扫描二维码连接宿主、MD-A11 亮度与远程锁定延迟优化、MD-A14 mDNS 自动发现，以及 MD-A03 普通投放恢复、MD-A08 局域网宿主发现和 Android 投放紧急退出实现。它是一个单 Activity 原生薄壳：设置/模式页提供本地投放、连接局域网宿主、本机作为宿主三个入口。内置宿主使用 `connectedDevice` 前台服务保持后台网络连接；实现不包含设备管理、专用设备/Kiosk、Wake Lock 权限、精确闹钟、后台启动 Activity、辅助功能服务、overlay、root、隐藏 API 或绕过认证的机制。

## 网页静态资源复用边界

APK 的内置宿主通过 Gradle `assets` sourceSet 直接引用仓库 `src/web`，不复制第二份业务文件。`markerdeck-base.css`、`markerdeck-control.css`、`markerdeck-mobile.css` 与按 HTML 顺序加载的 `markerdeck-*.js` 作为整体资产复用；远程投放仍从已验证的服务地址加载 `/markerdeck-screen.html?mode=display`，`file://` 模式仍只支持浏览器本地投放。Android 不另写 Canvas、导出或锁定业务；宿主只提供 HTTP/SSE 协议适配。

## MD-A09 Android Host MVP

- **本地投放**：启动只绑定 `127.0.0.1` 的内置 NanoHTTPD，加载 `http://127.0.0.1:<port>/markerdeck-screen.html?mode=local`，无需外部电脑即可显示和使用现有页面控制。
- **本机作为宿主**：启动绑定 LAN 的内置前台服务并加载 localhost 控制页。控制页从 `/api/info` 获取本机 LAN 地址和 `/qr.svg`，同网浏览器/Android 被控端复用现有注册、状态、SSE、预设和锁命令协议。
- **连接局域网宿主**：继续使用原地址、自动发现、设备名、普通 `display` WebView、屏幕恢复和三击临时退出入口流程。
- **扫描二维码连接宿主**：服务地址旁提供“扫描二维码”按钮，使用 JourneyApps ZXing Embedded 的 `ScanContract` 和 MarkerDeck 自有 `MarkerDeckCaptureActivity`；只有用户点击按钮后才检查并请求相机权限。扫描 Activity 在 Manifest 中使用 `sensorPortrait` 并由 `ScanOptions` 锁定方向，允许正反竖屏但不会跟随传感器进入横屏；该限制只作用于扫码，不改变投放 WebView、本地投放或宿主控制页的横竖屏行为。成功读取的启动页、控制端、投放端 URL 或裸 IP/IP:端口会归一化为 HTTP(S) 服务 origin，先询问宿主连接，再确认设备名称后进入投放；取消、拒绝、无相机、不可识别内容和扫描器异常均显示状态且不保存配置。
- **能力边界**：Android `/api/info` 宣布 `videoExport: false`，网页隐藏视频导出；没有 FFmpeg/MP4 导出。桌面 Node 服务保持视频能力。
- **生命周期与停止**：宿主启动后由 `MarkerDeckHostService` 以前台服务方式保持，返回桌面、锁屏、切换应用、Activity `onStop`/`onDestroy` 或从最近任务移除均不会主动停止。设置首页显示运行状态/地址并提供“停止内置宿主服务”，常驻通知也提供“停止服务”；Android 13 以上首次成功启动宿主后会请求一次通知权限，拒绝不会阻止服务运行，但通知栏入口可能不可见。显式停止、切换到远程宿主、普通本地投放退出和 `/api/shutdown` 会清理 HTTP、SSE、UDP responder、多播锁及通知。系统强制停止应用、设备关机或 OEM 强制终止进程仍会停止服务；系统允许 `START_STICKY` 恢复时会按已保存的宿主模式重新启动。

## MD-A14 mDNS 自动发现与启动询问

- Android 使用系统 `NsdManager` 发布/浏览 `_markerdeck._tcp`；这是 Android API 对 `_markerdeck._tcp.local` 的服务类型写法。发布 TXT 包含 `service=markerdeck`、`protocolVersion=1`、`instanceId` 和安全显示名。一次前台宿主会话只生成一个 instanceId，并由 HTTP host、UDP responder、mDNS publisher 共享。
- 设置页默认打开时同时执行有界的 NsdManager 与 UDP 扫描，mDNS resolve 最多三个并发；刷新、停止扫描、网络变化、Activity 暂停/销毁会注销监听并忽略旧回调。候选先做纯逻辑校验，再访问观测地址的 `/api/discovery?nonce=...`，通过后才进入列表。
- 单宿主发现后先显示简洁的“是否连接”询问；多个宿主显示可选择列表，不会擅自连接。确认后复用宿主确认、设备命名和普通 `display` 投放入口；拒绝或没有宿主继续显示现有模式选择页。一次启动会话中同一宿主只提示一次，刷新或用户主动扫描可再次选择。
- 自动提示只在设置页可交互且系统锁屏权限引导、扫码确认和 Activity 生命周期没有占用窗口时显示。mDNS 不可用或被路由器隔离时，UDP 和手动地址仍可用。
- Node 与 Android `/api/info` 均保留 `udpDiscovery` 并增加准确的 `mdnsDiscovery`；Android APK 不包含 Node.js、FFmpeg 或 bonjour-service。

内置服务 API 见 `AndroidHostServer.kt`，包括静态资源、`/api/info`、`/api/discovery`、`/qr.svg`、`/api/register`、`/api/devices`、`/api/state`、`/api/events`、预设、设备设置/清理、设备名称/分组、锁定/ACK 和 `/api/shutdown`。HTTP 状态为进程内，预设与宿主设置使用 SharedPreferences 持久化。

## MD-A11 亮度、总体亮度与远程锁定边界

- **Android 窗口亮度**：进入本地或远程实际投放页面时，只将当前 Activity 的 `WindowManager.LayoutParams.screenBrightness` 覆盖为 `1.0f`。返回设置、投放错误完成退出或 Activity 清理时恢复 `WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE`；`onResume`、屏幕点亮和窗口重新获得焦点会重新应用 100%。不写 `Settings.System`，不申请 `WRITE_SETTINGS`，不改变系统全局亮度。
- **总体亮度**：网页只保留 0-100 的“总体亮度”，默认 100%。读取旧状态或预设时，`bgBrightness`/`crossBrightness` 会分别折入 `bgColor`/`crossColor` 的 RGB，随后两个 legacy 字段均为字符串 `"100"`；总体亮度是唯一渲染乘数，且 canonicalization 可重复执行。随机点/十字、设备缩略图、PNG 和视频使用同一计算；新状态继续携带 legacy 字段以兼容旧客户端。这不是浏览器硬件亮度 API。
- **远程锁定延迟**：SSE 正常时继续即时下发。SSE 暂断时，约每 1.5 秒的 `registerDevice` 心跳返回状态也会经过同一 `applyRemoteState`，因此持久化的目标锁定命令不必等待 15 秒轮询；首次收到的全局命令只建立当前 baseline，避免历史命令误执行。远程锁定先切换 locked DOM、canvas 和 Electron 状态，不调用缺少用户手势的浏览器 `requestFullscreen`；只有本地用户操作可以请求浏览器 Fullscreen。批量目标、ACK 计数和离线未响应状态仍由服务端命令状态维护。

## 固定版本

- Android Gradle Plugin：`9.0.1`
- Gradle Wrapper：`9.1.0`，使用 `gradle-9.1.0-bin.zip`
- Android QR 扫码：JourneyApps ZXing Embedded `4.3.0`，Activity Result API `androidx.activity:activity:1.10.1`
- JDK：`17` 或更高版本；本机验证使用 Android Studio JBR `21`
- `minSdk`：`26`
- `compileSdk`：API `36.1`（major `36`、minor `1`），通过 `compileSdk = 36` 与 `compileSdkMinor = 1` 选择平台
- `targetSdk`：API `36`
- Android SDK Build Tools：`36.1.0`

工程使用 AGP 9 内置 Kotlin，不应用 `org.jetbrains.kotlin.android` 插件。`compileSdkMinor` 对应 SDK 平台目录 `android-36.1`；它不是旧式 SDK extension `android-36-ext20` 配置。

## 本机构建环境

Android Studio JBR 的路径：

```text
/Applications/Android Studio.app/Contents/jbr/Contents/Home
```

Android SDK 的路径：

```text
/Users/andrower/Library/Android/sdk
```

系统默认 Java 8 不满足要求时，在每次命令前显式指定 `JAVA_HOME` 和 `ANDROID_HOME`：

```bash
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" \
ANDROID_HOME="/Users/andrower/Library/Android/sdk" \
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

也可以在当前 shell 会话先导出两个变量，再执行后续命令。

## 构建与测试

从仓库根目录运行：

```bash
cd android
./gradlew --no-daemon clean testDebugUnitTest lintDebug assembleDebug
```

本机可能输出 `SDK XML versions up to 3` 与 `SDK XML file of version 4` 的提示。这是已安装 command-line tools 与较新 API 36.1 SDK metadata 版本之间的环境提示，不影响本次成功构建。CI 不依赖 runner 预装内容，会显式安装 `platforms;android-36.1` 与 `build-tools;36.1.0`。

Debug APK 输出到：

```text
android/app/build/outputs/apk/debug/app-debug.apk
```

纯 Kotlin 单元测试验证服务地址解析、IPv6、投放 URL 编码、二维码宿主地址归一化、权限/取消状态、发现响应校验和发现状态归并，不启动 Android 网络请求、扫码相机或 WebView。根目录的 `npm run check` 同时执行项目结构、JavaScript/HTML 语法和服务端 HTTP/UDP 集成检查。

仓库检查命令：

```bash
cd /Users/andrower/Desktop/OU/MarkerDeck
npm run check
git diff --check
```

## A02 行为

- 首次打开显示设置页；在 Activity 位于前台且有 Wi-Fi/有线局域网时，设置页会执行有界的 MarkerDeck 宿主发现。发现宿主后只询问是否连接，不会未经确认进入投放 WebView；拒绝或无结果继续保留模式选择页。
- 用户填写服务地址和设备名并点击“连接并开始投放”后，地址才会验证、写入 DataStore，并加载 `/markerdeck-screen.html?mode=display`。
- 地址接受完整的 `http://`/`https://` 地址，也接受 `192.168.1.2`、`192.168.1.2:8765`、`localhost:8765` 等无 scheme 输入；无 scheme 时自动补 `http://`，必须有主机名或 IP，端口必须为 `1..65535`。路径、查询和片段会被丢弃并归一化为 origin。IPv6 使用方括号保持正确 URL 结构。
- 扫描二维码只接受同一地址规则能归一化的 HTTP(S) 主机内容：`markerdeck-launch.html`、`markerdeck-screen.html?mode=control`、`markerdeck-screen.html?mode=display`、`/control`、`/display` 等完整 URL，以及裸 IP/IP:端口。`javascript:`, `file:`, `ftp:`、空内容、无主机和非法端口会被拒绝；路径、查询和片段不会写入服务地址字段。自有扫描 Activity 使用 `sensorPortrait` 且 `exported=false`，方向配置不会外溢到普通投放或宿主控制 Activity。
- 扫描成功只填入服务地址并显示“请点击连接确认”，不会写入 DataStore、启动 WebView、进入投放页或改变设备名称；相机权限请求只由“扫描二维码”按钮触发。取消、权限拒绝、无相机和扫描失败不会覆盖手动输入。
- 设备名会限制为 40 个字符并作为编码后的 `androidDeviceName` 查询参数传给页面。页面将它写入既有 `localStorage`，因此 Android 不弹出网页设备名对话框；普通浏览器未提供该参数时仍保留原有对话框。
- 配置重启后恢复，但应用不会自动进入投放；必须再次点击连接。应用始终使用普通投放。升级前遗留的 `mode` 配置会被忽略，不影响地址和设备名读取，并在下一次保存配置时删除。
- WebView 仅开启 JavaScript 和 DOM Storage，关闭文件/内容访问；原生 JavaScript bridge 仅用于投放紧急控件的显示/隐藏通知。HTTP 局域网连接由 Manifest 的 `INTERNET` 权限和 cleartext 配置支持。
- 活动投放页面沿用网页左上角三击安全手势。最小 Android bridge 用 `showEmergencyExitWhileUnlocked()` 表示初始未锁定投放，用 `showEmergencyControls()` 表示“已锁定投放经本机手势解锁”，用 `showEmergencyControlsForUnlockedProjection()` 表示“原本未锁定投放经三击临时显示”，另接受 `hideEmergencyControls()`；bridge 没有退出 Activity 的方法，网页已有“锁定投放”按钮仍负责锁定。
- 投放期间 WebView 顶层导航只允许已选择服务的同源地址；跨源或无效跳转会停止加载并显示错误。`about:blank` 只用于返回设置时清理页面；同源路径、API、SSE 和子资源请求不受此限制。
- 投放页加载时显示加载状态；主框架服务不可达、HTTP 错误或 HTTPS 证书错误显示错误和“重试”。子资源失败不会把整个页面标为失败。
- 投放期间启用沉浸式全屏和原生 `KEEP_SCREEN_ON`。活动投放中的系统返回键会被消费，不会返回页面、返回设置或结束 Activity；只有状态面板中的显式“返回设置”按钮会停止当前页面并清理全屏/常亮状态。设置页的系统返回仍会正常结束 Activity。
- 设置页提供“打开系统权限设置”入口。首次进入设置且尚未处理引导时，如果锁屏显示状态未确认，会显示一次性的温和提示；点击“稍后”或打开系统页都会持久化处理状态，同一 Activity 生命周期不会重复弹出，系统设置返回后会刷新状态。小米/HyperOS 会优先尝试当前应用的 MIUI 权限编辑页，由用户手动开启“允许锁屏显示”（部分系统显示为“锁屏显示”）；同页若有“后台弹出界面”或“后台启动”，它们可能影响恢复，可按设备策略手动确认。公开 Android SDK 没有统一可读取的 OEM“锁屏显示”状态，因此 Xiaomi 状态显示为“无法自动确认”，其他不支持独立检查的系统温和降级为手动确认；MIUI 页面不可解析或启动失败，以及非小米设备，均回退到 `Settings.ACTION_APPLICATION_DETAILS_SETTINGS` 应用详情页。应用不声明新的标准运行时权限，也不会自动修改这些设置。
- A02 的配置仍不会在普通冷启动时自动进入投放；只有本次明确连接产生的活动投放状态，才会通过 `savedInstanceState` 参与 Activity/进程重建恢复。

## MD-A08 局域网宿主发现

- 服务端在固定 UDP `8766` 端口监听版本化的 `markerdeck` JSON 请求，同时保留现有 HTTP 端口（默认 `8765`）。请求包含随机 nonce；响应包含协议版本、名称、HTTP 端口、HTTP 地址、实例 ID 和同一 nonce，并通过 UDP 单播返回请求端。
- Android 只在当前 Wi-Fi 或以太网可用时扫描，mDNS 与 UDP 同时运行；UDP 仍发送全局/定向 IPv4 广播和 `239.255.77.77` 多播，扫描窗口约 `1.8` 秒；Wi-Fi 扫描期间短暂持有 `MulticastLock`，Activity 离开设置页、暂停或销毁时取消扫描和网络回调。不会做端口扫描、公网发现或后台持续扫描。
- 收到 UDP 候选后，Android 只访问候选源地址的 `GET /api/discovery?nonce=...` 完成 HTTP 握手，并校验 nonce、服务名、协议版本、响应类型、实例 ID、端口、HTTP origin 和局域网 IPv4。最终导航地址使用已验证的 UDP 对端地址；广播内容本身不是认证凭据，nonce 只是请求关联值，局域网内仍应配合现有 URL 校验和 cleartext LAN 边界。
- 启动扫描发现一个宿主时先询问是否连接，多个宿主显示为可点击列表；确认后才进入设备命名和投放。拒绝、无结果、网络切换或发现失败均回退到手动输入；同一启动会话不重复提示同一宿主，手动刷新可再次选择，不覆盖用户正在编辑的地址。

## A03 P0 行为与限制

- 活动投放期间，API 27 及以上使用 `Activity.setShowWhenLocked(true)` 和 `Activity.setTurnScreenOn(true)`；API 26 只使用 `FLAG_SHOW_WHEN_LOCKED` 与 `FLAG_TURN_SCREEN_ON` 兼容标志。没有使用 dismiss keyguard、`requestDismissKeyguard` 或其他认证绕过行为。
- 活动投放期间保留沉浸式全屏和原生 `KEEP_SCREEN_ON`。`onResume`、窗口重新获得焦点、`ACTION_SCREEN_ON` 和 `ACTION_USER_PRESENT` 会恢复窗口状态、WebView 生命周期/计时器和可见的投放层。
- MD-A11 还会在相同的投放窗口生命周期中恢复 100% 的窗口亮度；该覆盖仅属于当前 Activity，设置页和清理路径恢复 `BRIGHTNESS_OVERRIDE_NONE`，不触及系统全局亮度。
- 屏幕打开或恢复时，健康且已加载的页面不会重新加载，避免改变网页 `sessionId` 或打断 SSE。只有没有健康页面，或记录到主框架失败且当前没有加载请求时，才会重新加载当前选定服务 origin 的活动投放 URL。
- WebView 渲染进程退出时，旧 WebView 会从层级中移除并销毁，随后创建同配置的新 WebView，只加载活动投放 URL；恢复期间显示可见状态，失败时显示错误和“重试”。不使用 JavaScript bridge。
- Activity 重建只恢复 `savedInstanceState` 中明确标记为活动的、已归一化的服务地址和设备名。全新冷启动、用户返回设置页或无效快照都不会自动连接；DataStore 配置恢复仍需要用户再次点击连接。旧版本保存的模式字段会被忽略，恢复后仍是普通投放。
- 动态屏幕/用户已出现接收器使用 Activity 注册，并在返回设置页或 Activity 销毁时解除注册；API 33 以上使用接收器导出标志，旧 API 使用兼容重载。没有进程级或 Manifest 接收器。
- 设置页明确提示普通模式受系统安全锁限制，不能绕过认证，并提供手动打开系统权限页的入口。应用遵循 `KeyguardManager.isKeyguardLocked` 的系统锁屏状态，并在恢复或降级状态显示实际诊断；普通模式可以尝试在锁屏上显示界面，但不能跳过 PIN、图案、密码或生物识别认证。普通模式不会显示或宣称 Kiosk。
- 活动投放中的 Android 13+ `OnBackInvokedCallback` 和旧版 `onBackPressed` 都消费系统返回事件，不改变页面、设置页或 Activity 状态；设置页仍按普通 Activity 行为结束。错误/状态面板保留显式“返回设置”按钮。
- 离开投放进入设置或销毁 Activity 时，先清除 show-when-locked/turn-screen-on、API 26 兼容标志、`KEEP_SCREEN_ON` 和沉浸式状态，再显示设置页；WebView 计时器和动态接收器也会停止。宿主 HTTP/SSE/UDP 运行时与 Activity 分离，只有显式停止路径才会清理。

## Android 投放紧急退出

- 初次进入本地或远程投放页时，原生“退出投放”按钮会与未锁定的本地调整窗口同时显示；点击网页“锁定投放”后两者一起隐藏。本机三击左上角在未锁定投放时仍能临时重新调出按钮；锁定后的三击仍先解除网页投放锁定，再显示同一按钮。按钮至少有 `48dp` 触控区域。
- 原生“退出投放”先要求确认，确认后复用现有 `showSettingsScreen()`，清理当前 WebView 投放并返回 Android 设置/模式入口。该路径不请求 `/api/shutdown`，也不停止未来的宿主服务；网页中的“锁定投放”按钮仍是唯一锁定入口。
- 按钮显示后由 Android `Handler` 计时 `8` 秒：已锁定投放经本机三击解锁后显示的按钮，无操作超时会先隐藏按钮，再通过网页公开的单向 `markerdeckRelockProjection` hook 重新锁定；原本未锁定投放经三击显示的按钮，超时只隐藏按钮，不改变网页锁定状态。退出确认弹窗显示期间暂停计时，取消后重新计时，确认退出会清理投放；网页桥接不能直接结束 Activity。
- 错误/加载面板显示时会清理紧急退出按钮，保留现有“返回设置”按钮，避免两个退出入口叠加。WebView renderer 重建、Activity 返回设置和销毁也会取消计时器。
- 设置页内容会在保留 XML 基础 padding 的前提下，按系统 status-bar 和 display-cutout 顶部安全区动态增加 padding；重复应用 Insets 不会累加，投放页的沉浸式全屏行为不变。

锁屏恢复是普通 Android 应用边界内的尽力行为：只有用户已经点击连接并进入实际投放页、Activity 仍在前台或任务中时，应用才会重新申请投放窗口的 show-when-locked、turn-screen-on、`KEEP_SCREEN_ON` 和沉浸式状态。进程被系统杀死、用户 force-stop、系统禁止后台启动或 OEM 安全锁策略限制时，不保证自动拉起 Activity，也不保证覆盖系统锁屏。

P0 目标是系统向 Activity 交付 resume/screen-on 回调后，目标在 `<= 1` 秒内让 UI 可见；实际时间受系统调度、WebView、服务端和 OEM 策略影响，普通模式不能保证覆盖系统锁屏或绕过认证。

## 手工检查清单

在有 Android 设备或模拟器时，安装 `app-debug.apk` 后检查：

- 首次启动在没有可信宿主时只显示设置/模式页；若发现宿主，确认启动提示、设备命名对话框和设置页字段不重叠。若设备有局域网，发现状态和刷新按钮可见。
- 输入空值、`ftp://`、无主机、端口 `0`、端口 `65536`，确认错误可见且不会进入 WebView；输入裸 IP、`IP:端口` 和 `localhost:端口`，确认自动补 `http://`。
- 在服务地址旁点击“扫描二维码”，确认首次点击才请求相机权限；用启动页、控制端、投放端 URL、带 query 的 URL 和裸 IP/IP:端口测试归一化结果，用 `javascript:`, `file:`, `ftp:`、空内容测试拒绝；取消扫码、拒绝权限、无相机或相机异常时确认状态可读且原地址未改变。扫描成功后确认宿主连接和设备名称均需明确确认，取消不保存配置。
- 在同一局域网启动服务端，确认启动时先询问连接；启动多个服务端，确认弹出可选择列表且不会擅自连接或覆盖正在编辑的地址；切换网络或离开设置页后确认扫描停止，返回后可刷新并再次选择。
- 输入可访问的 `http://` MarkerDeck 服务、设备名，点击连接，确认页面进入全屏、保持常亮、窗口亮度为 100%，服务端设备列表显示该名称；调整总体亮度后确认画面、缩略图和导出一致。
- 分别启动本地投放和本机作为宿主，确认设置首页的内置宿主状态显示“运行中”和地址；点击“停止内置宿主服务”后停留在设置页并显示“已停止”，远程宿主地址和发现列表不被清除。
- 在本机作为宿主时返回设置、回到桌面、锁屏和切换应用，使用同网设备确认服务地址仍可访问；重新打开应用后确认宿主仍显示运行中。分别从设置页和常驻通知点击停止，确认 HTTP、SSE、UDP responder、多播锁及通知均被清理。
- 确认含空格、符号或非 ASCII 字符的设备名能正常显示，并且页面没有弹出设备名对话框。
- 停止服务后点击连接或重试，确认主页面显示“服务不可达或投放页面加载失败”或 HTTP 错误及“重试”。恢复服务后点击重试。
- 在设置页点击“打开系统权限设置”，确认小米/HyperOS 优先进入 MarkerDeck 的 MIUI 权限编辑页；若不可用则进入应用详情页，并手动检查“允许锁屏显示”（或“锁屏显示”）。如有“后台弹出界面”或“后台启动”，按设备策略决定是否开启。
- 在首次进入设置时确认锁屏显示引导只出现一次；选择“稍后”后同一 Activity 不再重复，重新启动应用后按持久化处理状态不再强制弹出；打开系统设置返回后确认设置页状态文字已刷新。
- 在显示页按系统返回键，确认仍停留在投放页且页面、设置页和 Activity 均未返回；通过状态面板的显式“返回设置”按钮离开后，确认系统栏恢复、屏幕不再被原生常亮标志保持。
- 远程锁定时确认页面先进入 locked 状态，不等待浏览器 Fullscreen；短暂断开 SSE 后确认约 1.5 秒注册心跳可以执行持久锁定命令，首次连接不会重放历史全局命令，控制端 ACK/批量未响应计数仍准确。
- 退出并重新启动应用，确认地址和设备名恢复，但不会自动连接；重新连接后仍进入普通投放。
- 在常见竖屏、横屏和较小手机尺寸检查输入、状态面板和操作按钮不重叠。

## A03 P0 ADB/手工 20 次循环

以下步骤用于真实设备或模拟器；先记录设备序列号、OEM、Android API、系统是否设置安全锁和 WebView/Chrome 版本。`adb devices -l` 没有列出设备时，不记录为真实设备通过。

1. 构建并安装调试 APK，确认设备在线：

```bash
adb devices -l
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.andrower.markerdeck/.MainActivity
```

2. 在应用中输入可访问的服务地址和设备名，点击连接并确认服务端能看到该投放端。记录连接时的屏幕方向、分辨率和时间。

3. 使用 ADB 让屏幕休眠并唤醒，连续完成 20 次；每次唤醒后记录从系统回调/屏幕亮起到 MarkerDeck UI 可见的时间，并记录是否需要手工解锁：

```bash
for i in $(seq 1 20); do
  echo "cycle $i"
  adb shell input keyevent KEYCODE_SLEEP
  sleep 1
  adb shell input keyevent KEYCODE_WAKEUP
  sleep 1
  # 安全锁设备在这里手工验证锁屏状态，并按设备策略完成解锁
done
```

4. 每次循环检查：投放 Activity 仍是可见界面；健康页面没有被无故重新加载；SSE/画面状态仍在更新；沉浸式全屏和常亮状态恢复；安全锁仍然有效；异常时显示“屏幕已点亮/恢复失败”等实际状态而不是 Kiosk 文案。

5. 分别验证：屏幕打开但未认证时的安全锁限制；完成 PIN、图案、密码或生物识别后 `ACTION_USER_PRESENT`/`onResume` 恢复；返回设置页后不再保持常亮或锁屏上显示；旋转一次后投放仍可见。使用 `KEYCODE_SLEEP`/`KEYCODE_WAKEUP` 不可用时，改用设备电源键并在记录中注明。

6. 对至少一台真实设备完成 20 次记录后，再登记 OEM、API、是否安全锁、每次耗时、失败次数和 OEM 电池/后台限制。模拟器结果不能替代真实设备或 OEM 验证。

本仓库环境可验证构建、静态检查、纯单元测试和本地服务端 HTTP/HTML 兼容性；这不等于完整的 v1.3.0 Android/WebView 兼容性验证。没有连接 Android 设备时，无法声称已验证真实 WebView、沉浸式系统栏、`KEEP_SCREEN_ON`、屏幕旋转或物理常亮效果。

## MD-A04 状态

MD-A04 专用设备功能已取消，不是本应用功能，也不再包含 Device Owner、Lock Task、管理组件、专用退出流程或设备初始化步骤。原因是这类能力需要管理员配置，恢复出厂/清除数据和错误管理的风险高于当前普通投放需求。仓库不提供专用设备启用命令，也不会把普通投放描述为 Kiosk。

### 当前验证边界

本地可运行 Android JVM 单元测试、Lint、debug 构建和根目录 Node 检查；这些结果不等于真机通过。本次只读诊断确认授权设备为 Xiaomi/HyperOS API 35，且当前未处于 MarkerDeck 投放；该设备的 MarkerDeck 进程还显示 `backgroundRestricted=true` 与 `RUN_ANY_IN_BACKGROUND=ignore`，如现场恢复仍受影响，应在系统设置中按需手动调整电池/后台活动为“不限制”。当前没有完成真实投放状态下的锁屏覆盖或 20 次灭屏/亮屏循环验收。普通锁屏恢复、真实 WebView、沉浸式系统栏、`KEEP_SCREEN_ON`、屏幕旋转和 OEM 安全策略仍需在不改动设备管理状态的测试设备上现场验证。
