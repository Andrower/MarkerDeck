const { contextBridge, ipcRenderer } = require("electron");

contextBridge.exposeInMainWorld("chromaDesktop", {
  setProjectionLocked(locked) {
    ipcRenderer.send("projection-lock-changed", !!locked);
  },
  setDisplayMode(active) {
    ipcRenderer.send("display-mode-changed", !!active);
  },
  onProjectionLockHotkey(callback) {
    ipcRenderer.on("desktop-lock-hotkey", () => callback());
  }
});
