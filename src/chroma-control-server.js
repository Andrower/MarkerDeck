#!/usr/bin/env node

const http = require("node:http");
const os = require("node:os");
const fs = require("node:fs");
const path = require("node:path");
const { spawn } = require("node:child_process");

const PORT = Number(process.env.PORT || 8765);
const ROOT = __dirname;
const WEB_ROOT = path.join(ROOT, "web");
const DATA_ROOT = process.env.CHROMA_DATA_DIR
  ? path.resolve(process.env.CHROMA_DATA_DIR)
  : process.cwd();
const PRESETS_FILE = process.env.CHROMA_PRESETS_FILE
  ? path.resolve(process.env.CHROMA_PRESETS_FILE)
  : path.join(DATA_ROOT, "chroma-presets.json");
const PAGES = new Set([
  "chroma-cross-screen.html",
  "chroma-launch.html"
]);

const LAUNCH_PAGE = "/chroma-launch.html";

const defaultState = {
  bgColor: "#00ff00",
  bgBrightness: "100",
  crossColor: "#0040d8",
  crossBrightness: "100",
  crossSize: "6",
  crossThickness: "1.4",
  edgeRatio: "10",
  centerY: "50",
  hideCross: "0",
  randomPoints: "0",
  randomPointCount: "12",
  randomSeed: "",
  forceLock: "0",
  displayLocked: "0",
  lockCommand: "none",
  lockCommandId: "0"
};

const defaultPresets = [
  ["绿底蓝十字", "#00ff00", "100", "#0040d8", "100"],
  ["60%绿底蓝十字", "#00ff00", "60", "#0040d8", "100"],
  ["30%绿底蓝十字", "#00ff00", "30", "#0040d8", "100"],
  ["蓝底绿十字", "#0040d8", "100", "#00ff00", "100"],
  ["60%蓝底绿十字", "#0040d8", "60", "#00ff00", "100"],
  ["30%蓝底绿十字", "#0040d8", "30", "#00ff00", "100"],
  ["浅灰底蓝十字", "#d8d8d8", "100", "#0040d8", "100"],
  ["浅灰底绿十字", "#d8d8d8", "100", "#00ff00", "100"]
].map(([name, bgColor, bgBrightness, crossColor, crossBrightness], index) => ({
  id: `default-${index + 1}`,
  name,
  state: {
    ...defaultState,
    bgColor,
    bgBrightness,
    crossColor,
    crossBrightness
  }
}));

let state = { ...defaultState };
const devices = new Map();
let nextDeviceOrder = 1;
let activeVideoJobs = 0;
const videoJobs = new Map();

function cleanPreset(preset, fallbackId) {
  const name = String(preset?.name || "").trim().slice(0, 40);
  if (!name) return null;
  const rawId = String(preset?.id || fallbackId || "").replace(/[^a-zA-Z0-9_-]/g, "").slice(0, 80);
  return {
    id: rawId || `preset-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
    name,
    state: normalizeState(preset?.state || {})
  };
}

function loadPresets() {
  try {
    const parsed = JSON.parse(fs.readFileSync(PRESETS_FILE, "utf8"));
    if (!Array.isArray(parsed)) throw new Error("Preset file must contain an array");
    return parsed.map((preset, index) => cleanPreset(preset, `saved-${index + 1}`)).filter(Boolean);
  } catch (error) {
    if (error.code !== "ENOENT") console.error(`Unable to load presets: ${error.message}`);
    return defaultPresets.map((preset) => cleanPreset(preset, preset.id));
  }
}

function savePresets() {
  fs.mkdirSync(path.dirname(PRESETS_FILE), { recursive: true });
  const temporaryFile = `${PRESETS_FILE}.tmp`;
  fs.writeFileSync(temporaryFile, JSON.stringify(presets, null, 2), "utf8");
  fs.renameSync(temporaryFile, PRESETS_FILE);
}

let presets = loadPresets();

function cleanDevice(device) {
  return {
    id: device.id,
    name: device.name,
    group: device.group || "",
    role: device.role,
    width: device.width,
    height: device.height,
    dpr: device.dpr,
    userAgent: device.userAgent,
    lastSeen: device.lastSeen,
    order: device.order,
    online: Date.now() - device.lastSeen < 5000,
    state: device.state || state
  };
}

function normalizeState(next) {
  return {
    ...defaultState,
    ...Object.fromEntries(Object.entries(next || {}).filter(([key]) => key in defaultState))
  };
}

function getLanIp() {
  const nets = os.networkInterfaces();
  for (const list of Object.values(nets)) {
    for (const item of list || []) {
      if (item.family === "IPv4" && !item.internal) return item.address;
    }
  }
  return "127.0.0.1";
}

function send(res, status, body, type = "text/plain; charset=utf-8") {
  res.writeHead(status, {
    "content-type": type,
    "cache-control": "no-store",
    "access-control-allow-origin": "*",
    "access-control-allow-methods": "GET,POST,OPTIONS",
    "access-control-allow-headers": "content-type"
  });
  res.end(body);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let data = "";
    req.on("data", (chunk) => {
      data += chunk;
      if (data.length > 10000) {
        req.destroy();
        reject(new Error("body too large"));
      }
    });
    req.on("end", () => resolve(data));
    req.on("error", reject);
  });
}

function readBinaryBody(req, limit = 70 * 1024 * 1024) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    let size = 0;
    let tooLarge = false;
    req.on("data", (chunk) => {
      size += chunk.length;
      if (size > limit) {
        tooLarge = true;
        chunks.length = 0;
        return;
      }
      if (!tooLarge) chunks.push(chunk);
    });
    req.on("end", () => tooLarge ? reject(new Error("image-too-large")) : resolve(Buffer.concat(chunks)));
    req.on("error", reject);
  });
}

function ffmpegExecutable() {
  if (process.env.FFMPEG_PATH) return process.env.FFMPEG_PATH;
  const directory = process.platform === "win32" ? "ffmpeg-windows" : "ffmpeg-macos";
  const executable = process.platform === "win32" ? "ffmpeg.exe" : "ffmpeg";
  const bundledCandidates = [
    path.join(ROOT, directory, executable),
    path.join(ROOT, "..", "runtime", process.platform === "win32" ? "windows" : "macos", directory, executable)
  ];
  return bundledCandidates.find((candidate) => fs.existsSync(candidate)) || "ffmpeg";
}

function makeStaticVideo(png, duration, onProgress = () => {}) {
  const temporaryDirectory = fs.mkdtempSync(path.join(os.tmpdir(), "chroma-video-"));
  const inputFile = path.join(temporaryDirectory, "frame.png");
  const outputFile = path.join(temporaryDirectory, "output.mp4");
  fs.writeFileSync(inputFile, png);

  return new Promise((resolve, reject) => {
    const errors = [];
    const processHandle = spawn(ffmpegExecutable(), [
      "-hide_banner", "-loglevel", "error", "-y",
      "-loop", "1", "-framerate", "10", "-i", inputFile,
      "-t", duration.toFixed(3),
      "-vf", "pad=ceil(iw/2)*2:ceil(ih/2)*2,format=yuv420p",
      "-c:v", "libx264", "-preset", "medium", "-tune", "stillimage",
      "-r", "10", "-movflags", "+faststart", "-an",
      "-progress", "pipe:1", "-nostats", outputFile
    ], { windowsHide: true });
    let progressOutput = "";
    processHandle.stdout.on("data", (chunk) => {
      progressOutput += chunk.toString("utf8");
      const lines = progressOutput.split(/\r?\n/);
      progressOutput = lines.pop() || "";
      lines.forEach((line) => {
        const [key, value] = line.split("=");
        if (key === "out_time_us") {
          const percent = Math.max(0, Math.min(99, (Number(value) / (duration * 1000000)) * 100));
          if (Number.isFinite(percent)) onProgress(percent);
        }
        if (key === "progress" && value === "end") onProgress(100);
      });
    });
    processHandle.stderr.on("data", (chunk) => errors.push(chunk));
    processHandle.on("error", (error) => reject(new Error(error.code === "ENOENT" ? "ffmpeg-not-found" : error.message)));
    processHandle.on("close", (code) => {
      if (code !== 0) {
        const detail = Buffer.concat(errors).toString("utf8").trim().slice(-1200);
        reject(new Error(detail || `ffmpeg-exit-${code}`));
        return;
      }
      try {
        resolve(fs.readFileSync(outputFile));
      } catch (error) {
        reject(error);
      }
    });
  }).finally(() => fs.rmSync(temporaryDirectory, { recursive: true, force: true }));
}

function startVideoJob(png, duration) {
  const id = `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  const job = { id, status: "running", progress: 0, video: null, error: "" };
  videoJobs.set(id, job);
  activeVideoJobs += 1;
  makeStaticVideo(png, duration, (progress) => {
    job.progress = Math.max(job.progress, Math.round(progress * 10) / 10);
  }).then((video) => {
    job.video = video;
    job.progress = 100;
    job.status = "ready";
  }).catch((error) => {
    job.error = error.message || String(error);
    job.status = "failed";
  }).finally(() => {
    activeVideoJobs -= 1;
    setTimeout(() => videoJobs.delete(id), 10 * 60 * 1000).unref();
  });
  return job;
}

function sendDownload(res, body, fileName, type) {
  res.writeHead(200, {
    "content-type": type,
    "content-length": body.length,
    "content-disposition": `attachment; filename="${fileName}"`,
    "cache-control": "no-store",
    "access-control-allow-origin": "*"
  });
  res.end(body);
}

function gfMul(x, y) {
  let z = 0;
  for (let i = 7; i >= 0; i--) {
    z = (z << 1) ^ ((z >>> 7) * 0x11d);
    z ^= ((y >>> i) & 1) * x;
  }
  return z & 0xff;
}

function gfPow(x) {
  let y = 1;
  for (let i = 0; i < x; i++) y = gfMul(y, 2);
  return y;
}

function rsGenerator(degree) {
  let result = [1];
  for (let i = 0; i < degree; i++) {
    const next = Array(result.length + 1).fill(0);
    for (let j = 0; j < result.length; j++) {
      next[j] ^= result[j];
      next[j + 1] ^= gfMul(result[j], gfPow(i));
    }
    result = next;
  }
  return result;
}

function rsRemainder(data, degree) {
  const gen = rsGenerator(degree);
  const result = Array(degree).fill(0);
  for (const value of data) {
    const factor = value ^ result.shift();
    result.push(0);
    for (let i = 0; i < degree; i++) result[i] ^= gfMul(gen[i + 1], factor);
  }
  return result;
}

function appendBits(bits, value, length) {
  for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
}

function makeCodewords(text) {
  const bytes = Array.from(Buffer.from(text, "utf8"));
  if (bytes.length > 78) throw new Error("QR URL is too long");
  const bits = [];
  appendBits(bits, 0x4, 4);
  appendBits(bits, bytes.length, 8);
  bytes.forEach((byte) => appendBits(bits, byte, 8));
  appendBits(bits, 0, Math.min(4, 640 - bits.length));
  while (bits.length % 8) bits.push(0);

  const data = [];
  for (let i = 0; i < bits.length; i += 8) {
    data.push(bits.slice(i, i + 8).reduce((a, b) => (a << 1) | b, 0));
  }
  for (let pad = 0xec; data.length < 80; pad ^= 0xfd) data.push(pad);
  return data.concat(rsRemainder(data, 20));
}

function makeQrSvg(text) {
  const version = 4;
  const size = version * 4 + 17;
  const modules = Array.from({ length: size }, () => Array(size).fill(false));
  const fixed = Array.from({ length: size }, () => Array(size).fill(false));

  function set(x, y, dark, isFixed = true) {
    if (x < 0 || y < 0 || x >= size || y >= size) return;
    modules[y][x] = !!dark;
    if (isFixed) fixed[y][x] = true;
  }

  function finder(x, y) {
    for (let dy = -1; dy <= 7; dy++) {
      for (let dx = -1; dx <= 7; dx++) {
        const xx = x + dx;
        const yy = y + dy;
        const dark = dx >= 0 && dx <= 6 && dy >= 0 && dy <= 6 &&
          (dx === 0 || dx === 6 || dy === 0 || dy === 6 || (dx >= 2 && dx <= 4 && dy >= 2 && dy <= 4));
        set(xx, yy, dark);
      }
    }
  }

  finder(0, 0);
  finder(size - 7, 0);
  finder(0, size - 7);
  for (let i = 8; i < size - 8; i++) {
    set(i, 6, i % 2 === 0);
    set(6, i, i % 2 === 0);
  }
  for (let dy = -2; dy <= 2; dy++) {
    for (let dx = -2; dx <= 2; dx++) {
      const d = Math.max(Math.abs(dx), Math.abs(dy));
      set(26 + dx, 26 + dy, d !== 1);
    }
  }
  set(8, size - 8, true);
  for (let i = 0; i < 9; i++) {
    if (i !== 6) {
      set(8, i, false);
      set(i, 8, false);
    }
  }
  for (let i = 0; i < 8; i++) {
    set(size - 1 - i, 8, false);
    set(8, size - 1 - i, false);
  }

  const codewords = makeCodewords(text);
  const bits = [];
  codewords.forEach((byte) => appendBits(bits, byte, 8));
  let bitIndex = 0;
  let upward = true;
  for (let right = size - 1; right >= 1; right -= 2) {
    if (right === 6) right = 5;
    for (let vert = 0; vert < size; vert++) {
      const y = upward ? size - 1 - vert : vert;
      for (let j = 0; j < 2; j++) {
        const x = right - j;
        if (fixed[y][x]) continue;
        const mask = (x + y) % 2 === 0;
        modules[y][x] = ((bits[bitIndex] || 0) === 1) !== mask;
        bitIndex++;
      }
    }
    upward = !upward;
  }

  const format = getFormatBits(1, 0);
  for (let i = 0; i <= 5; i++) set(8, i, ((format >>> i) & 1) !== 0);
  set(8, 7, ((format >>> 6) & 1) !== 0);
  set(8, 8, ((format >>> 7) & 1) !== 0);
  set(7, 8, ((format >>> 8) & 1) !== 0);
  for (let i = 9; i < 15; i++) set(14 - i, 8, ((format >>> i) & 1) !== 0);
  for (let i = 0; i < 8; i++) set(size - 1 - i, 8, ((format >>> i) & 1) !== 0);
  for (let i = 8; i < 15; i++) set(8, size - 15 + i, ((format >>> i) & 1) !== 0);

  const scale = 8;
  const border = 4;
  const outSize = (size + border * 2) * scale;
  const rects = [];
  for (let y = 0; y < size; y++) {
    for (let x = 0; x < size; x++) {
      if (modules[y][x]) rects.push(`<rect x="${(x + border) * scale}" y="${(y + border) * scale}" width="${scale}" height="${scale}"/>`);
    }
  }
  return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${outSize} ${outSize}" width="${outSize}" height="${outSize}"><rect width="100%" height="100%" fill="#fff"/><g fill="#000">${rects.join("")}</g></svg>`;
}

function getFormatBits(ecl, mask) {
  let data = (ecl << 3) | mask;
  let rem = data;
  for (let i = 0; i < 10; i++) rem = (rem << 1) ^ (((rem >>> 9) & 1) * 0x537);
  return ((data << 10) | rem) ^ 0x5412;
}

let server;

async function handler(req, res) {
  if (req.method === "OPTIONS") return send(res, 204, "");
  const url = new URL(req.url, `http://${req.headers.host}`);

  if (url.pathname === "/") {
    res.writeHead(302, { location: LAUNCH_PAGE });
    return res.end();
  }

  if (url.pathname === "/display" || url.pathname === "/control") {
    const suffix = url.pathname === "/display" ? "?mode=display" : "?mode=control";
    res.writeHead(302, { location: `/chroma-cross-screen.html${suffix}` });
    return res.end();
  }

  if (url.pathname === "/api/info") {
    const lanIp = getLanIp();
    return send(res, 200, JSON.stringify({
      ip: lanIp,
      port: PORT,
      url: `http://${lanIp}:${PORT}${LAUNCH_PAGE}`
    }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/shutdown" && req.method === "POST") {
    send(res, 200, JSON.stringify({ ok: true }), "application/json; charset=utf-8");
    setTimeout(() => {
      server.close(() => process.exit(0));
      setTimeout(() => process.exit(0), 1000).unref();
    }, 80).unref();
    return;
  }

  if (url.pathname === "/api/video" && req.method === "POST") {
    const duration = Number(url.searchParams.get("duration"));
    if (!Number.isFinite(duration) || duration < 0.1 || duration > 3600) {
      return send(res, 400, "Video duration must be between 0.1 and 3600 seconds");
    }
    if (activeVideoJobs >= 1) return send(res, 429, "A video export is already running");
    const png = await readBinaryBody(req);
    if (png.length < 8 || png.subarray(0, 8).toString("hex") !== "89504e470d0a1a0a") {
      return send(res, 400, "Expected a PNG image");
    }
    activeVideoJobs += 1;
    try {
      const video = await makeStaticVideo(png, duration);
      return sendDownload(res, video, "chroma-static.mp4", "video/mp4");
    } finally {
      activeVideoJobs -= 1;
    }
  }

  if (url.pathname === "/api/video/start" && req.method === "POST") {
    const duration = Number(url.searchParams.get("duration"));
    if (!Number.isFinite(duration) || duration < 0.1 || duration > 3600) {
      return send(res, 400, "Video duration must be between 0.1 and 3600 seconds");
    }
    if (activeVideoJobs >= 1) return send(res, 429, "A video export is already running");
    const png = await readBinaryBody(req);
    if (png.length < 8 || png.subarray(0, 8).toString("hex") !== "89504e470d0a1a0a") {
      return send(res, 400, "Expected a PNG image");
    }
    const job = startVideoJob(png, duration);
    return send(res, 202, JSON.stringify({ id: job.id }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/video/status" && req.method === "GET") {
    const id = String(url.searchParams.get("id") || "").slice(0, 80);
    const job = videoJobs.get(id);
    if (!job) return send(res, 404, "Video job not found");
    return send(res, 200, JSON.stringify({
      status: job.status,
      progress: job.progress,
      error: job.status === "failed" ? job.error : ""
    }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/video/result" && req.method === "GET") {
    const id = String(url.searchParams.get("id") || "").slice(0, 80);
    const job = videoJobs.get(id);
    if (!job) return send(res, 404, "Video job not found");
    if (job.status === "running") return send(res, 409, "Video is still being generated");
    if (job.status === "failed") return send(res, 500, job.error || "Video conversion failed");
    videoJobs.delete(id);
    return sendDownload(res, job.video, "chroma-static.mp4", "video/mp4");
  }

  if (url.pathname === "/api/register" && req.method === "POST") {
    const body = JSON.parse(await readBody(req));
    const id = String(body.id || "").slice(0, 80);
    if (!id) return send(res, 400, "Missing device id");
    const previous = devices.get(id);
    const requestedName = String(body.name || "").trim().slice(0, 40);
    const registeredName = body.updateName && requestedName
      ? requestedName
      : previous?.name || requestedName || `设备 ${id.slice(-4)}`;
    const nextState = previous?.state || normalizeState(body.state || state);
    devices.set(id, {
      id,
      name: registeredName,
      group: previous?.group || "",
      role: String(body.role || previous?.role || "display").slice(0, 20),
      width: Number(body.width || 0),
      height: Number(body.height || 0),
      dpr: Number(body.dpr || 1),
      userAgent: String(body.userAgent || req.headers["user-agent"] || "").slice(0, 180),
      lastSeen: Date.now(),
      order: previous?.order || nextDeviceOrder++,
      state: nextState
    });
    return send(res, 200, JSON.stringify({ ok: true, name: registeredName, state: nextState }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/devices") {
    const list = Array.from(devices.values())
      .map(cleanDevice)
      .sort((a, b) => {
        const onlineDelta = Number(b.online) - Number(a.online);
        if (onlineDelta) return onlineDelta;
        return a.order - b.order;
      });
    return send(res, 200, JSON.stringify({ devices: list }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/presets" && req.method === "GET") {
    return send(res, 200, JSON.stringify({ presets }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/presets" && req.method === "POST") {
    const body = JSON.parse(await readBody(req));
    const preset = cleanPreset({
      id: `preset-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`,
      name: body.name,
      state: body.state
    });
    if (!preset) return send(res, 400, "Missing preset name");
    presets.push(preset);
    savePresets();
    return send(res, 200, JSON.stringify({ ok: true, preset, presets }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/presets/delete" && req.method === "POST") {
    const body = JSON.parse(await readBody(req));
    const id = String(body.id || "").slice(0, 80);
    const index = presets.findIndex((preset) => preset.id === id);
    if (index < 0) return send(res, 404, "Preset not found");
    presets.splice(index, 1);
    savePresets();
    return send(res, 200, JSON.stringify({ ok: true, presets }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/device-name" && req.method === "POST") {
    const body = JSON.parse(await readBody(req));
    const id = String(body.id || "").slice(0, 80);
    const name = String(body.name || "").trim().slice(0, 40);
    if (!id) return send(res, 400, "Missing device id");
    if (!name) return send(res, 400, "Missing device name");
    const device = devices.get(id);
    if (!device) return send(res, 404, "Device not found");
    device.name = name;
    devices.set(id, device);
    return send(res, 200, JSON.stringify({ ok: true, name }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/device-group" && req.method === "POST") {
    const body = JSON.parse(await readBody(req));
    const ids = Array.isArray(body.ids)
      ? body.ids.map((id) => String(id || "").slice(0, 80)).filter(Boolean).slice(0, 100)
      : [];
    const group = String(body.group || "").trim().slice(0, 40);
    if (!ids.length) return send(res, 400, "Missing device ids");
    let updated = 0;
    ids.forEach((id) => {
      const device = devices.get(id);
      if (!device) return;
      device.group = group;
      devices.set(id, device);
      updated += 1;
    });
    return send(res, 200, JSON.stringify({ ok: true, group, updated }), "application/json; charset=utf-8");
  }

  if (url.pathname === "/api/state") {
    const deviceId = url.searchParams.get("deviceId");
    if (req.method === "GET") {
      if (deviceId && devices.has(deviceId)) {
        return send(res, 200, JSON.stringify(devices.get(deviceId).state || state), "application/json; charset=utf-8");
      }
      return send(res, 200, JSON.stringify(state), "application/json; charset=utf-8");
    }
    if (req.method === "POST") {
      const next = JSON.parse(await readBody(req));
      const nextState = normalizeState(next);
      if (deviceId && devices.has(deviceId)) {
        const device = devices.get(deviceId);
        device.state = nextState;
        devices.set(deviceId, device);
      } else {
        state = nextState;
      }
      return send(res, 200, JSON.stringify({ ok: true }), "application/json; charset=utf-8");
    }
  }

  if (url.pathname === "/qr.svg") {
    const text = url.searchParams.get("text") || `http://${getLanIp()}:${PORT}${LAUNCH_PAGE}`;
    try {
      return send(res, 200, makeQrSvg(text), "image/svg+xml; charset=utf-8");
    } catch (error) {
      return send(res, 400, error.message);
    }
  }

  const fileName = path.basename(url.pathname);
  if (!PAGES.has(fileName)) return send(res, 404, "Not found");
  const filePath = path.join(WEB_ROOT, fileName);
  const ext = path.extname(filePath);
  const type = ext === ".html" ? "text/html; charset=utf-8" : ext === ".js" ? "text/javascript; charset=utf-8" : "application/octet-stream";
  send(res, 200, fs.readFileSync(filePath), type);
}

server = http.createServer((req, res) => {
  handler(req, res).catch((error) => send(res, 500, error.stack || String(error)));
}).listen(PORT, "0.0.0.0", () => {
  const url = `http://${getLanIp()}:${PORT}${LAUNCH_PAGE}`;
  console.log(`Chroma control server running: ${url}`);
});
