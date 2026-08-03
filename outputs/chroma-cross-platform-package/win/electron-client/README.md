# Chroma Cross Electron Client

This folder is the Windows desktop shell source for building a portable `.exe`.

It keeps the current web control system, but wraps it in Electron so display lock can also use:

- borderless fullscreen/kiosk mode
- always-on-top projection window
- blocked `Alt+F4`, `Esc`, `F11`, `Enter`, `Space`, `Ctrl+W`, `Ctrl+R` and similar shortcuts while locked
- unlock hotkey: `Ctrl + Alt + Shift + L`

Build on Windows:

```bat
cd electron-client
npm install
npm run build:win
```

The generated portable exe will be in `dist`.

The client blocks Windows-key events that reach Electron, but Windows handles the Windows key and some combinations before the app receives them. Fully disabling the Windows key requires Windows Assigned Access/kiosk policy, Group Policy, or a privileged native keyboard filter. System-level actions such as `Ctrl + Alt + Delete`, the power key, and Task Manager force-ending the process cannot be fully blocked by a normal app.
