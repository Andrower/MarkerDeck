const assert = require("node:assert/strict");
const fs = require("node:fs");
const http = require("node:http");
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

function receiveSseEvent(role, sessionId, eventName, action) {
  return new Promise((resolve, reject) => {
    let buffer = "";
    let actionStarted = false;
    const request = http.get(`${origin}/api/events?role=${encodeURIComponent(role)}&sessionId=${encodeURIComponent(sessionId)}`);
    const timeout = setTimeout(() => {
      request.destroy();
      reject(new Error(`Timed out waiting for SSE event: ${eventName}`));
    }, 3000);
    request.on("response", (response) => {
      response.setEncoding("utf8");
      response.on("data", async (chunk) => {
        buffer += chunk;
        if (!actionStarted && buffer.includes("event: connected")) {
          actionStarted = true;
          try {
            await action();
          } catch (error) {
            clearTimeout(timeout);
            request.destroy();
            reject(error);
          }
        }
        const blocks = buffer.split("\n\n");
        buffer = blocks.pop() || "";
        for (const block of blocks) {
          if (!block.includes(`event: ${eventName}`)) continue;
          const dataLine = block.split("\n").find((line) => line.startsWith("data: "));
          clearTimeout(timeout);
          request.destroy();
          resolve(JSON.parse(dataLine.slice(6)));
          return;
        }
      });
    });
    request.on("error", (error) => {
      if (error.code === "ECONNRESET") return;
      clearTimeout(timeout);
      reject(error);
    });
  });
}

function openSseConnection(role, sessionId, pageInstanceId = "") {
  return new Promise((resolve, reject) => {
    const request = http.get(`${origin}/api/events?role=${encodeURIComponent(role)}&sessionId=${encodeURIComponent(sessionId)}&pageInstanceId=${encodeURIComponent(pageInstanceId)}`);
    const timeout = setTimeout(() => {
      request.destroy();
      reject(new Error("Timed out opening SSE connection"));
    }, 3000);
    request.on("response", (response) => {
      response.setEncoding("utf8");
      response.on("data", (chunk) => {
        if (!chunk.includes("event: connected")) return;
        clearTimeout(timeout);
        resolve(request);
      });
    });
    request.on("error", (error) => {
      if (error.code === "ECONNRESET") return;
      clearTimeout(timeout);
      reject(error);
    });
  });
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
    body: JSON.stringify({
      id: "test-session",
      sessionId: "test-session",
      deviceId: "test-device",
      name: "测试屏幕",
      role: "display"
    })
  });
  assert.equal(registration.response.status, 200);
  assert.equal(registration.body.name, "测试屏幕");
  assert.equal(registration.body.globalLockCommandId, "0");

  const devices = await jsonRequest("/api/devices");
  assert.equal(devices.body.devices[0].id, "test-session");
  assert.equal(devices.body.devices[0].deviceId, "test-device");
});

test("tracks multiple receiving sessions for one physical device", async () => {
  const secondRegistration = await jsonRequest("/api/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      id: "test-session-2",
      sessionId: "test-session-2",
      deviceId: "test-device",
      name: "不应覆盖已有名称",
      role: "display"
    })
  });
  assert.equal(secondRegistration.response.status, 200);
  assert.equal(secondRegistration.body.name, "测试屏幕");

  const devices = await jsonRequest("/api/devices");
  const sessions = devices.body.devices.filter((device) => device.deviceId === "test-device");
  assert.deepEqual(sessions.map((device) => device.id).sort(), ["test-session", "test-session-2"]);

  const renamed = await jsonRequest("/api/device-name", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ id: "test-session", name: "双窗口设备" })
  });
  assert.equal(renamed.body.updated, 2);
  const renamedDevices = await jsonRequest("/api/devices");
  assert.deepEqual(
    renamedDevices.body.devices.filter((device) => device.deviceId === "test-device").map((device) => device.name),
    ["双窗口设备", "双窗口设备"]
  );
});

test("inherits groups for new sessions on the same physical device", async () => {
  await jsonRequest("/api/device-group", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ids: ["test-session", "test-session-2"], group: "A 组" })
  });
  const registration = await jsonRequest("/api/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      id: "test-session-3",
      sessionId: "test-session-3",
      deviceId: "test-device",
      name: "双窗口设备",
      role: "display"
    })
  });
  assert.equal(registration.response.status, 200);
  const devices = await jsonRequest("/api/devices");
  assert.equal(devices.body.devices.find((device) => device.id === "test-session-3").group, "A 组");
});

test("allocates a new session id when a duplicated tab conflicts with an active page", async () => {
  await jsonRequest("/api/register", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({
      id: "copied-session",
      sessionId: "copied-session",
      deviceId: "copied-device",
      pageInstanceId: "page-original",
      name: "复制标签测试",
      role: "display"
    })
  });
  const eventRequest = await openSseConnection("display", "copied-session", "page-original");
  try {
    const duplicate = await jsonRequest("/api/register", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({
        id: "copied-session",
        sessionId: "copied-session",
        deviceId: "copied-device",
        pageInstanceId: "page-duplicate",
        name: "复制标签测试",
        role: "display"
      })
    });
    assert.notEqual(duplicate.body.sessionId, "copied-session");
    const devices = await jsonRequest("/api/devices");
    assert.equal(devices.body.devices.filter((device) => device.deviceId === "copied-device").length, 2);
  } finally {
    eventRequest.destroy();
  }
});

test("pushes state changes through the realtime event stream", async () => {
  const event = await receiveSseEvent("display", "test-session", "state", async () => {
    await jsonRequest("/api/state?deviceId=test-session", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ bgColor: "#654321" })
    });
  });
  assert.equal(event.sessionId, "test-session");
  assert.equal(event.state.bgColor, "#654321");
});

test("broadcasts lock commands without device state updates consuming them", async () => {
  const broadcast = await jsonRequest("/api/lock-broadcast", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ enabled: true })
  });
  assert.equal(broadcast.response.status, 200);
  assert.equal(broadcast.body.command, "lock");

  const firstRead = await jsonRequest("/api/state?deviceId=test-session");
  assert.equal(firstRead.body.globalLockCommand, "lock");
  assert.equal(firstRead.body.globalLockCommandId, broadcast.body.commandId);

  await jsonRequest("/api/state?deviceId=test-session", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ bgColor: "#00ff00", displayLocked: "1" })
  });
  const secondRead = await jsonRequest("/api/state?deviceId=test-session");
  assert.equal(secondRead.body.globalLockCommandId, broadcast.body.commandId);
});

test("collects lock acknowledgements per receiving session", async () => {
  const command = await jsonRequest("/api/lock-command", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ ids: ["test-session", "test-session-2"], enabled: true })
  });
  assert.equal(command.body.targetCount, 2);
  assert.equal(command.body.pendingCount, 2);

  const firstAck = await jsonRequest("/api/lock-ack", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ commandId: command.body.commandId, sessionId: "test-session", ok: true, locked: true })
  });
  assert.equal(firstAck.body.confirmedCount, 1);
  assert.equal(firstAck.body.pendingCount, 1);
  assert.equal(firstAck.body.complete, false);

  const secondAck = await jsonRequest("/api/lock-ack", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ commandId: command.body.commandId, sessionId: "test-session-2", ok: true, locked: true })
  });
  assert.equal(secondAck.body.confirmedCount, 2);
  assert.equal(secondAck.body.pendingCount, 0);
  assert.equal(secondAck.body.complete, true);
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
