# Electron 桌面客户端

桌面客户端位于 `desktop/electron`，用于生成 Windows x64 便携式 EXE。

## 锁定能力

- 无边框全屏和 kiosk 模式
- 投放窗口保持置顶
- 锁定时拦截 `Alt+F4`、`Esc`、`F11`、`Enter`、`Space`、`Ctrl+W`、`Ctrl+R` 等按键
- 使用 `Ctrl + Alt + Shift + L` 切换锁定状态

操作系统保留按键不在普通 Electron 应用的完整控制范围内，详细限制见 [Windows 使用说明](windows.md)。

## 构建

```bat
cd desktop\electron
pnpm install --frozen-lockfile
pnpm run build:win
```

构建过程会准备 FFmpeg，并把统一的 `src` 页面和服务端复制到 EXE 资源目录。
