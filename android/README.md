# MarkerDeck Android

这是 MD-A01 的 Android 工程骨架。当前版本只有一个原生 `Activity` 和品牌占位画面，用于验证 Kotlin、Android SDK、Gradle、单元测试和 CI 链路。

本任务不包含 WebView、服务地址设置页、连接/投放逻辑、Kiosk、Lock Task、Foreground Service、Node.js 或 FFmpeg。它还不是正式 Android 客户端 Release。

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

纯 Kotlin 单元测试只验证服务地址解析和投放 URL 构造，不启动网络请求或 WebView。根目录的 `npm run check` 只检查 Android 关键文件是否存在，不会在没有 Android SDK 的环境中强制运行 Gradle。
