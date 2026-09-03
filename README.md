# MarkerDeck

面向影视现场的局域网多屏纯色与跟踪点投放控制工具。

[![最新 Release](https://img.shields.io/github/v/release/Andrower/MarkerDeck?display_name=tag&sort=semver)](https://github.com/Andrower/MarkerDeck/releases/latest)
[![Source CI](https://github.com/Andrower/MarkerDeck/actions/workflows/check.yml/badge.svg?branch=main)](https://github.com/Andrower/MarkerDeck/actions/workflows/check.yml)
[![Android CI](https://github.com/Andrower/MarkerDeck/actions/workflows/android-check.yml/badge.svg?branch=main)](https://github.com/Andrower/MarkerDeck/actions/workflows/android-check.yml)
[![License](https://img.shields.io/github/license/Andrower/MarkerDeck)](LICENSE)

## 下载

前往 [最新 Release](https://github.com/Andrower/MarkerDeck/releases/latest)，在 Assets 中选择对应平台的文件：

| 平台与入口 | Release 文件名 | 说明 |
| --- | --- | --- |
| [Windows 桌面客户端 ZIP](https://github.com/Andrower/MarkerDeck/releases/latest) | `MarkerDeck.Client.<版本号>.zip` | Electron 全屏控制端；必须完整解压到文件夹后运行 `MarkerDeck.exe`，不要只移动 EXE。 |
| [Windows 浏览器服务端 ZIP](https://github.com/Andrower/MarkerDeck/releases/latest) | `markerdeck-windows-x64-v<版本号>.zip` | 解压后运行 `start-markerdeck-server.bat`，用浏览器打开控制端。 |
| [macOS ARM64 ZIP](https://github.com/Andrower/MarkerDeck/releases/latest) | `markerdeck-macos-arm64-v<版本号>.zip` | 适用于 Apple Silicon；解压后运行 `start-markerdeck-server.command`。 |
| [Android APK](https://github.com/Andrower/MarkerDeck/releases/latest) | `markerdeck-android-debug-v<版本号>.apk` | 当前为 debug 签名 APK，适合测试和现场试用。 |

Windows 桌面客户端 ZIP 内含 Electron、Node.js 和 FFmpeg 运行时，完整解压后才能正常启动。Android 当前 APK 使用 debug 签名，不代表正式商店发布包。历史版本见 [GitHub Releases](https://github.com/Andrower/MarkerDeck/releases)。

## 核心场景

MarkerDeck 适合需要让多台手机、平板或电脑同步显示纯色背景和跟踪标记的拍摄现场。控制端在同一局域网内管理投放端，修改画面后实时同步到选中的页面。

主要能力：

- 绿色、蓝色、浅灰色背景，以及自定义背景色和十字颜色
- 总体亮度、十字大小、线条粗细、边距、中心高度和随机点数量调整
- 纯色待机、隐藏十字、随机跟踪点和自定义预设
- 查看投放端在线状态、画面缩略图、分组、重命名和批量控制
- 同一物理设备的多个接收页面折叠管理，也可展开后独立选择
- 远程锁定、快捷键解锁、PNG 导出和本地 FFmpeg MP4 导出

## 快速开始

1. 下载对应平台的 ZIP 或 Android APK；Windows 桌面 ZIP 必须完整解压。
2. 启动服务：Windows 浏览器包运行 `start-markerdeck-server.bat`，macOS 运行 `start-markerdeck-server.command`；桌面客户端直接运行 `MarkerDeck.exe`。
3. 在电脑打开 `http://localhost:8765/markerdeck-launch.html`，或使用启动页显示的局域网地址。
4. 让手机、平板或另一台电脑连接同一局域网或手机热点，打开启动页地址，选择“作为投放端”。
5. 回到控制端选择设备，调整画面、应用预设或锁定投放。

手机和平板必须使用启动页显示的局域网地址，不能把 `localhost` 或 `127.0.0.1` 作为远程设备地址。端口被占用时，macOS 启动脚本会选择后续可用端口；防火墙需要允许服务使用 HTTP 端口和 UDP `8766`。

## 真实界面预览

控制端工作区由三部分组成：快速连接显示二维码和地址，设备管理显示在线投放端与缩略图，画面设置集中处理预设、颜色、亮度、点位和导出。页面使用当前仓库的真实 HTML/CSS/JavaScript 控制端，不依赖第三方云服务。

![MarkerDeck 控制端概览](docs/assets/markerdeck-control-overview.png)

图中设备名称和连接地址均为脱敏的演示数据。

## 平台能力

| 能力 | Windows 桌面客户端 | Windows 浏览器服务端 | macOS ARM64 | Android APK |
| --- | --- | --- | --- | --- |
| 控制端与投放端 | 支持 | 支持 | 支持 | 支持 |
| 局域网发现 | 支持 | 支持 | 支持 | 作为宿主时支持 |
| PNG 导出 | 支持 | 支持 | 支持 | 宿主控制页支持 |
| MP4 导出 | 支持，内置 FFmpeg | 支持，内置 FFmpeg | 支持，按本机 FFmpeg 可用性 | 不支持 |
| 桌面全屏、置顶和按键拦截 | 支持 | 由浏览器决定 | 由浏览器决定 | 使用 Android 投放窗口 |
| Android 前台宿主 | 不适用 | 不适用 | 不适用 | 支持 |

## 网络与锁定边界

- 控制服务默认只在本机和当前局域网中运行；设备状态、控制指令和预设不需要上传到第三方云服务。不要把服务端口直接暴露到公网。
- 局域网发现使用 UDP `8766`，发现到的地址还会通过 nonce 作用域的 HTTP 握手验证。电脑与投放设备应连接同一个局域网或手机热点。
- `localhost` 只表示当前设备。手机连接电脑服务时，应使用启动页给出的局域网 IP 或二维码地址，不能使用电脑上的 `localhost`。
- 网页和桌面客户端可以拦截常见的退出、刷新、返回和全屏快捷键，但普通应用无法完全拦截操作系统保留按键。
- Windows 键、`Ctrl + Alt + Delete`、电源键等系统级操作不能由普通网页或应用完全禁用；严格封闭的拍摄设备需要配合 Windows Assigned Access、组策略或系统级 kiosk 模式。
- `Ctrl + Alt + Shift + L` 会在当前服务内广播锁定或解锁全部在线投放端；控制面板的远程锁定按钮只作用于选中的设备或分组。
- MP4 导出由本地 FFmpeg 完成。Windows 发布包内含 FFmpeg；macOS 会依次查找捆绑 FFmpeg、Homebrew 和系统 `PATH`。

## 开发快速开始

项目使用原生 HTML/CSS/JavaScript、Node.js 本地服务、Electron Windows 客户端和独立 Android 工程。推荐 Node.js 24。

```bash
npm start
npm test
node scripts/check-project.js
```

Android 工程需要 JDK 17 或更高版本：

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

页面和服务端共享 `src/web`，发布 ZIP、Node.js 和 FFmpeg 运行时不提交到普通 Git 历史，而是由 GitHub Actions 组装并上传到 Release。完整的目录结构、资源加载顺序和发布流程见 [项目结构与架构](docs/architecture.md)。

## 文档索引

- [项目结构与架构](docs/architecture.md)
- [Windows 使用说明](docs/windows.md)
- [macOS 使用说明](docs/macos.md)
- [Electron 桌面客户端](docs/desktop-client.md)
- [视频导出](docs/video-export.md)
- [版本与回滚](docs/rollback.md)
- [Android 开发与构建](android/README.md)
- [改进路线](docs/improvement-roadmap.md)
- [任务记录索引](docs/tasks/README.md)

## 许可

项目源码使用 [MIT License](LICENSE)。FFmpeg 及其第三方构建使用各自的许可证；分发包含 FFmpeg 的运行包时必须保留对应许可文件。
