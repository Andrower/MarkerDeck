const { app, BrowserWindow, globalShortcut, ipcMain, shell } = require("electron");
const { spawn } = require("node:child_process");
const http = require("node:http");
const path = require("node:path");

const PORT = Number(process.env.PORT || 8765);
const ROOT = app.isPackaged
  ? path.join(process.resourcesPath, "server")
  : path.resolve(__dirname, "..");
const SERVER_FILE = path.join(ROOT, "chroma-control-server.js");
const BUNDLED_NODE = path.join(ROOT, "node-windows", "node.exe");
const LAUNCH_URL = `http://localhost:${PORT}/chroma-launch.html`;
const DISPLAY_URL = `http://localhost:${PORT}/chroma-cross-screen.html?mode=display`;
const UNLOCK_ACCELERATOR = "CommandOrControl+Alt+Shift+L";

let mainWindow = null;
let serverProcess = null;
let projectionLocked = false;
let displayMode = false;
let allowClose = false;

function waitForServer(url, timeoutMs = 6000) {
  const deadline = Date.now() + timeoutMs;
  return new Promise((resolve) => {
    const tick = () => {
      const request = http.get(url, (response) => {
        response.resume();
        resolve(true);
      });
      request.on("error", () => {
        if (Date.now() > deadline) resolve(false);
        else setTimeout(tick, 180);
      });
      request.setTimeout(800, () => request.destroy());
    };
    tick();
  });
}

function startServer() {
  if (serverProcess) return;
  const nodeRuntime = process.platform === "win32" && require("node:fs").existsSync(BUNDLED_NODE)
    ? BUNDLED_NODE
    : process.execPath;
  const serverEnv = nodeRuntime === process.execPath
    ? { ...process.env, PORT: String(PORT), ELECTRON_RUN_AS_NODE: "1" }
    : { ...process.env, PORT: String(PORT) };
  serverProcess = spawn(nodeRuntime, [SERVER_FILE], {
    cwd: ROOT,
    env: serverEnv,
    stdio: "ignore",
    windowsHide: true
  });
  serverProcess.on("exit", () => {
    serverProcess = null;
  });
}

function shouldBlockInput(input) {
  if (!projectionLocked) return false;
  const key = String(input.key || "").toLowerCase();
  const isUnlock =
    input.control &&
    input.alt &&
    input.shift &&
    (key === "l" || input.code === "KeyL");
  if (isUnlock) return false;
  if (key === "f4" && input.alt) return true;
  if (key === "escape" || key === "f11") return true;
  if (key === "enter" || key === " " || key === "space") return true;
  if (key === "meta" || key === "super") return true;
  if (input.control || input.meta || input.alt) return true;
  return ["browserback", "browserforward", "mediastop"].includes(key);
}

function applyWindowLock() {
  if (!mainWindow) return;
  if (projectionLocked || displayMode) {
    mainWindow.setMenuBarVisibility(false);
    mainWindow.setAlwaysOnTop(true, "screen-saver");
    mainWindow.setFullScreen(true);
  }
  mainWindow.setKiosk(projectionLocked);
  if (!projectionLocked && !displayMode) {
    mainWindow.setAlwaysOnTop(false);
    mainWindow.setFullScreen(false);
  }
}

function toggleProjectionLock() {
  projectionLocked = !projectionLocked;
  applyWindowLock();
  if (mainWindow) {
    mainWindow.webContents.send("desktop-lock-toggled", projectionLocked);
  }
}

async function createWindow() {
  mainWindow = new BrowserWindow({
    width: 1100,
    height: 760,
    minWidth: 720,
    minHeight: 520,
    backgroundColor: "#101812",
    autoHideMenuBar: true,
    webPreferences: {
      preload: path.join(__dirname, "preload.js"),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: false
    }
  });

  mainWindow.webContents.setWindowOpenHandler(({ url }) => {
    shell.openExternal(url);
    return { action: "deny" };
  });

  mainWindow.webContents.on("before-input-event", (event, input) => {
    if (shouldBlockInput(input)) event.preventDefault();
  });

  mainWindow.on("close", (event) => {
    if (projectionLocked && !allowClose) {
      event.preventDefault();
      mainWindow.focus();
    }
  });

  mainWindow.webContents.on("did-navigate", (_, url) => {
    displayMode = url.includes("mode=display");
    applyWindowLock();
  });

  mainWindow.webContents.on("did-navigate-in-page", (_, url) => {
    displayMode = url.includes("mode=display");
    applyWindowLock();
  });

  await mainWindow.loadURL(LAUNCH_URL);
}

app.whenReady().then(async () => {
  startServer();
  await waitForServer(LAUNCH_URL);
  await createWindow();
  globalShortcut.register(UNLOCK_ACCELERATOR, toggleProjectionLock);
});

ipcMain.on("projection-lock-changed", (_, locked) => {
  projectionLocked = !!locked;
  applyWindowLock();
});

ipcMain.on("display-mode-changed", (_, active) => {
  displayMode = !!active;
  applyWindowLock();
});

app.on("before-quit", () => {
  allowClose = true;
});

app.on("window-all-closed", () => {
  if (serverProcess) serverProcess.kill();
  app.quit();
});
