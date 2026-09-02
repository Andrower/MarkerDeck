# MarkerDeck Android

这是 MD-A02 的普通 Android 投放端 MVP。它是一个单 Activity 原生薄壳：设置页保存用户选择的 MarkerDeck 服务地址、设备名和明确的普通投放模式，连接后在 WebView 中加载投放页面。

本任务只实现普通投放模式。它不包含 Kiosk、Lock Task、Device Owner/DPC、Foreground Service、自动恢复、Node.js、FFmpeg 或局域网扫描，也不提供公网回退或遥测。普通模式不代表专用设备能力。

## 固定版本

- Android Gradle Plugin：`9.0.1`
- Gradle Wrapper：`9.1.0`，使用 `gradle-9.1.0-bin.zip`
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

纯 Kotlin 单元测试验证服务地址解析、IPv6、投放 URL 编码和设置纯逻辑，不启动网络请求或 WebView。根目录的 `npm run check` 同时执行项目结构、JavaScript/HTML 语法和服务端集成检查。

仓库检查命令：

```bash
cd /Users/andrower/Desktop/OU/MarkerDeck
npm run check
git diff --check
```

## A02 行为

- 首次打开只显示设置页，不自动连接、不扫描局域网，也不进行任何 WebView 网络访问。
- 用户填写服务地址和设备名并点击“连接并开始投放”后，地址才会验证、写入 DataStore，并加载 `/markerdeck-screen.html?mode=display`。
- 地址只接受 `http://` 或 `https://`，必须有主机名或 IP，端口必须为 `1..65535`；路径、查询和片段会被丢弃并归一化为 origin。IPv6 使用方括号保持正确 URL 结构。
- 设备名会限制为 40 个字符并作为编码后的 `androidDeviceName` 查询参数传给页面。页面将它写入既有 `localStorage`，因此 Android 不弹出网页设备名对话框；普通浏览器未提供该参数时仍保留原有对话框。
- 配置重启后恢复，但应用不会自动进入投放；必须再次点击连接。模式会显式保存为 `display`，A02 只显示普通投放可用。
- WebView 仅开启 JavaScript 和 DOM Storage，关闭文件/内容访问，不使用原生 JavaScript bridge。HTTP 局域网连接由 Manifest 的 `INTERNET` 权限和 cleartext 配置支持。
- 投放期间 WebView 顶层导航只允许已选择服务的同源地址；跨源或无效跳转会停止加载并显示错误。`about:blank` 只用于返回设置时清理页面；同源路径、API、SSE 和子资源请求不受此限制。
- 投放页加载时显示加载状态；主框架服务不可达、HTTP 错误或 HTTPS 证书错误显示错误和“重试”。子资源失败不会把整个页面标为失败。
- 投放期间启用沉浸式全屏和原生 `KEEP_SCREEN_ON`。系统返回键、“返回设置”都会停止当前页面、清理全屏/常亮状态并回到设置页。
- A02 不实现进程异常、灭屏/解锁后的高级恢复；这些属于 MD-A03。

## 手工检查清单

在有 Android 设备或模拟器时，安装 `app-debug.apk` 后检查：

- 首次启动没有连接提示、网络访问或网页设备名对话框；设置页字段可见且不重叠。
- 输入空值、非 HTTP(S)、无主机、端口 `0`、端口 `65536`，确认错误可见且不会进入 WebView。
- 输入可访问的 `http://` MarkerDeck 服务、设备名，点击连接，确认页面进入全屏、保持常亮、服务端设备列表显示该名称。
- 确认含空格、符号或非 ASCII 字符的设备名能正常显示，并且页面没有弹出设备名对话框。
- 停止服务后点击连接或重试，确认主页面显示“服务不可达或投放页面加载失败”或 HTTP 错误及“重试”。恢复服务后点击重试。
- 在显示页按系统返回键，确认回到设置页、系统栏恢复、屏幕不再被原生常亮标志保持；再次连接仍需显式点击。
- 退出并重新启动应用，确认地址、设备名和普通投放模式恢复，但不会自动连接。
- 在常见竖屏、横屏和较小手机尺寸检查输入、状态面板和操作按钮不重叠。

本仓库环境可验证构建、静态检查、纯单元测试和本地服务端 HTTP/HTML 兼容性；这不等于完整的 v1.3.0 Android/WebView 兼容性验证。没有连接 Android 设备时，无法声称已验证真实 WebView、沉浸式系统栏、`KEEP_SCREEN_ON`、屏幕旋转或物理常亮效果。
