# Windows 使用说明

## 免安装包

1. 解压 Windows ZIP。
2. 双击 `start-chroma-server.bat`。
3. 保持命令窗口运行。

免安装包包含 `runtime\node-windows\node.exe`。视频版还包含 `runtime\ffmpeg-windows\ffmpeg.exe`，不需要另外安装依赖。

## 桌面客户端

Electron 桌面客户端支持全屏、置顶和更强的按键拦截。锁定期间会拦截常见的退出、刷新和导航快捷键，默认解锁组合键为 `Ctrl + Alt + Shift + L`。

Windows 键和 `Ctrl + Alt + Delete` 等系统级操作无法由普通应用完全屏蔽。严格封闭环境需要使用 Windows Assigned Access、组策略或系统级 kiosk 配置。

## 源码运行

在仓库根目录执行：

```bat
platform\windows\start-chroma-server.bat
```

构建桌面客户端：

```bat
cd desktop\electron
pnpm install --frozen-lockfile
pnpm run build:win
```
