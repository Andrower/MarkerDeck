# MarkerDeck Windows 使用说明

## 免安装包

1. 解压 Windows ZIP。
2. 双击 `start-markerdeck-server.bat`。
3. 保持命令窗口运行。

免安装包包含 `runtime\node-windows\node.exe`。视频版还包含 `runtime\ffmpeg-windows\ffmpeg.exe`，不需要另外安装依赖。

## 桌面客户端

Electron 桌面客户端支持全屏、置顶和更强的按键拦截。锁定期间会拦截常见的退出、刷新和导航快捷键。`Ctrl + Alt + Shift + L` 会通过本地服务广播切换全部在线投放端的锁定状态，同一设备中的多个投放页面也能分别收到。

1. 下载 `MarkerDeck.Client.<版本号>.zip`。
2. 将 ZIP 完整解压到普通文件夹。
3. 双击 `MarkerDeck.exe`。

桌面客户端目录内包含 Electron DLL、Node.js 和 FFmpeg。不要只复制主 EXE，否则 Windows 会提示缺少 `ffmpeg.dll` 等运行库。完整目录版只在首次使用时解压，后续启动不会重复释放运行时文件。

Windows 键和 `Ctrl + Alt + Delete` 等系统级操作无法由普通应用完全屏蔽。严格封闭环境需要使用 Windows Assigned Access、组策略或系统级 kiosk 配置。

## 源码运行

在仓库根目录执行：

```bat
platform\windows\start-markerdeck-server.bat
```

构建桌面客户端：

```bat
cd desktop\electron
pnpm install --frozen-lockfile
pnpm run build:win
```
