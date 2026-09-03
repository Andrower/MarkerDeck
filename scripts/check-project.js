#!/usr/bin/env node

const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");
const { spawnSync } = require("node:child_process");
const vm = require("node:vm");

const root = path.resolve(__dirname, "..");
const screenStylesheets = [
  "src/web/markerdeck-base.css",
  "src/web/markerdeck-control.css",
  "src/web/markerdeck-mobile.css"
];
const screenScripts = [
  "src/web/markerdeck-core.js",
  "src/web/markerdeck-api.js",
  "src/web/markerdeck-canvas.js",
  "src/web/markerdeck-export.js",
  "src/web/markerdeck-presets.js",
  "src/web/markerdeck-devices.js",
  "src/web/markerdeck-projection.js",
  "src/web/markerdeck-settings.js",
  "src/web/markerdeck-launcher.js",
  "src/web/markerdeck-bootstrap.js"
];
const screenAssets = [...screenStylesheets, ...screenScripts];
const requiredFiles = [
  "README.md",
  "LICENSE",
  "src/markerdeck-server.js",
  "src/markerdeck-host-discovery.js",
  "src/web/markerdeck-screen.html",
  "src/web/markerdeck-launch.html",
  ...screenAssets,
  "desktop/electron/main.js",
  "desktop/electron/preload.js",
  "desktop/electron/package.json",
  "platform/macos/start-markerdeck-server.command",
  "platform/windows/start-markerdeck-server.bat",
  "tests/host-discovery.test.js"
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
  "android/app/src/main/kotlin/com/andrower/markerdeck/ProjectionEmergencyControls.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/DiscoveryProtocol.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/DiscoveryScanner.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/LifecycleRecovery.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/PermissionSettings.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/ServiceUrl.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/Settings.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/SettingsRepository.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/AndroidHostServer.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/HostLifecycleController.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/MarkerDeckHostRuntime.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/MarkerDeckHostService.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/HostMode.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/HostNetworkAddress.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/HostPreferencesPersistence.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/HostProtocol.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/HostSseHub.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/HostStateStore.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/HostUdpResponder.kt",
  "android/app/src/main/kotlin/com/andrower/markerdeck/QrSvg.kt",
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
  "android/app/src/test/kotlin/com/andrower/markerdeck/DiscoveryProtocolTest.kt",
  "android/app/src/test/kotlin/com/andrower/markerdeck/LifecycleRecoveryTest.kt",
  "android/app/src/test/kotlin/com/andrower/markerdeck/PermissionSettingsTest.kt",
  "android/app/src/test/kotlin/com/andrower/markerdeck/SettingsTest.kt",
  "android/app/src/test/kotlin/com/andrower/markerdeck/ProjectionEmergencyControlsTest.kt",
  "android/app/src/test/kotlin/com/andrower/markerdeck/HostProtocolTest.kt",
  "android/app/src/test/kotlin/com/andrower/markerdeck/HostStateStoreTest.kt",
  "android/app/src/test/kotlin/com/andrower/markerdeck/AndroidHostServerTest.kt",
  "android/README.md",
  ".github/workflows/android-check.yml"
];

requiredFiles.concat(androidRequiredFiles).forEach((relativePath) => {
  assert.ok(fs.existsSync(path.join(root, relativePath)), `Missing required file: ${relativePath}`);
});

const androidBuild = fs.readFileSync(path.join(root, "android/app/build.gradle.kts"), "utf8");
assert.match(androidBuild, /assets\.srcDir\(rootProject\.file\("\.\.\/src\/web"\)\)/,
  "Android assets must use the shared src/web sourceSet");
assert.match(androidBuild, /org\.nanohttpd:nanohttpd:/, "Android host must use NanoHTTPD");
assert.match(androidBuild, /com\.google\.zxing:core:/, "Android host must use ZXing core");

const androidManifest = fs.readFileSync(
  path.join(root, "android/app/src/main/AndroidManifest.xml"),
  "utf8"
);
assert.match(androidManifest, /android\.permission\.FOREGROUND_SERVICE"/,
  "Android host must declare the base foreground-service permission");
assert.match(androidManifest, /android\.permission\.FOREGROUND_SERVICE_CONNECTED_DEVICE"/,
  "Android host must declare the connected-device foreground-service permission");
assert.match(androidManifest, /android\.permission\.POST_NOTIFICATIONS"/,
  "Android host notification stop action must declare notification permission");
assert.match(androidManifest, /android:name="\.MarkerDeckHostService"[\s\S]*?android:foregroundServiceType="connectedDevice"/,
  "Android host service must use the connectedDevice foreground-service type");

const javascriptFiles = [
  "src/markerdeck-server.js",
  "desktop/electron/main.js",
  "desktop/electron/preload.js",
  ...screenAssets.filter((relativePath) => relativePath.endsWith(".js"))
];

javascriptFiles.forEach((relativePath) => {
  const result = spawnSync(process.execPath, ["--check", path.join(root, relativePath)], { encoding: "utf8" });
  assert.equal(result.status, 0, result.stderr || `Syntax check failed: ${relativePath}`);
});

["package.json", "desktop/electron/package.json"].forEach((relativePath) => {
  JSON.parse(fs.readFileSync(path.join(root, relativePath), "utf8"));
});

const electronPackage = JSON.parse(
  fs.readFileSync(path.join(root, "desktop/electron/package.json"), "utf8")
);
const electronServerResources = electronPackage.build.extraResources
  .find((resource) => resource.to === "server")?.filter || [];
assert.ok(
  electronServerResources.includes("markerdeck-host-discovery.js"),
  "Electron package must include the desktop host discovery module"
);

const htmlFiles = [
  "src/web/markerdeck-screen.html",
  "src/web/markerdeck-launch.html"
];

function getAttribute(tag, name) {
  return tag.match(new RegExp(`\\b${name}\\s*=\\s*["']([^"']+)["']`, "i"))?.[1] || "";
}

function checkLocalReference(htmlPath, reference, kind) {
  const cleanReference = reference.split(/[?#]/, 1)[0];
  assert.ok(cleanReference, `Empty ${kind} reference: ${htmlPath}`);
  assert.ok(!/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(cleanReference), `External ${kind} reference is not supported: ${htmlPath} -> ${reference}`);
  const resolvedPath = path.resolve(path.dirname(path.join(root, htmlPath)), cleanReference);
  assert.ok(resolvedPath.startsWith(`${root}${path.sep}`), `Out-of-tree ${kind} reference: ${htmlPath} -> ${reference}`);
  assert.ok(fs.existsSync(resolvedPath), `Missing ${kind} reference: ${htmlPath} -> ${reference}`);
}

htmlFiles.forEach((relativePath) => {
  const html = fs.readFileSync(path.join(root, relativePath), "utf8");
  const scriptTags = [...html.matchAll(/<script\b[^>]*>/gi)].map((match) => match[0]);
  scriptTags
    .map((tag) => getAttribute(tag, "src"))
    .filter(Boolean)
    .forEach((reference) => checkLocalReference(relativePath, reference, "script"));
  [...html.matchAll(/<link\b[^>]*>/gi)]
    .map((match) => match[0])
    .filter((tag) => /\brel\s*=\s*["'][^"']*\bstylesheet\b/i.test(tag))
    .map((tag) => getAttribute(tag, "href"))
    .filter(Boolean)
    .forEach((reference) => checkLocalReference(relativePath, reference, "stylesheet"));

  const inlineScripts = [...html.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)]
    .filter((match) => !getAttribute(match[1], "src"));
  if (relativePath.endsWith("markerdeck-launch.html")) {
    assert.ok(inlineScripts.length > 0, `No inline scripts found: ${relativePath}`);
  }
  inlineScripts.forEach((match, index) => {
    new vm.Script(match[2], { filename: `${relativePath}:inline-${index + 1}` });
  });
});

const displayPage = fs.readFileSync(path.join(root, "src/web/markerdeck-screen.html"), "utf8");
const projectionModule = fs.readFileSync(path.join(root, "src/web/markerdeck-projection.js"), "utf8");
const linkedStylesheets = [...displayPage.matchAll(/<link\b[^>]*>/gi)]
  .map((match) => match[0])
  .filter((tag) => /\brel\s*=\s*["'][^"']*\bstylesheet\b/i.test(tag))
  .map((tag) => getAttribute(tag, "href"));
assert.deepEqual(
  linkedStylesheets,
  screenStylesheets.map((relativePath) => path.basename(relativePath)),
  "Screen stylesheets must retain their explicit cascade order"
);
assert.match(displayPage, /markerdeck-projection\.js/);
assert.match(projectionModule, /androidProvidedDeviceName/);
assert.match(projectionModule, /saveLocalDeviceName\(providedDeviceName\)/);
assert.match(projectionModule, /await app\.core\.requestDisplayName\(\)/);

const launcherMode = fs.statSync(path.join(root, "platform/macos/start-markerdeck-server.command")).mode;
assert.ok((launcherMode & 0o111) !== 0, "macOS launcher must be executable");

console.log("Project structure and syntax checks passed.");
