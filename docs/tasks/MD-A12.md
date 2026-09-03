# MD-A12：仓库主页与发布入口整理

## 目标

面向实际使用者重写 GitHub 仓库主页，先说明能解决的现场问题、如何下载和如何启动，再提供必要的网络边界、开发入口与文档索引。发布入口只指向现有 GitHub Release，不修改仓库 About/Topics，不创建或发布新的 Release。

## 子项

- `MD-A12-01 README 使用者首屏`：补充 MarkerDeck 定位、GitHub badges、四个平台下载入口、核心场景、主要能力和 3-5 步快速开始。
- `MD-A12-02 README 能力与边界`：加入真实界面预览说明、平台能力矩阵、网络与锁定边界，并把模块级源码说明收敛到 `docs/architecture.md`。
- `MD-A12-03 任务与变更记录`：新增本任务记录，更新任务索引和 `CHANGELOG.md` 的 Unreleased 记录。
- `MD-A12-04 资产与验收`：尝试使用当前页面和临时演示投放端生成产品截图；检查文档链接、badges、Release 入口、Node 项目和差异格式。

## 范围与非目标

范围包括根目录 README、任务文档、CHANGELOG 和 README 使用的产品截图资产（仅在真实浏览器截图成功时加入）。

非目标包括修改应用功能、修改 GitHub 仓库 About/Topics、调整 Release 工作流、创建或发布 Release、提交临时数据/服务日志/测试脚本，以及使用历史 PAT。

## 验收标准

- README 首屏包含 `MarkerDeck`、一句中文定位、最新 Release、Source CI、Android CI、License badges 和清晰下载入口。
- 下载区明确区分 Windows 桌面客户端 ZIP、Windows 浏览器服务端 ZIP、macOS ARM64 ZIP 和 Android APK；每项可进入 `releases/latest`，并说明桌面 ZIP 必须完整解压、Android 当前为 debug 签名。
- README 前半部分依次覆盖核心场景/主要能力、3-5 步快速开始、真实界面预览和平台能力矩阵；后半部分覆盖网络与锁定边界、开发快速开始和文档索引。
- README 不再展开逐模块源码说明，但仍通过 `docs/architecture.md` 提供项目结构、加载顺序和发布流程；localhost/LAN、UDP `8766`、FFmpeg 和系统键无法完全拦截等边界不丢失。
- 真实截图只能来自当前本地服务页面和明确标为演示的临时投放端，不得包含用户私人网络地址或设备名；截图失败时 README 不引用缺失图片。
- `docs/tasks/README.md` 有 MD-A12 索引，CHANGELOG Unreleased 有对应中文记录，且本任务有对应 changelog。

## 变更

- 重写根目录 `README.md`，将下载入口、使用场景、快速开始、界面说明、平台能力、网络锁定边界、开发入口和文档索引按使用者阅读顺序组织。
- 下载区链接到 GitHub `releases/latest`，并按现有 Release 工作流中的资产命名说明四个平台入口。
- 更新 `docs/tasks/README.md`，加入 MD-A12 与 MD-A11 的任务索引。
- 新增本文件，记录任务边界、验收与验证命令。
- 在 `CHANGELOG.md` 的 Unreleased 下新增一条 MD-A12 中文记录。
- 产品截图仅在真实 Playwright 截图成功后加入 `docs/assets/markerdeck-control-overview.png`；若环境失败则不提交截图，也不在 README 留下缺失图片引用。

## 验证

已执行：

```bash
npm test
node scripts/check-project.js
git diff --check
```

- `npm test`：31 个测试全部通过。
- `node scripts/check-project.js`：通过。
- `git diff --check`：通过。
- README 相对链接、badges 的 workflow 路径/名称和四个 `releases/latest` 入口：通过静态检查。
- 使用本地临时服务和 4 个明确标为“演示”的投放端完成一次 Playwright 截图；宽屏控制端三列无布局重叠，图片为 1600 × 1000 PNG，176626 字节，并通过本地图片查看和二进制签名检查。

## 剩余现场验证

- GitHub 上的 README 渲染、badge 状态和 Release Assets 实际文件名需在 PR/发布页面确认。
- GitHub 仓库 About/Topics 不属于本任务范围，由主代理验收后处理。
- 本任务不替代不同 Windows/macOS/Android 设备上的现场投放、锁定和 FFmpeg 验收。
