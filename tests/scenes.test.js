const assert = require("node:assert/strict");
const { test } = require("node:test");

const scenes = require("../src/web/markerdeck-scenes");

class FakeClassList {
  constructor() { this.values = new Set(); }
  add(...names) { names.forEach((name) => this.values.add(name)); }
  remove(...names) { names.forEach((name) => this.values.delete(name)); }
  toggle(name, force) {
    const next = force === undefined ? !this.values.has(name) : !!force;
    if (next) this.values.add(name); else this.values.delete(name);
    return next;
  }
  contains(name) { return this.values.has(name); }
}

class FakeElement {
  constructor(tagName = "div") {
    this.tagName = tagName.toUpperCase();
    this.children = [];
    this.parentNode = null;
    this.style = { setProperty: (name, value) => { this.style[name] = value; } };
    this.dataset = {};
    this.classList = new FakeClassList();
    this.attributes = new Map();
    this.listeners = new Map();
    this.hidden = false;
    this.disabled = false;
    this.value = "";
    this.textContent = "";
    this.clientWidth = 600;
    this.clientHeight = 400;
    this.offsetWidth = 126;
    this.offsetHeight = 78;
  }

  append(...nodes) {
    nodes.flat().forEach((node) => {
      if (!node) return;
      if (node.parentNode) node.parentNode.children.splice(node.parentNode.children.indexOf(node), 1);
      node.parentNode = this;
      this.children.push(node);
    });
  }

  appendChild(node) { this.append(node); return node; }

  remove() {
    this.parentNode?.children.splice(this.parentNode.children.indexOf(this), 1);
    this.parentNode = null;
  }

  contains(node) {
    return node === this || this.children.some((child) => child === node || child.contains?.(node));
  }

  setAttribute(name, value) { this.attributes.set(name, String(value)); }
  removeAttribute(name) { this.attributes.delete(name); }
  getAttribute(name) { return this.attributes.get(name) ?? null; }

  addEventListener(type, listener) {
    if (!this.listeners.has(type)) this.listeners.set(type, []);
    this.listeners.get(type).push(listener);
  }

  async dispatch(type, event = {}) {
    const next = { target: this, currentTarget: this, ...event };
    for (const listener of this.listeners.get(type) || []) await listener(next);
  }

  focus() { this.ownerDocument.activeElement = this; }
  select() {}
  setPointerCapture() {}
  releasePointerCapture() {}
  get isConnected() {
    let node = this;
    while (node?.parentNode) node = node.parentNode;
    return node === this.ownerDocument?.body;
  }
  getBoundingClientRect() { return { left: 0, top: 0, width: this.clientWidth, height: this.clientHeight }; }

  querySelectorAll(selector) {
    const matches = [];
    const matcher = (node) => {
      if (selector === ".scene-device-node" && (node.classList.contains("scene-device-node") || String(node.className || "").split(/\s+/).includes("scene-device-node"))) matches.push(node);
      node.children.forEach(matcher);
    };
    this.children.forEach(matcher);
    return matches;
  }

  querySelector(selector) {
    return this.querySelectorAll(selector)[0] || null;
  }
}

class FakeDocument extends FakeElement {
  constructor() {
    super("document");
    this.ownerDocument = this;
    this.body = new FakeElement("body");
    this.body.ownerDocument = this;
    this.activeElement = null;
    this.elements = new Map();
  }

  createElement(tagName) {
    const element = new FakeElement(tagName);
    element.ownerDocument = this;
    return element;
  }

  getElementById(id) { return this.elements.get(id) || null; }
  register(id, tagName = "div") {
    const element = this.createElement(tagName);
    element.id = id;
    this.elements.set(id, element);
    this.body.append(element);
    return element;
  }

  querySelector(selector) {
    if (selector === ".settings-column > .preset-section") return this.elements.get("presetSection") || null;
    return super.querySelector(selector);
  }
}

function makeBrowserHarness({ role = "control", stored = null } = {}) {
  const document = new FakeDocument();
  const ids = [
    "sceneSelect", "sceneMap", "sceneMetaReadout", "sceneNameReadout", "sceneSizeReadout",
    "sceneZoomReadout", "sceneEditLayoutBtn", "sceneRatioReadout", "sceneMapHint", "sceneSelectedCount",
    "sceneCurrentTarget", "sceneCurrentLocation", "selectedScreenTarget", "sceneDockTitle", "sceneDockDetail",
    "sceneClearSelectionBtn", "forceLockBtn", "sceneQuickPresets", "controlQuickconnectBtn", "closeQuickconnectBtn",
    "controlQuickconnect", "newSceneBtn", "manageScenesBtn", "sceneManagerOverlay", "sceneManagerCloseBtn",
    "sceneManagerList", "sceneCountReadout", "sceneManagerTitle", "sceneManagerMeta", "sceneManagerMode",
    "sceneForm", "sceneNameInput", "sceneWidthInput", "sceneHeightInput", "sceneUnitSelect", "sceneCreateBtn", "sceneApplyBtn",
    "sceneRenameBtn", "sceneDuplicateBtn", "sceneDeleteBtn", "presetLibraryOverlay", "closePresetLibraryBtn",
    "openPresetLibraryBtn"
  ];
  const dom = {};
  ids.forEach((id) => { dom[id] = document.register(id, id.includes("Input") ? "input" : "div"); });
  dom.sceneForm = document.register("sceneForm", "form");
  dom.sceneSelect = document.register("sceneSelect", "select");
  dom.sceneUnitSelect = document.register("sceneUnitSelect", "select");
  dom.sceneMap = document.register("sceneMap", "div");
  dom.sceneMap.clientWidth = 600;
  dom.sceneMap.clientHeight = 400;
  dom.sceneMapViewport = document.register("sceneMapViewport", "div");
  dom.sceneMapViewport.clientWidth = 640;
  dom.sceneMapViewport.clientHeight = 520;
  dom.sceneManagerList = document.register("sceneManagerList", "div");
  dom.presetLibraryBody = document.register("presetLibraryBody", "div");
  const presetSection = document.register("presetSection", "section");
  presetSection.classList.add("preset-section");
  dom.presetLibraryBody.appendChild = (node) => { dom.presetLibraryBody.append(node); };

  const storage = {
    getItem() { return stored; },
    setItem(_key, value) { stored = value; }
  };
  const state = {
    role,
    lastDevices: [],
    selectedDeviceIds: new Set(),
    scenes: [],
    presets: [],
    favoritePresetIds: new Set(),
    recentPresetIds: []
  };
  const statuses = [];
  let renderListeners = [];
  let clearSelections = 0;
  const app = {
    core: { dom, state, updateStatus: (message) => statuses.push(message) },
    canvas: { addRenderListener: (listener) => renderListeners.push(listener), renderDeviceThumbnail() {} },
    devices: {
      selectSet: async (devices) => {
        clearSelections += devices.length === 0 ? 1 : 0;
        state.selectedDeviceIds = new Set(devices.map((device) => device.id));
      }
    },
    presets: {},
    visualState: {}
  };
  const global = {
    localStorage: storage,
    confirm: () => true,
    prompt: (_message, fallback) => fallback,
    requestAnimationFrame: (callback) => callback(),
    addEventListener() {}
  };
  return { document, dom, state, app, global, statuses, getStored: () => stored, getClearSelections: () => clearSelections, renderListeners };
}

test("normalizes versioned storage safely and preserves opaque session punctuation", () => {
  const storage = {
    getItem: () => JSON.stringify({
      version: 1,
      activeSceneId: "scene:main",
      scenes: [{
        id: "scene:main",
        name: "主场景",
        width: 100000,
        height: 0.01,
        unit: "unknown",
        positions: { "screen.1:session/2": { x: 2, y: -1 }, "bad\u0000key": { x: 0.2, y: 0.3 } }
      }]
    }),
    setItem() {}
  };
  const loaded = scenes.loadDocument(storage);
  assert.equal(loaded.version, scenes.VERSION);
  assert.equal(loaded.activeSceneId, "scene:main");
  assert.equal(loaded.scenes[0].width, 100000);
  assert.equal(loaded.scenes[0].height, 0.01);
  assert.equal(loaded.scenes[0].unit, "m");
  assert.deepEqual(loaded.scenes[0].positions["screen.1:session/2"], { x: 1, y: 0 });
  assert.deepEqual(loaded.scenes[0].positions.badkey, { x: 0.2, y: 0.3 });

  const invalid = scenes.loadDocument({ getItem: () => JSON.stringify({
    version: 99,
    scenes: [{ id: "future", name: "不应加载", width: 2, height: 2 }]
  }) });
  assert.equal(invalid.scenes.length, 1);
  assert.equal(invalid.scenes[0].id, scenes.DEFAULT_SCENE.id);
  assert.equal(invalid.scenes[0].name, scenes.DEFAULT_SCENE.name);
});

test("handles invalid JSON and storage failures without losing a usable document", () => {
  const fallback = scenes.loadDocument({ getItem: () => "not-json" });
  assert.equal(fallback.version, scenes.VERSION);
  assert.equal(fallback.scenes[0].id, scenes.DEFAULT_SCENE.id);

  const throwingStorage = {
    getItem() { throw new Error("read failed"); },
    setItem() { throw new Error("write failed"); }
  };
  const loaded = scenes.loadDocument(throwingStorage);
  assert.equal(loaded.scenes.length, 1);
  const saved = scenes.saveDocument(throwingStorage, loaded);
  assert.equal(saved.version, scenes.VERSION);
  assert.equal(saved.scenes[0].id, scenes.DEFAULT_SCENE.id);
});

test("duplicates scene coordinates deeply and safely deletes the active scene", () => {
  const source = scenes.normalizeScene({
    id: "main",
    name: "主场景",
    width: 21,
    height: 9,
    positions: { "screen.1:session/2": { x: 0.25, y: 0.75 } }
  });
  const copy = scenes.duplicateScene(source, new Set(["main"]));
  assert.notEqual(copy.id, source.id);
  assert.equal(copy.width, source.width);
  assert.deepEqual(copy.positions, source.positions);
  copy.positions["screen.1:session/2"].x = 0.9;
  assert.equal(source.positions["screen.1:session/2"].x, 0.25);

  const only = scenes.normalizeDocument({ version: scenes.VERSION, activeSceneId: "main", scenes: [source] });
  assert.deepEqual(scenes.deleteScene(only, "main"), only);
  const second = scenes.createScene({ id: "second", name: "第二个", width: 4, height: 3 }, new Set(["main"]));
  const document = scenes.normalizeDocument({ version: scenes.VERSION, activeSceneId: "main", scenes: [source, second] });
  const after = scenes.deleteScene(document, "main");
  assert.equal(after.scenes.length, 1);
  assert.equal(after.activeSceneId, second.id);
  assert.equal(after.scenes[0].id, second.id);
});

test("createBrowserModule keeps new-scene fields and submits through the form event", async () => {
  const harness = makeBrowserHarness();
  global.document = harness.document;
  global.window = harness.global;
  const module = scenes.createBrowserModule(harness.global, harness.app);
  module.init();

  await harness.dom.newSceneBtn.dispatch("click");
  assert.equal(harness.dom.sceneManagerMode.textContent, "新建场景");
  harness.dom.sceneNameInput.value = "拍摄现场";
  harness.dom.sceneWidthInput.value = "21";
  harness.dom.sceneHeightInput.value = "9";
  harness.dom.sceneUnitSelect.value = "m";
  await harness.dom.sceneForm.dispatch("submit", { preventDefault() {} });
  const created = module.getState().currentScene;
  assert.equal(created.name, "拍摄现场");
  assert.equal(created.width, 21);
  assert.equal(created.height, 9);
  assert.equal(harness.state.activeSceneId, created.id);

  await harness.dom.manageScenesBtn.dispatch("click");
  harness.dom.sceneWidthInput.value = "32";
  await harness.dom.sceneForm.dispatch("submit", { preventDefault() {} });
  assert.equal(module.getState().currentScene.width, 32);
  assert.equal(harness.statuses.at(-1), "场景设置已保存");
});

test("does not move or open the preset library outside control mode", () => {
  const harness = makeBrowserHarness({ role: "local" });
  global.document = harness.document;
  global.window = harness.global;
  harness.dom.presetLibraryOverlay.hidden = true;
  document.body.classList.add("mobile-preset-management");
  const presetSection = document.getElementById("presetSection");
  const originalParent = presetSection.parentNode;
  const module = scenes.createBrowserModule(harness.global, harness.app);
  module.init();
  assert.equal(presetSection.parentNode, originalParent);
  assert.equal(harness.dom.presetLibraryOverlay.hidden, true);
  module.openPresetLibrary();
  assert.equal(harness.dom.presetLibraryOverlay.hidden, true);
  assert.equal(document.body.classList.contains("mobile-preset-management"), false);
});

test("scene selection clear delegates to the device selection set", async () => {
  const harness = makeBrowserHarness();
  global.document = harness.document;
  global.window = harness.global;
  const module = scenes.createBrowserModule(harness.global, harness.app);
  module.init();
  await harness.dom.sceneClearSelectionBtn.dispatch("click");
  assert.equal(harness.getClearSelections(), 1);
});

test("keeps the scene node during a device refresh while dragging and persists its position", async () => {
  const harness = makeBrowserHarness();
  global.document = harness.document;
  global.window = harness.global;
  const device = {
    id: "screen.1:session/2",
    name: "投放页",
    width: 9,
    height: 16,
    online: true,
    state: { bgColor: "#00ff00", crossColor: "#0040d8" }
  };
  harness.state.lastDevices = [device];
  harness.state.selectedDeviceIds.add(device.id);
  const module = scenes.createBrowserModule(harness.global, harness.app);
  module.init();
  await harness.dom.sceneEditLayoutBtn.dispatch("click");
  const node = harness.dom.sceneMap.querySelector(".scene-device-node");
  assert.ok(node, "scene node should render before dragging");
  const pointerEvent = (clientX, clientY) => ({
    button: 0,
    pointerId: 7,
    clientX,
    clientY,
    preventDefault() {},
    stopPropagation() {}
  });
  await node.dispatch("pointerdown", pointerEvent(120, 100));
  await node.dispatch("pointermove", pointerEvent(510, 310));
  await module.onDevicesChanged();
  assert.equal(harness.dom.sceneMap.children.includes(node), true);
  await node.dispatch("pointerup", pointerEvent(510, 310));
  const point = module.getState().currentScene.positions[device.id];
  assert.ok(point.x > 0.8 && point.x < 0.9);
  assert.ok(point.y > 0.7 && point.y < 0.85);
  const persisted = JSON.parse(harness.getStored());
  assert.deepEqual(persisted.scenes[0].positions[device.id], point);
});
