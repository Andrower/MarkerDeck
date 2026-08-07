# Chroma Cross

Chroma Cross 是一套用于影视拍摄与后期跟踪的局域网投放工具。它可以在手机、平板或电脑上显示纯色背景与可调十字跟踪点，并允许控制端统一管理多台投放设备。

## 主要功能

- 绿色、蓝色和浅灰色背景预设
- 背景颜色、明度、十字颜色、尺寸、粗细与位置调整
- 隐藏十字，切换为纯色画面
- 添加间距受控且互不重叠的随机跟踪点
- 控制端查看设备状态、重命名、分组和批量修改
- 保存和删除自定义预设
- 锁定投放、快捷键解锁和停止本地服务
- 按自定义分辨率导出 PNG
- 视频预览版支持按自定义时长导出 H.264 MP4
- 批量导出可选择 ZIP 压缩包或多个独立文件

## 下载

| 版本 | 系统 | 说明 | 下载 |
| --- | --- | --- | --- |
| v1.1.0 稳定版 | Windows x64 | 免安装 EXE，内置 Node.js | [下载 Windows 版](https://github.com/Andrower/chroma-cross-platform/releases/download/v1.1.0/Chroma.Cross.Client.1.1.0.exe) |
| v1.1.0 稳定版 | macOS Apple Silicon | 适用于 arm64 机型 | [下载 Mac 版](https://github.com/Andrower/chroma-cross-platform/releases/download/v1.1.0/chroma-cross-macos-arm64-v1.1.0.zip) |
| v1.2.0-video 预发布版 | Windows x64 | 内置 Node.js 与 FFmpeg，可离线导出 MP4 | [下载 Windows 视频版](https://github.com/Andrower/chroma-cross-platform/releases/download/v1.2.0-video/chroma-cross-video-windows-x64-v1.2.0.zip) |
| v1.2.0-video 预发布版 | macOS Apple Silicon | 内置 Node.js，MP4 导出需要系统 FFmpeg | [下载 Mac 视频版](https://github.com/Andrower/chroma-cross-platform/releases/download/v1.2.0-video/chroma-cross-video-macos-arm64-v1.2.0.zip) |

全部历史版本可在 [GitHub Releases](https://github.com/Andrower/chroma-cross-platform/releases) 中查看和回滚。

## 快速开始

### Windows

1. 稳定版直接双击 `Chroma.Cross.Client.1.1.0.exe`。
2. ZIP 版本解压后双击 `start-chroma-server.bat`。
3. 保持弹出的服务窗口运行。

### macOS

1. 解压下载的 ZIP。
2. 双击 `mac/start-chroma-server.command`。
3. 首次启动如遇系统安全提示，请右键该文件并选择“打开”。
4. 保持终端窗口运行。

## 连接设备

1. 电脑与手机连接到同一个局域网或同一个手机热点。
2. 启动程序后，电脑会打开启动页。
3. 手机扫描启动页中的二维码。
4. 电脑进入“控制端”，需要投放画面的手机进入“被控端”。
5. 在控制端选择一个或多个设备，再修改画面、预设、分组或锁定状态。

默认电脑入口为：

```text
http://localhost:8765/chroma-launch.html
```

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
- 默认解锁组合键为 `Ctrl + Alt + Shift + L`。

## 网络与隐私

- 控制服务默认只在本机和当前局域网中运行。
- 设备状态、控制指令和预设不需要上传到第三方云服务。
- 请不要把服务端口直接暴露到公网。

## 开发与构建

项目主要由原生 HTML/CSS/JavaScript、Node.js 本地服务和 Electron Windows 客户端组成。

- 推荐 Node.js 24
- Windows 客户端使用 Electron Builder 构建便携式 EXE
- MP4 导出由 FFmpeg 完成
- 生成的 ZIP、EXE、Node.js 和 FFmpeg 运行时不提交到普通 Git 历史，而是放入 GitHub Releases

常用命令：

```bash
npm start
npm run check
npm run package:mac
```

更多说明：

- [项目结构](docs/architecture.md)
- [macOS 使用说明](docs/macos.md)
- [Windows 使用说明](docs/windows.md)
- [Electron 桌面客户端](docs/desktop-client.md)
- [视频导出](docs/video-export.md)
- [版本与回滚](docs/rollback.md)

## 许可

项目源码使用 [MIT License](LICENSE)。FFmpeg 及其第三方构建使用各自的许可证；分发包含 FFmpeg 的运行包时必须保留对应许可文件。
