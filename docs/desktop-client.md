# MarkerDeck Electron 桌面客户端

桌面客户端位于 `desktop/electron`，用于生成 Windows x64 完整目录 ZIP。用户只需解压一次，之后可以直接运行目录中的 EXE，不会在每次启动时重新释放 Electron 运行时。

## 锁定能力

- 无边框全屏和 kiosk 模式
- 投放窗口保持置顶
- 锁定时拦截 `Alt+F4`、`Esc`、`F11`、`Enter`、`Space`、`Ctrl+W`、`Ctrl+R` 等按键
- 使用 `Ctrl + Alt + Shift + L` 广播切换当前服务内全部在线投放端的锁定状态

组合键通过服务端广播，不依赖每个页面是否获得键盘焦点。同一台设备打开多个投放页面时，每个页面会独立读取广播命令。

操作系统保留按键不在普通 Electron 应用的完整控制范围内，详细限制见 [Windows 使用说明](windows.md)。

## 构建

```bat
cd desktop\electron
pnpm install --frozen-lockfile
pnpm run build:win
```

构建过程会准备 FFmpeg，并把统一的 `src/markerdeck-server.js`、`src/web/` 下的启动页、screen HTML、CSS 和经典外部脚本整体复制到桌面客户端资源目录。发布前会检查 ZIP 内的 `ffmpeg.dll`、`ffmpeg.exe` 和 `node.exe`。

解压后必须保留完整目录，不能只复制主 EXE。Electron 所需的 DLL 和应用资源都位于同一目录中。
