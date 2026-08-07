const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");
const { spawn } = require("node:child_process");
const { after, before, test } = require("node:test");

const root = path.resolve(__dirname, "..");
const port = 18000 + (process.pid % 10000);
const origin = `http://127.0.0.1:${port}`;
const dataDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "chroma-test-"));
let serverProcess;
let serverOutput = "";

async function waitForServer() {
  const deadline = Date.now() + 8000;
  while (Date.now() < deadline) {
    try {
      const response = await fetch(`${origin}/api/info`);
      if (response.ok) return;
    } catch {
      // The process may still be starting.
    }
    await new Promise((resolve) => setTimeout(resolve, 100));
  }
  throw new Error(`Server did not start. Output: ${serverOutput}`);
}

async function jsonRequest(urlPath, options) {
  const response = await fetch(`${origin}${urlPath}`, options);
  const body = await response.json();
  return { response, body };
}

before(async () => {
  serverProcess = spawn(process.execPath, [path.join(root, "src/chroma-control-server.js")], {
    cwd: root,
    env: {
      ...process.env,
      PORT: String(port),
      CHROMA_DATA_DIR: dataDirectory
    },
    stdio: ["ignore", "pipe", "pipe"]
  });
  serverProcess.stdout.on("data", (chunk) => { serverOutput += chunk; });
  serverProcess.stderr.on("data", (chunk) => { serverOutput += chunk; });
  await waitForServer();
});

after(async () => {
  if (serverProcess && serverProcess.exitCode === null) {
    serverProcess.kill("SIGTERM");
    await new Promise((resolve) => serverProcess.once("exit", resolve));
  }
  fs.rmSync(dataDirectory, { recursive: true, force: true });
});

test("serves launch and control pages", async () => {
  const launch = await fetch(`${origin}/chroma-launch.html`);
  assert.equal(launch.status, 200);
  assert.match(await launch.text(), /控制端网址/);

  const control = await fetch(`${origin}/chroma-cross-screen.html?mode=control`);
  assert.equal(control.status, 200);
  assert.match(await control.text(), /设备管理/);
});

test("returns server information and QR code", async () => {
  const { response, body } = await jsonRequest("/api/info");
  assert.equal(response.status, 200);
  assert.equal(body.port, port);
  assert.match(body.url, /chroma-launch\.html$/);

  const qr = await fetch(`${origin}/qr.svg?text=${encodeURIComponent(body.url)}`);
  assert.equal(qr.status, 200);
  assert.match(qr.headers.get("content-type"), /image\/svg\+xml/);
  assert.match(await qr.text(), /^<svg/);
});

test("updates state and registers a named device", async () => {
  const nextState = { bgColor: "#123456", hideCross: "1" };
  const stateUpdate = await jsonRequest("/api/state", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(nextState)
  });
  assert.equal(stateUpdate.response.status, 200);

  const state = await jsonRequest("/api/state");
  assert.equal(state.body.bgColor, "#123456");
  assert.equal(state.body.hideCross, "1");

  const registration = await jsonRequest("/api/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ id: "test-device", name: "测试屏幕", role: "display" })
  });
  assert.equal(registration.response.status, 200);
  assert.equal(registration.body.name, "测试屏幕");

  const devices = await jsonRequest("/api/devices");
  assert.equal(devices.body.devices[0].id, "test-device");
});

test("persists and deletes a custom preset", async () => {
  const created = await jsonRequest("/api/presets", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ name: "自动测试预设", state: { bgColor: "#abcdef" } })
  });
  assert.equal(created.response.status, 200);
  assert.equal(created.body.preset.name, "自动测试预设");
  assert.ok(fs.existsSync(path.join(dataDirectory, "chroma-presets.json")));

  const deleted = await jsonRequest("/api/presets/delete", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ id: created.body.preset.id })
  });
  assert.equal(deleted.response.status, 200);
  assert.equal(deleted.body.presets.some((preset) => preset.id === created.body.preset.id), false);
});
