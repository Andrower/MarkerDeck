# MD-A11：亮度与远程锁定延迟优化

## 目标

在保持 MD-A11 既有 Android 窗口亮度和远程锁定回退行为的前提下，完成子项 `MD-A11-01 界面亮度控制收敛`：界面只保留“总体亮度”，并让旧的 `bgBrightness`/`crossBrightness` 状态在网页、Node 和 Android Host 中获得视觉等价且幂等的迁移。

## 子项

- `MD-A11-01 界面亮度控制收敛`：移除两个可见的分通道亮度行，canonical state 将 legacy 亮度折入对应 RGB 色值并把两个 legacy 字段写回字符串 `"100"`，总体亮度作为唯一渲染乘数。
- `MD-A11-02 统一渲染与预设`：共享 Canvas、缩略图、PNG、视频、预设 swatch 和控制端同步的状态/颜色路径；内置 60%/30% 预设保留原名并改为已折色值。
- `MD-A11-03 跨端协议兼容`：Node `normalizeState`、浏览器 `readState`/`setState`、预设加载和 Android `HostProtocol.kt` 使用等价 canonicalization，并继续输出 legacy 字段 `"100"`。
- `MD-A11-04 验证与记录`：补充折色、幂等、旧预设、总体亮度组合、Node/Android canonical state 测试，以及项目检查和变更记录。

## 验收标准

- 屏幕设置页只有一个亮度滑杆，元素 id 为 `overallBrightness`，可见标签为“总体亮度”；生产 JS 不访问已删除的分通道亮度 DOM 元素。
- 输入含 `bgBrightness`/`crossBrightness` 的旧状态后，两个数值分别折入 `bgColor`/`crossColor` 的六位 RGB 色值，两个字段均为字符串 `"100"`，`overallBrightness` 保持归一化值。
- 对 canonical state 再次 canonicalize 不改变任何字段；缺失或非法 legacy 亮度按 100% 处理，未知状态字段仍由 Node/Android 白名单过滤。
- 直播 Canvas、随机点、设备缩略图、PNG、视频和预设 swatch 均使用折色后的颜色再应用总体亮度；总体亮度与旧分通道亮度组合的结果有测试覆盖。
- Node 和 Android Host 的 canonical state、内置预设名称/色值、旧预设加载和 legacy 字段兼容均有自动化断言。

## 变更

- 新增 `src/web/markerdeck-visual-state.js`，集中维护状态 canonicalization、RGB 折色、默认状态、默认预设和总体亮度颜色计算。
- 网页核心、Canvas、预设、导出与服务端改用共享 canonical state；Android `HostProtocol.kt` 实现相同的百分比裁剪、四舍五入和 RGB 折色算法。
- 内置 60%/30% 预设改为 `#009900`、`#004d00`、`#002682`、`#001341` 等已折色值，名称保持不变；所有新状态继续带 `bgBrightness: "100"` 和 `crossBrightness: "100"`。
- 更新 Node/Android/网页测试、项目结构检查、路线图、Android README 和 CHANGELOG。

## 验证

执行 `npm test`、`node scripts/check-project.js`、`git diff --check`；Android 至少执行 `./gradlew testDebugUnitTest`。真实 Android 窗口亮度、OEM 生命周期和 SSE 暂断时序仍属于设备现场验收范围。
