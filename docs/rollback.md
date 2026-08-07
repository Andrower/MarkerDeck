# 版本与回滚

源码、平台启动脚本和打包脚本保存在 Git 仓库中。生成的运行包统一上传到 GitHub Releases。

以下大文件和生成目录不会进入普通 Git 历史：

- `node_modules`
- Electron `dist`
- `.zip` archives
- generated `.exe` files
- bundled Node runtimes
- `runtime` 中的 Node.js 与 FFmpeg
- `artifacts` 中的发布包

查看提交和标签：

```bash
git log --oneline --decorate
git tag
git switch --detach <commit-or-tag>
```

需要从旧版本建立修复分支时：

```bash
git switch -c fix/from-v1.1.0 v1.1.0
```

发布版本应使用 `v主版本.次版本.修订版本` 标签，例如 `v1.2.0`。预发布版本可以使用 `v1.2.0-beta.1`。
