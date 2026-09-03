# MarkerDeck

MarkerDeck 是一套用于影视拍摄与后期跟踪的局域网多屏纯色/跟踪点投放控制工具。它可以在手机、平板或电脑上显示纯色背景与可调十字跟踪点，并允许控制端统一管理多台投放设备。

## 主要功能

- 绿色、蓝色和浅灰色背景预设
- 背景颜色、总体亮度、十字颜色、尺寸、粗细与位置调整
- 隐藏十字，切换为纯色画面
- 添加间距受控且互不重叠的随机跟踪点
- 控制端查看设备状态、重命名、分组、批量修改和清理离线设备
- 同一物理设备的多个接收页面默认折叠显示，可整体或展开后独立控制
- SSE 实时状态推送及逐接收页面的锁定命令确认
- 保存和删除自定义预设
- 锁定投放、快捷键解锁和停止本地服务
- 按自定义分辨率导出 PNG
- 视频预览版支持按自定义时长导出 H.264 MP4
- 批量导出可选择 ZIP 压缩包或多个独立文件

## 下载

| 版本 | 系统 | 说明 | 下载 |
| --- | --- | --- | --- |
| 最新版 | Windows x64 桌面客户端 | 解压一次后运行，内置 Node.js 与 FFmpeg | [下载 Windows 版](https://github.com/Andrower/MarkerDeck/releases/latest) |
| 最新版 | Windows x64 浏览器服务端 | 解压后运行批处理文件，内置 Node.js 与 FFmpeg | [下载 Windows 服务端](https://github.com/Andrower/MarkerDeck/releases/latest) |
| 最新版 | macOS Apple Silicon | 解压后运行，适用于 arm64 机型 | [下载 Mac 版](https://github.com/Andrower/MarkerDeck/releases/latest) |

全部历史版本可在 [GitHub Releases](https://github.com/Andrower/MarkerDeck/releases) 中查看和回滚。

## 快速开始

### Windows

1. 下载 `MarkerDeck.Client.<版本号>.zip` 并完整解压。
2. 双击解压目录中的 `MarkerDeck.exe`。
3. 不要把 EXE 单独移出目录；`ffmpeg.dll`、Node.js 和 FFmpeg 必须和程序目录一起保留。

浏览器服务端包使用 `markerdeck-windows-x64-v<版本号>.zip`，解压后双击 `start-markerdeck-server.bat`。

### macOS

1. 解压下载的 ZIP。
2. 双击 `start-markerdeck-server.command`。
3. 首次启动如遇系统安全提示，请右键该文件并选择“打开”。
4. 保持终端窗口运行。

## 连接设备

1. 电脑与手机连接到同一个局域网或同一个手机热点。
2. 启动程序后，电脑会打开启动页。
3. 手机扫描启动页中的二维码。
4. 电脑进入“控制端”，需要投放画面的手机进入“被控端”。
5. 在控制端选择一个或多个设备，再修改画面、预设、分组或锁定状态。

电脑启动页会自动扫描同一局域网内由手机或其他电脑提供的 MarkerDeck 宿主，并在“局域网宿主”区域显示通过 UDP 发现和 HTTP nonce 握手验证的控制地址。点击宿主即可进入它的控制端；“刷新”会重新执行一次短时扫描。防火墙需要允许 MarkerDeck 使用 UDP `8766` 和对应宿主的 HTTP 端口。

默认电脑入口为：

```text
http://localhost:8765/markerdeck-launch.html
```

正式页面名为 `markerdeck-launch.html`（启动页）和 `markerdeck-screen.html`（控制端与投放端页面）。

`markerdeck-screen.html` 只保留页面结构和按顺序加载的经典脚本引用，网页资产位于同一目录，支持 HTTP 服务和 `file://` 直接打开：

- `markerdeck-base.css`：基础页面、投放画布、通用控件、导出工具和启动页样式。
- `markerdeck-control.css`：控制端工作区、设备列表、分组和设备管理样式。
- `markerdeck-mobile.css`：移动响应式规则、对话框和导出进度样式；三个 CSS 按此处列出的顺序加载，以保持原有级联结果。
- `markerdeck-visual-state.js`：总体亮度默认值、状态 canonicalization、legacy RGB 折色与颜色计算。
- `markerdeck-core.js`：DOM 引用、共享状态、存储兼容和状态读写。
- `markerdeck-api.js`：HTTP、SSE、视频服务和设备协议调用。
- `markerdeck-canvas.js`：画布尺寸、确定性绘制和设备缩略图。
- `markerdeck-export.js`：PNG、ZIP、MP4 和文件保存流程。
- `markerdeck-presets.js`：内置/自定义预设及移动端预设选择。
- `markerdeck-devices.js`：设备列表、分组、选择和控制端设备设置。
- `markerdeck-projection.js`：本地/投放/控制同步、锁定、三击退出和唤醒锁。
- `markerdeck-settings.js`：画面参数按钮与设置变化协调。
- `markerdeck-launcher.js`：启动页、服务信息和角色入口。
- `markerdeck-bootstrap.js`：唯一装配入口，不持有业务状态。

脚本使用 `window.MarkerDeck` 下的显式模块入口，不使用 bundler 或 ES module；Android 未来若需要离线复用网页资产，应整体复制这组同名 CSS/JS/HTML 文件并保留引用顺序，当前 APK 仍从用户选择的 MarkerDeck 服务地址加载投放页。

手机必须使用启动页显示的局域网 IP 地址，不能使用 `localhost` 或 `127.0.0.1`。

## 导出图片与视频

- PNG 和 MP4 都使用界面中填写的最终像素尺寸，不受浏览器预览大小影响。
- 当前画面和全部预设都可以批量导出。
- MP4 时长范围为 0.1 到 3600 秒。
- Windows 视频版已经包含 FFmpeg。
- macOS 视频版会依次查找捆绑 FFmpeg、Homebrew FFmpeg 和系统 `PATH` 中的 FFmpeg。

## 锁定说明

网页和桌面客户端可以拦截常见的退出、刷新、返回与全屏快捷键，但普通应用无法完全屏蔽操作系统保留按键。

- Windows 键、`Ctrl + Alt + Delete`、电源键等系统级操作不能由普通网页完全禁用。
- 需要严格封闭的拍摄设备应配合 Windows Assigned Access、组策略或系统级 kiosk 模式。
- `Ctrl + Alt + Shift + L` 会在当前服务内广播锁定或解锁全部在线投放端；同一设备打开多个投放页面时也会分别收到。
- 控制面板中的远程锁定按钮仍只作用于当前选中的设备或分组。

## 网络与隐私

- 控制服务默认只在本机和当前局域网中运行。
- 设备状态、控制指令和预设不需要上传到第三方云服务。
- 请不要把服务端口直接暴露到公网。

## 旧版本兼容

v1.3.0 保留旧版 Chroma Cross 的迁移兼容：旧的 `/chroma-launch.html` 和 `/chroma-cross-screen.html` URL 会返回 302，并保留查询参数；服务端在新数据文件不存在时会回退读取 `CHROMA_*` 环境变量以及 `chroma-settings.json`、`chroma-presets.json`。页面读取旧的 `chromaCross*` 浏览器存储键后会写入新的 `markerdeck*` 键。之后通过 MarkerDeck 保存设置或预设时，会使用新的 `markerdeck-*` 文件名。

## 开发与构建

项目主要由原生 HTML/CSS/JavaScript、Node.js 本地服务和 Electron Windows 客户端组成；`android/` 是独立的 Android-first 工程。

- 推荐 Node.js 24
- Windows 客户端使用 Electron Builder 构建完整目录 ZIP
- MP4 导出由 FFmpeg 完成
- Android 已开始 MD-A09 Host MVP：设置页提供本地投放、连接局域网宿主、本机作为宿主三入口；内置宿主复用 `src/web` assets，提供控制页、LAN 地址/二维码、状态/SSE/锁命令闭环，并通过 `connectedDevice` 前台服务在 Activity 离开后继续运行。MD-A11 增加仅作用于投放 Activity 的 100% 窗口亮度、总体亮度、legacy RGB 折色和 SSE 暂断时的注册心跳锁定回退。Android 不提供 FFmpeg/MP4 导出，桌面 Node 服务继续保持视频能力。MD-A03 的普通投放生命周期恢复、真实设备、1 秒内恢复、连续 20 次循环和 OEM 差异验证仍待完成；尚不是正式 Android 客户端 Release
- 生成的 ZIP、Node.js 和 FFmpeg 运行时不提交到普通 Git 历史，而是放入 GitHub Releases

常用命令：

```bash
npm start
npm run check
npm run package:mac
```

Android 工程使用独立的 Gradle Wrapper；需要 JDK 17 或更高版本，当前本机验证使用 Android Studio JBR 21：

```bash
cd android
./gradlew testDebugUnitTest lintDebug assembleDebug
```

完整的 JDK、Android SDK、版本矩阵和输出路径见 [Android 开发与构建](android/README.md)。

macOS 发布包名为 `markerdeck-macos-arm64-v<版本号>.zip`，Windows 浏览器服务端发布包名为 `markerdeck-windows-x64-v<版本号>.zip`。

更多说明：

- [项目结构](docs/architecture.md)
- [macOS 使用说明](docs/macos.md)
- [Windows 使用说明](docs/windows.md)
- [Electron 桌面客户端](docs/desktop-client.md)
- [视频导出](docs/video-export.md)
- [版本与回滚](docs/rollback.md)
- [改进路线](docs/improvement-roadmap.md)
- [Android 开发与构建](android/README.md)

## 许可

项目源码使用 [MIT License](LICENSE)。FFmpeg 及其第三方构建使用各自的许可证；分发包含 FFmpeg 的运行包时必须保留对应许可文件。
