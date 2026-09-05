# MD-A18：手机被控端快速调节

## 状态

手机 local/display 被控端快速调节优化已完成并冻结代码。显式解锁后的参数面板先展示常用颜色、亮度和十字参数，同时保留既有快捷预设、预设管理、锁定/退出和同步流程。已在一台 Android 设备 `22127RK46C` 上完成现场验证，其他 Android/OEM 组合仍未覆盖。

## 目标

在 `390×844`、`360×800` 以及 `844×390` 手机视口中，local/display 面板解锁后无需先滚过预设卡片或保存表单即可调整背景颜色、总体亮度、十字颜色、大小等常用参数。被控端仍显示部分投放画面，滑条与锁定按钮保持可操作；control 桌面、平板和手机布局保持 MD-A17 行为。

## 已观察问题

- local/display 复用了控制端新增的工作台 DOM，预设网格和保存表单位于参数前方，首屏无法直接调节画面。
- 移动预设栏和预设 sheet 原先只在 control 样式下显示，被控端没有快捷选择入口。
- 被控端打开预设管理时不能显示既有预设列表；锁定期间已开的 sheet 或入口仍可能留下可见状态。
- 手机横屏高度较低时需要保持面板可滚动，锁定按钮不能换行或被底部入口遮挡。

## 范围

- 仅调整 `src/web` 的 local/display 手机响应式布局：把 `.settings-section` 的常用参数置于隐藏的主预设区之前，保存/删除预设和导出留在后部或管理态。
- 为非 control 手机启用已有 `mobile-preset-bar`、`mobile-preset-sheet` 和 `applyPreset` 流程，sheet 目标文案使用“当前设备”。
- local/display 的“管理预设”显示既有 `.preset-section`，不调用 control 专用预设 overlay；提供从管理态返回参数的入口。
- 锁定时关闭并清理移动预设 sheet/管理态，隐藏面板、快捷入口和 sheet；解锁不会自动恢复旧 sheet。
- 保持桌面/平板 control 布局、设备/场景隔离、HTTP/SSE、ACK、状态同步、原生 interaction guard 与 Android 原生代码不变。

## 非目标

- 不改 Node/Android HTTP、SSE、设备身份、锁定 ACK 或状态同步协议。
- 不新增预设数据结构、第二套预设列表、前端框架或服务端接口。
- 不让 local/display 进入设备管理、场景平面图、场景选择、远程目标详情或 control 场景 overlay。
- 不修改 Android 原生设置页、投放壳、启动页或屏幕锁定语义；真机/OEM 适配不在本任务内完成。

## 技术边界与受影响平台

- 共享网页模块：`markerdeck-presets.js` 复用既有预设数据和应用函数，`markerdeck-projection.js` 只负责锁定时关闭移动入口，CSS 通过 `body.control-mode` 与非 control 选择器隔离。
- local/display 桌面保持既有面板逻辑；local/display 手机和手机横屏增加快速调节布局。control 全部视口只做回归验证。
- body 的 `locked` 状态仍由现有投放锁定流程控制，面板与入口通过 CSS/已有状态事件隐藏，不改变后台同步或 ACK 时序。

## 验收标准

- local/display 在 `390×844`、`360×800` 解锁面板首屏可见背景颜色、总体亮度、十字颜色和十字大小；滑条触控区域足够，顶部“锁定投放”单行显示。
- local/display 在 `844×390` 横屏可滚动使用，参数、锁定按钮和移动预设入口不互相遮挡。
- 快捷预设入口打开既有移动 sheet，显示“当前设备”，应用预设仍走 `applyPreset`；管理预设显示保存/删除列表并可明确返回参数。
- local/display 管理入口不打开 control 预设 overlay；场景/设备/远程详情仍隐藏。control 桌面、平板、手机布局和预设行为无回归。
- 锁定或远程锁定时面板、移动预设栏和 sheet 均隐藏且不可点击，已开 sheet 关闭并失去焦点；解锁只恢复参数面板，不自动弹出旧 sheet。
- `npm run check`、相关 Node 专项测试和 `git diff --check` 通过；其他 Android/OEM、不同系统字体和长时生命周期仍明确列为未测。

## 自动化验证

- 补充或调整纯逻辑测试时只覆盖新的 role 分支、管理入口不打开 control overlay、锁定关闭 sheet 等状态行为，不镜像 CSS 像素布局。
- 运行 `npm run check`、必要的 `npm test` 和 `git diff --check`；浏览器视口与交互由主任务独立会话验收。

## 未测现场项

- 其他 Android 真机/OEM WebView、安全区与状态栏组合、系统字体放大和长时前后台生命周期。
- 不同浏览器的横屏地址栏动态高度及系统返回手势。

## 实现与当前验证

- 非 control 手机样式将 `.settings-section` 提到首屏，预设保存/删除与导出留在管理态；`390×844` 和 `360×800` 浏览器视口已确认颜色、亮度、十字大小和单行锁定按钮可见。
- `844×390` 横屏浏览器视口已确认参数区可滚动，移动预设栏右对齐且不覆盖参数；预设 sheet 卡片、滚动与关闭入口可用。
- local/display 的管理入口复用既有预设列表，提供“返回参数”；保存预设、返回参数、应用蓝底并通过本地/服务端同步的路径已验证。
- 远程锁定打开 sheet 时，面板、移动预设栏和 sheet 均隐藏并清理管理态；解锁只恢复面板和快捷栏，不自动恢复旧 sheet。主任务浏览器回归确认 control `1440`、`820` 与 `390` 视口地图/移动布局无横溢出，local `360` 视口预设应用、亮度和锁定入口正常，display 管理态返回、远程锁定清理焦点与解锁恢复参数正常。
- 管理态软键盘首次滚动已用 sticky header 间距修复；非 control 通用 panel/参数滚动也补齐 header 间距，避免“返回参数”后颜色控件被遮挡。
- Android `22127RK46C`（Android 16 WebView，Chrome `149.0.7827.163`，设备 `1440×3200`、DPR `3.5`，CSS `411×914`/`914×411`）已现场验证：local 键盘输入/保存、返回参数、预设应用与锁定可用；横屏参数与底栏无横溢出。display 通过局域网连接、预设和远程锁定/解锁，锁定 ACK `acknowledged=1 confirmed=1 failed=0 pending=0`。
- v1.7.0 发布收尾另在同一设备验证 Android 本机宿主 `control` WebView 的状态栏/刘海安全区与横屏布局，并确认切换 `local` 后恢复沉浸式投放；这是宿主壳安全区修复，未改变本任务的 receiver 网页协议与锁定语义，详细记录见 `docs/tasks/MD-A17.md`。
- `npm run check` 通过（57/57），`git diff --check` 通过；Android `test`、`lintDebug`、`assembleDebug` 通过（104 项 JVM 基线测试）。其他 OEM、系统字体放大、长时生命周期和不同浏览器地址栏仍未测。
