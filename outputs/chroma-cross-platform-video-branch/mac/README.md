# Chroma Cross Desktop Server

macOS:

Double-click `start-chroma-server.command`.

The macOS package includes `node-macos/node`, so Node.js does not need to be installed separately.

MP4 export uses `ffmpeg-macos/ffmpeg` when bundled, then checks Homebrew and the system PATH. This Mac currently has FFmpeg available at `/opt/homebrew/bin/ffmpeg`.

After startup:

- Launch page opens automatically on the computer.
- Phones scan the QR code shown on the launch page.
- Use the two buttons to enter control or display.
- If port `8765` is busy, the launcher will move to the next free port automatically.
- Direct URLs still work:
  - `http://PHONE_IP:PORT/chroma-cross-screen.html?mode=control`
  - `http://PHONE_IP:PORT/chroma-cross-screen.html?mode=display`

Keep the terminal window open while using the service. Close it or press Control-C to stop.
