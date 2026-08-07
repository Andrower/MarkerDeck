# macOS 使用说明

## 免安装包

1. 解压 macOS ZIP。
2. 双击 `start-chroma-server.command`。
3. 首次运行如遇安全提示，请右键该文件并选择“打开”。
4. 保持终端窗口运行。

免安装包包含 `runtime/node-macos/node`，不需要另外安装 Node.js。

MP4 导出会先查找包内 FFmpeg，然后检查 Homebrew 和系统 `PATH`。如果没有找到 FFmpeg，PNG 导出仍可使用。

## 源码运行

在仓库根目录执行：

```zsh
./platform/macos/start-chroma-server.command
```

如果端口 `8765` 已被占用，启动脚本会自动选择后续可用端口。
