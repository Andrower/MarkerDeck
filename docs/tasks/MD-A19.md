# MD-A19 Android 应用图标更新

## 目标

将 Android 启动图标从通用蓝色十字替换为 MarkerDeck 专属的取景框标记，提升品牌辨识度，并保持 Android 自适应图标和主题单色图标兼容。

## 问题与范围

旧图标使用绿色背景和蓝色十字，容易被理解为“添加”或医疗符号，也与应用的深绿界面不一致。本任务只更新 Android launcher 图标及其可编辑 SVG 源稿，不改变应用页面、协议、包名、版本号或桌面端打包配置。

## 设计与技术边界

- 深墨绿 `#101812` 作为自适应背景。
- 米白四角取景框表示画面范围，亮绿中心方点表示视觉标记。
- 前景位于 Android 自适应图标安全区域内，使用粗轮廓和大留白，避免小尺寸丢失。
- 单色资源保留四角与中心方点的整体剪影，供 Android 主题图标着色。
- `docs/assets/markerdeck-app-icon.svg` 是后续跨平台扩展使用的标准源稿。

## 验收标准

- Android adaptive icon 能在圆形、圆角方形等 launcher mask 下完整显示。
- 普通和彩色、系统主题单色两种资源都能被 Android 资源编译器接受。
- debug APK 构建通过，应用安装后 launcher 显示新图标。
- 桌面 Electron 当前没有自定义图标，本任务不扩展其打包配置。

## 验证

- SVG 源稿已渲染检查，四角、中心方点、圆角与留白符合选定方案。
- Android `lintDebug`、`assembleDebug` 已通过。
- `git diff --check` 已通过。
- Android `22127RK46C` 已安装正式 v1.7.1 APK；MIUI launcher 的圆角方形遮罩下，深墨绿背景、米白取景框和亮绿中心方点均完整显示。
- 主题单色资源已通过 Android 资源编译；该真机未开启主题图标，因此未做 launcher 着色现场验证。
