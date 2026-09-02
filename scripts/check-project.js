#!/usr/bin/env node

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const requiredFiles = [
  "README.md",
  "LICENSE",
  "src/markerdeck-server.js",
  "src/web/markerdeck-screen.html",
  "src/web/markerdeck-launch.html",
  "desktop/electron/main.js",
  "desktop/electron/preload.js",
  "desktop/electron/package.json",
  "platform/macos/start-markerdeck-server.command",
  "platform/windows/start-markerdeck-server.bat"
];

requiredFiles.forEach((relativePath) => {
  assert.ok(fs.existsSync(path.join(root, relativePath)), `Missing required file: ${relativePath}`);
});

const javascriptFiles = [
  "src/markerdeck-server.js",
  "desktop/electron/main.js",
  "desktop/electron/preload.js"
];

javascriptFiles.forEach((relativePath) => {
  const result = spawnSync(process.execPath, ["--check", path.join(root, relativePath)], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr || `Syntax check failed: ${relativePath}`);
});

["package.json", "desktop/electron/package.json"].forEach((relativePath) => {
  JSON.parse(fs.readFileSync(path.join(root, relativePath), "utf8"));
});

const htmlFiles = [
  "src/web/markerdeck-screen.html",
  "src/web/markerdeck-launch.html"
];

htmlFiles.forEach((relativePath) => {
  const html = fs.readFileSync(path.join(root, relativePath), "utf8");
  const scripts = [...html.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)];
  assert.ok(scripts.length > 0, `No inline scripts found: ${relativePath}`);
  scripts.forEach((match, index) => {
    new vm.Script(match[1], { filename: `${relativePath}:inline-${index + 1}` });
  });
});

const launcherMode = fs.statSync(path.join(root, "platform/macos/start-markerdeck-server.command")).mode;
assert.ok((launcherMode & 0o111) !== 0, "macOS launcher must be executable");

console.log("Project structure and syntax checks passed.");
