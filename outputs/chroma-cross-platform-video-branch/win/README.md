# Chroma Cross Portable Server

Windows:

Double-click `start-chroma-server.bat`.

Windows uses the bundled `node.exe` in this package.

MP4 export uses `ffmpeg-windows\ffmpeg.exe` when present, then falls back to FFmpeg on the system PATH. Run `prepare-ffmpeg-windows.ps1` before packaging an offline Windows build.

After startup:

- Launch page opens automatically on the computer.
- Phones scan the QR code shown on the launch page.
- Use the two buttons to enter control or display.
- Direct URLs still work:
  - `http://PHONE_IP:8765/chroma-cross-screen.html?mode=control`
  - `http://PHONE_IP:8765/chroma-cross-screen.html?mode=display`

Keep the terminal window open while using the service. Close it or press Control-C to stop.

Optional Windows exe client:

- `electron-client` contains the desktop shell source for building a portable `.exe`.
- The exe shell starts the same local service, opens the launch page, and uses stronger display locking in Electron.
- While locked it blocks common exit shortcuts such as `Alt+F4`, `Esc`, `F11`, `Enter`, `Space`, `Ctrl+W`, and `Ctrl+R`.
- Unlock hotkey: `Ctrl + Alt + Shift + L`.

Build on Windows:

```bat
cd electron-client
npm install
npm run build:win
```

The generated portable exe will appear in `electron-client\dist`.

Note: Windows-key events are blocked when they reach the client, but Windows handles the Windows key and some combinations before the app receives them. Fully disabling it requires Windows Assigned Access/kiosk policy, Group Policy, or a privileged native keyboard filter. System-level actions such as `Ctrl + Alt + Delete`, the power key, and Task Manager force-ending the process cannot be fully blocked by a normal app.
