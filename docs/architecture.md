# 项目结构

```text
chroma-cross-platform/
├── src/                    共享服务端和网页源码
│   ├── chroma-control-server.js
│   └── web/
├── desktop/electron/       Windows 桌面客户端
├── platform/               Mac 和 Windows 启动、运行时准备脚本
├── scripts/                检查与打包脚本
├── tests/                  服务端自动测试
├── docs/                   使用、构建和回滚说明
├── runtime/                本地 Node.js、FFmpeg，不提交 Git
└── artifacts/              生成的 ZIP、EXE，不提交 Git
```

## 边界

- `src` 是唯一业务源码，不为不同系统复制页面或服务端。
- `platform` 只处理系统启动差异。
- `desktop/electron` 只负责桌面窗口、kiosk 和按键拦截。
- `runtime` 可以在打包前准备，不能提交大型二进制文件。
- `artifacts` 只保存生成结果，正式文件上传到 GitHub Releases。
- 用户预设写入 `.data` 或运行包的 `data`，不会写回源码目录。

## 发布流程

推送 `v*` 标签会触发 `.github/workflows/release.yml`：

1. 组装 macOS arm64 ZIP。
2. 组装 Windows x64 ZIP。
3. 构建 Windows 便携式 EXE。
4. 创建或更新对应 GitHub Release。

手动运行工作流时可以只生成 Actions 附件，不创建 Release。
