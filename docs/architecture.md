# 项目结构

```text
MarkerDeck/
├── src/                    共享服务端和网页源码
│   ├── markerdeck-server.js
│   └── web/
├── desktop/electron/       Windows 桌面客户端
├── platform/               Mac 和 Windows 启动、运行时准备脚本
├── scripts/                检查与打包脚本
├── tests/                  服务端自动测试
├── docs/                   使用、构建和回滚说明
├── runtime/                本地 Node.js、FFmpeg，不提交 Git
└── artifacts/              生成的 ZIP，不提交 Git
```

## 边界

- `src` 是唯一业务源码，不为不同系统复制页面或服务端。
- 服务端通过 SSE 向控制端和投放端推送设备、状态、命令及 ACK 事件，HTTP API 负责写操作和断线恢复。
- `deviceId` 标识物理设备，`sessionId` 标识独立接收页面；控制目标使用 `sessionId`。
- `platform` 只处理系统启动差异。
- `desktop/electron` 只负责桌面窗口、kiosk 和按键拦截。
- `runtime` 可以在打包前准备，不能提交大型二进制文件。
- `artifacts` 只保存生成结果，正式文件上传到 GitHub Releases。
- 用户预设写入 `.data` 或运行包的 `data`，不会写回源码目录。

## 发布流程

推送 `v*` 标签会触发 `.github/workflows/release.yml`：

1. 组装 macOS arm64 ZIP。
2. 组装 Windows x64 ZIP。
3. 构建并校验 Windows 桌面客户端完整目录 ZIP。
4. 创建或更新对应 GitHub Release。

手动运行工作流时可以只生成 Actions 附件，不创建 Release。

## v1.3.0 重命名与迁移兼容

MarkerDeck 使用 `MARKERDECK_*` 环境变量、`markerdeck-*` 数据文件和 `markerdeck*` 浏览器存储键。旧版 Chroma Cross 的配置仍可迁移：

- 服务端在新文件不存在时回退读取 `CHROMA_DATA_DIR`、`CHROMA_PRESETS_FILE`、`chroma-settings.json` 和 `chroma-presets.json`。
- 页面读取 `chromaCross*` 存储键时会将值复制到对应的 `markerdeck*` 键。
- `/chroma-launch.html` 和 `/chroma-cross-screen.html` 保留 302 重定向，并保留原查询参数。
- 通过 MarkerDeck 保存设置或预设后，数据会写入新的 `markerdeck-*` 文件；旧文件不会被自动删除。
