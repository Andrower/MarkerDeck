# MD-A20 Android 正式发布签名

## 目标

将 GitHub Release 的 Android 产物从 CI 临时 debug 证书切换为 MarkerDeck 长期固定的 release 证书，使首个正式签名版本之后的 APK 可以直接覆盖升级。

## 问题与范围

现有发布工作流执行 `assembleDebug` 并上传 `app-debug.apk`。GitHub runner 的 debug keystore 不是长期发布身份，不保证不同 Release 使用同一私钥。本任务配置 Gradle release signing、GitHub Secrets 恢复、签名与版本验证、正式产物命名和密钥清理。

不提交 keystore 或密码，不改变应用包名、网络协议、网页行为、Android 功能或桌面签名。Google Play App Signing、Windows Authenticode 与 macOS Developer ID 不在本任务范围。

## 签名与安全边界

- keystore、store password、alias 和 key password 分别通过四个 GitHub Actions Secrets 提供。
- 仓库忽略 `*.jks` 和 `*.keystore`；工作流只在 runner 临时目录恢复 keystore，并在 Android job 结束时删除。
- tag 发布必须存在固定的 `ANDROID_RELEASE_CERT_SHA256` 仓库变量，并与 APK 实际证书指纹一致。
- 手工 workflow dispatch 允许首次 bootstrap 输出公开证书指纹；指纹固定后再次运行验证。
- APK 必须通过 `apksigner verify`，且 Manifest `versionName` 必须与 Release 版本一致。

## 升级边界

v1.7.0 及更早发布物使用无法继续复用的 debug 私钥，因此首个正式签名 APK 不能覆盖安装这些版本。用户需卸载一次，应用本地数据会被清除；从首个正式签名版本开始，只要 release keystore 不变即可覆盖升级。

## 验收标准

- 没有四个签名环境变量时，release Gradle 任务明确失败；debug 构建保持可用。
- 手工发布工作流能构建正式签名 APK，并输出证书 SHA-256。
- 固定指纹后，同一工作流再次通过；错误指纹或缺少 tag 指纹时发布失败。
- 下载后的 APK 通过签名、包名、versionName/versionCode 检查。
- 同一证书签名的后续更高 versionCode APK 可用 `adb install -r` 覆盖并保留数据。

## 验证计划

- 本地 `testDebugUnitTest`、`lintDebug` 与 `assembleDebug` 已通过，确认开发流程不依赖 release Secrets。
- 本地无签名环境执行 `assembleRelease` 已按预期失败并显示四个必需变量，未生成可发布产物。
- GitHub Actions 手工 bootstrap release 构建并读取公开证书指纹。
- 固定 `ANDROID_RELEASE_CERT_SHA256` 后重复手工构建。
- Android 真机完成首次正式安装及同证书递增 versionCode 覆盖升级测试。
