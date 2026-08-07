# Chroma Cross Video Branch

This folder is an independent branch of the stable Chroma Cross project. It adds native FFmpeg video export without changing the existing project folder.

## Video export

- Render the current screen to a static H.264 MP4.
- Set a custom duration from 0.1 to 3600 seconds.
- Export every saved preset as MP4.
- Download batch exports as one ZIP or as separate MP4 files.
- Use the same custom pixel resolution as PNG export.
- Show a compact real-time FFmpeg progress window for current and batch exports.

For batch export, the selected duration is applied to every preset in that batch. To create different durations, export presets individually and change the duration before each export.

MP4 export requires opening the page through the local control server. Direct `file://` mode can still export PNG, but cannot call FFmpeg.

## FFmpeg lookup

The server checks these locations in order:

1. `FFMPEG_PATH`
2. `mac/ffmpeg-macos/ffmpeg` or `win/ffmpeg-windows/ffmpeg.exe`
3. `ffmpeg` available on the system `PATH`

The Windows Electron package configuration includes `ffmpeg-windows/**` when building the portable EXE.

Running `pnpm run build:win` first executes `win/prepare-ffmpeg-windows.ps1`. It downloads the release essentials build linked from the FFmpeg download ecosystem and places `ffmpeg.exe` in the packaged server directory. Internet access is only required while preparing/building; the resulting EXE runs offline.

FFmpeg and the selected Windows build have their own licenses. Preserve the corresponding license notices when distributing a package containing FFmpeg.
