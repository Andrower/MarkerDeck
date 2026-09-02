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

const androidRequiredFiles = [
  "android/settings.gradle.kts",
  "android/build.gradle.kts",
  "android/gradle.properties",
  "android/gradlew",
  "android/gradlew.bat",
  "android/gradle/wrapper/gradle-wrapper.jar",
  "android/gradle/wrapper/gradle-wrapper.properties",
  "android/app/build.gradle.kts",
  "android/app/src/main/AndroidManifest.xml",
  "android/app/src/main/kotlin/com/andrower/markerdeck/MainActivity.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/LifecycleRecovery.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/ServiceUrl.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/Settings.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/SettingsRepository.kt",
  "android/app/src/main/res/drawable/ic_launcher_foreground.xml",
  "android/app/src/main/res/drawable/ic_launcher_monochrome.xml",
  "android/app/src/main/res/drawable/markerdeck_input.xml",
  "android/app/src/main/res/drawable/markerdeck_primary_button.xml",
  "android/app/src/main/res/drawable/markerdeck_secondary_button.xml",
  "android/app/src/main/res/drawable/markerdeck_status_panel.xml",
  "android/app/src/main/res/layout/activity_main.xml",
  "android/app/src/main/res/mipmap-anydpi/ic_launcher.xml",
  "android/app/src/main/res/values/colors.xml",
  "android/app/src/main/res/values/strings.xml",
  "android/app/src/main/res/values/themes.xml",
  "android/app/src/main/res/xml/backup_rules.xml",
  "android/app/src/main/res/xml/data_extraction_rules.xml",
  "android/app/src/test/kotlin/com/andrower/markerdeck/ServiceUrlTest.kt",
  "android/app/src/test/kotlin/com/andrower/markerdeck/LifecycleRecoveryTest.kt",
  "android/app/src/test/kotlin/com/andrower/markerdeck/SettingsTest.kt",
  "android/README.md",
  ".github/workflows/android-check.yml"
];

requiredFiles.concat(androidRequiredFiles).forEach((relativePath) => {
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

const displayPage = fs.readFileSync(path.join(root, "src/web/markerdeck-screen.html"), "utf8");
assert.match(displayPage, /androidDeviceName/);
assert.match(displayPage, /saveLocalDeviceName\(providedDeviceName\)/);
assert.match(displayPage, /await requestDisplayName\(\)/);

const launcherMode = fs.statSync(path.join(root, "platform/macos/start-markerdeck-server.command")).mode;
assert.ok((launcherMode & 0o111) !== 0, "macOS launcher must be executable");

console.log("Project structure and syntax checks passed.");
