# 视频导出

MarkerDeck 使用 FFmpeg 将当前画面或全部预设转换为静态 H.264 MP4。

## 功能范围

- 时长范围：0.1 到 3600 秒
- 输出尺寸：使用控制端填写的最终像素宽高
- 像素格式：`yuv420p`
- 奇数宽高会自动补齐为偶数，确保播放器兼容
- 批量结果可以保存为 ZIP 或多个独立 MP4
- 生成窗口显示当前文件和整体进度

MP4 导出必须通过本地服务打开页面。`file://` 模式只能导出 PNG。

## FFmpeg 查找顺序

1. 环境变量 `FFMPEG_PATH`
2. 运行包中的 `runtime/ffmpeg-macos/ffmpeg` 或 `runtime/ffmpeg-windows/ffmpeg.exe`
3. 仓库开发目录中的 `runtime/macos` 或 `runtime/windows`
4. 系统 `PATH` 中的 `ffmpeg`

Windows 构建前可运行 `platform/windows/prepare-ffmpeg.ps1` 下载 FFmpeg。最终运行包包含 FFmpeg 后不需要联网。

FFmpeg 和第三方 Windows 构建使用各自许可证。发布包含 FFmpeg 的运行包时必须保留许可文件。
