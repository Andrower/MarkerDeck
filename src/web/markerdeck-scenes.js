(function (root, factory) {
  "use strict";

  const pure = factory();
  if (typeof module === "object" && module.exports) {
    module.exports = pure;
    return;
  }

  const app = root.MarkerDeck = root.MarkerDeck || {};
  app.scenes = pure.createBrowserModule(root, app);
})(typeof window !== "undefined" ? window : globalThis, factory);

function factory() {
  "use strict";

  const VERSION = 1;
  const STORAGE_KEY = "markerdeckScenes:v1";
  const DEFAULT_SCENE = Object.freeze({
    id: "scene-default",
    name: "默认场景",
    width: 12,
    height: 8,
    unit: "m",
    positions: {}
  });
  const VALID_UNITS = new Set(["m", "cm", "mm", "ft", "in", "无单位", "米", "厘米", "毫米"]);

  function finiteNumber(value, fallback, min = -Infinity, max = Infinity) {
    const number = Number(value);
    if (!Number.isFinite(number)) return fallback;
    return Math.min(max, Math.max(min, number));
  }

  function cleanName(value, fallback = DEFAULT_SCENE.name) {
    const name = String(value ?? "").trim().replace(/\s+/g, " ").slice(0, 80);
    return name || fallback;
  }

  function cleanId(value, fallback) {
    // Session ids are opaque protocol values.  Keep punctuation such as dots,
    // colons and UUID separators so saved positions remain addressable after a
    // refresh; only control characters are unsafe in a DOM/storage key.
    const id = String(value ?? "").trim().replace(/[\u0000-\u001F\u007F]/g, "").slice(0, 160);
    return id || fallback;
  }

  function normalizePoint(point, fallback = { x: 0.5, y: 0.5 }) {
    if (Array.isArray(point)) {
      return {
        x: finiteNumber(point[0], fallback.x, 0, 1),
        y: finiteNumber(point[1], fallback.y, 0, 1)
      };
    }
    return {
      x: finiteNumber(point?.x, fallback.x, 0, 1),
      y: finiteNumber(point?.y, fallback.y, 0, 1)
    };
  }

  function denormalizePoint(point, width, height) {
    const normalized = normalizePoint(point);
    return {
      x: normalized.x * Math.max(0, Number(width) || 0),
      y: normalized.y * Math.max(0, Number(height) || 0)
    };
  }

  function normalizeUnit(value) {
    const unit = String(value ?? "m").trim();
    return VALID_UNITS.has(unit) ? unit : "m";
  }

  function normalizePositions(value) {
    if (!value || typeof value !== "object" || Array.isArray(value)) return {};
    return Object.fromEntries(Object.entries(value)
      .map(([id, point]) => [cleanId(id, ""), normalizePoint(point)])
      .filter(([id]) => !!id));
  }

  function normalizeScene(value, index = 0) {
    const fallbackId = index === 0 ? DEFAULT_SCENE.id : `scene-${index + 1}`;
    const width = finiteNumber(value?.width, DEFAULT_SCENE.width, 0.01, 100000);
    const height = finiteNumber(value?.height, DEFAULT_SCENE.height, 0.01, 100000);
    return {
      id: cleanId(value?.id, fallbackId),
      name: cleanName(value?.name, index === 0 ? DEFAULT_SCENE.name : `场景 ${index + 1}`),
      width,
      height,
      unit: normalizeUnit(value?.unit),
      positions: normalizePositions(value?.positions),
      updatedAt: Number.isFinite(Number(value?.updatedAt)) ? Number(value.updatedAt) : Date.now()
    };
  }

  function uniqueSceneIds(scenes) {
    const seen = new Set();
    return scenes.map((scene, index) => {
      let id = cleanId(scene.id, `scene-${index + 1}`);
      let suffix = 2;
      while (seen.has(id)) id = `${cleanId(scene.id, `scene-${index + 1}`)}-${suffix++}`;
      seen.add(id);
      return { ...scene, id };
    });
  }

  function normalizeDocument(value) {
    const rawScenes = Array.isArray(value) ? value : value?.scenes;
    const scenes = uniqueSceneIds((Array.isArray(rawScenes) ? rawScenes : [])
      .filter((scene) => scene && typeof scene === "object")
      .map(normalizeScene));
    const safeScenes = scenes.length ? scenes : [normalizeScene(DEFAULT_SCENE, 0)];
    const activeSceneId = safeScenes.some((scene) => scene.id === value?.activeSceneId)
      ? value.activeSceneId
      : safeScenes[0].id;
    return { version: VERSION, activeSceneId, scenes: safeScenes };
  }

  function validateDocument(value) {
    const normalized = normalizeDocument(value);
    const valid = !!value && typeof value === "object" && !Array.isArray(value) &&
      Number(value.version) === VERSION && Array.isArray(value.scenes) && value.scenes.length > 0;
    return { valid, document: normalized };
  }

  function loadDocument(storage) {
    try {
      const raw = storage?.getItem(STORAGE_KEY);
      if (!raw) return normalizeDocument(null);
      const parsed = JSON.parse(raw);
      const checked = validateDocument(parsed);
      return checked.valid ? checked.document : normalizeDocument(null);
    } catch (_) {
      return normalizeDocument(null);
    }
  }

  function saveDocument(storage, value) {
    const document = normalizeDocument(value);
    try {
      storage?.setItem(STORAGE_KEY, JSON.stringify(document));
    } catch (_) {}
    return document;
  }

  function createScene(value, existingIds = new Set()) {
    const now = Date.now();
    const baseId = cleanId(value?.id, `scene-${now.toString(36)}`);
    let id = baseId;
    let suffix = 2;
    while (existingIds.has(id)) id = `${baseId}-${suffix++}`;
    return normalizeScene({
      id,
      name: cleanName(value?.name, "新场景"),
      width: value?.width,
      height: value?.height,
      unit: value?.unit,
      positions: value?.positions,
      updatedAt: now
    });
  }

  function duplicateScene(scene, existingIds = new Set(), name) {
    const copy = createScene({
      name: name || `${cleanName(scene?.name, "场景")} 副本`,
      width: scene?.width,
      height: scene?.height,
      unit: scene?.unit,
      positions: scene?.positions
    }, existingIds);
    return copy;
  }

  function deleteScene(document, sceneId) {
    const normalized = normalizeDocument(document);
    if (normalized.scenes.length <= 1) return normalized;
    const scenes = normalized.scenes.filter((scene) => scene.id !== String(sceneId));
    return normalizeDocument({
      ...normalized,
      scenes,
      activeSceneId: scenes.some((scene) => scene.id === normalized.activeSceneId)
        ? normalized.activeSceneId
        : scenes[0]?.id
    });
  }

  function copyScene(scene) {
    return normalizeScene(JSON.parse(JSON.stringify(scene || DEFAULT_SCENE)));
  }

  function createBrowserModule(global, app) {
    const dom = () => app.core?.dom || {};
    const coreState = () => app.core?.state || {};
    const storage = (() => {
      try {
        return global.localStorage;
      } catch (_) {
        return null;
      }
    })();
    const sceneState = {
      document: loadDocument(storage),
      activeSceneId: "",
      zoom: 1,
      density: "compact",
      editing: false,
      drag: null,
      positionsDirty: false,
      groupHeaders: new Map(),
      managerMode: "edit",
      initialized: false,
      renderPending: false
    };
    sceneState.activeSceneId = sceneState.document.activeSceneId;

    function currentScene() {
      return sceneState.document.scenes.find((scene) => scene.id === sceneState.activeSceneId) ||
        sceneState.document.scenes[0];
    }

    function persist() {
      sceneState.document = saveDocument(storage, {
        ...sceneState.document,
        activeSceneId: sceneState.activeSceneId
      });
      syncCoreState();
    }

    function syncCoreState() {
      const state = coreState();
      state.scenes = sceneState.document.scenes;
      state.activeSceneId = sceneState.activeSceneId;
      state.sceneZoom = sceneState.zoom;
      state.sceneDensity = sceneState.density;
      state.sceneEditing = sceneState.editing;
    }

    function ensurePosition(scene, device, index, count) {
      const id = String(device?.id || "");
      const existing = scene.positions[id];
      if (existing) return normalizePoint(existing);
      const columns = Math.max(1, Math.ceil(Math.sqrt(Math.max(1, count))));
      const rows = Math.max(1, Math.ceil(Math.max(1, count) / columns));
      const column = index % columns;
      const row = Math.floor(index / columns);
      const x = columns === 1 ? 0.5 : 0.14 + (column / (columns - 1)) * 0.72;
      const y = rows === 1 ? 0.5 : 0.16 + (row / (rows - 1)) * 0.68;
      const point = normalizePoint({ x, y });
      scene.positions[id] = point;
      sceneState.positionsDirty = true;
      return point;
    }

    function mapElement() {
      return dom().sceneMap;
    }

    function updateStatus(text) {
      app.core?.updateStatus?.(text);
    }

    function sceneUnitText(scene) {
      const unit = scene?.unit || "m";
      const labels = { m: "米", cm: "厘米", mm: "毫米", ft: "英尺", in: "英寸", "无单位": "无单位", 米: "米", 厘米: "厘米", 毫米: "毫米" };
      return labels[unit] || unit;
    }

    function formatSceneMeta(scene) {
      if (!scene) return "暂无场景";
      return `${scene.width} × ${scene.height} ${sceneUnitText(scene)}`;
    }

    function sceneSelectOptions() {
      const select = dom().sceneSelect;
      if (!select) return;
      const current = sceneState.activeSceneId;
      select.textContent = "";
      sceneState.document.scenes.forEach((scene) => {
        const option = document.createElement("option");
        option.value = scene.id;
        option.textContent = scene.name;
        select.append(option);
      });
      select.value = sceneState.document.scenes.some((scene) => scene.id === current)
        ? current
        : sceneState.document.scenes[0]?.id || "";
    }

    function updateMapReadouts() {
      const scene = currentScene();
      const d = dom();
      const ratio = scene ? Number(scene.width) / Number(scene.height) : 0;
      const extremeRatio = Number.isFinite(ratio) && (ratio < 0.125 || ratio > 8);
      const ratioNotice = extremeRatio ? " · 比例较大，卡片可用空间有限" : "";
      if (d.sceneNameReadout) d.sceneNameReadout.textContent = scene?.name || "暂无场景";
      if (d.sceneMetaReadout) d.sceneMetaReadout.textContent = scene ? `${formatSceneMeta(scene)} · 等比例显示${ratioNotice}` : "创建一个场景开始布局";
      if (d.sceneSizeReadout) d.sceneSizeReadout.textContent = scene ? `${formatSceneMeta(scene)} · ${Number(scene.width / scene.height).toFixed(2)}:1` : "";
      if (d.sceneZoomReadout) d.sceneZoomReadout.textContent = `${Math.round(sceneState.zoom * 100)}%`;
      if (d.sceneEditLayoutBtn) {
        d.sceneEditLayoutBtn.textContent = sceneState.editing ? "完成布局" : "编辑布局";
        d.sceneEditLayoutBtn.setAttribute("aria-pressed", String(sceneState.editing));
      }
      if (d.sceneRatioReadout) {
        d.sceneRatioReadout.textContent = scene ? `${Number(scene.width / scene.height).toFixed(2)}:1${extremeRatio ? " · 极端比例" : ""}` : "固定比例预览";
      }
      if (d.sceneMapHint) d.sceneMapHint.textContent = sceneState.editing
        ? `拖动屏幕卡片调整位置，完成后自动保存到当前场景${extremeRatio ? " · 极端比例下卡片可能较小" : ""}`
        : `点击屏幕卡片选择投放目标 · 布局按场景保存${extremeRatio ? " · 极端比例下卡片可能较小" : ""}`;
    }

    function updateSelectedReadouts() {
      const d = dom();
      const state = coreState();
      const devices = Array.isArray(state.lastDevices) ? state.lastDevices : [];
      const selected = devices.filter((device) => state.selectedDeviceIds?.has(device.id));
      const names = selected.map((device) => device.name || device.id).filter(Boolean);
      if (d.sceneSelectedCount) d.sceneSelectedCount.textContent = `已选 ${selected.length}`;
      if (d.sceneCurrentTarget) d.sceneCurrentTarget.textContent = names.length
        ? `当前选择：${names.slice(0, 3).join(" + ")}${names.length > 3 ? ` 等 ${names.length} 个` : ""}`
        : "当前选择：未选择屏幕";
      if (d.sceneCurrentLocation) d.sceneCurrentLocation.textContent = names.length
        ? `${currentScene()?.name || "当前场景"} · ${selected.length} 个投放页面`
        : "请从设备列表或场景平面图选择投放目标";
      if (d.selectedScreenTarget) d.selectedScreenTarget.textContent = names.length
        ? names.join("、")
        : "当前未选择设备";
      if (d.sceneDockTitle) d.sceneDockTitle.textContent = `已选择 ${selected.length} 个屏幕`;
      if (d.sceneDockDetail) d.sceneDockDetail.textContent = names.length
        ? `${names.join("、")} · 在线状态随设备实时更新`
        : "从设备列表或场景平面图选择投放目标";
      if (d.sceneClearSelectionBtn) d.sceneClearSelectionBtn.disabled = selected.length === 0;
      if (d.forceLockBtn) d.forceLockBtn.disabled = selected.length === 0;
    }

    async function toggleDevice(device) {
      if (!device) return;
      if (typeof app.devices?.toggleDevice === "function") await app.devices.toggleDevice(device);
      else if (typeof app.devices?.selectSet === "function") await app.devices.selectSet([device]);
      render();
    }

    function setNodePosition(node, point) {
      const normalized = normalizePoint(point);
      const map = mapElement();
      // Keep the centre of a card inside the map even when a previously saved
      // position is exactly on an edge.  The persisted normalized coordinate is
      // still retained; this only protects the visual card bounds.
      const rect = map?.getBoundingClientRect?.();
      const width = node?.offsetWidth || 126;
      const height = node?.offsetHeight || 78;
      const marginX = rect?.width ? Math.min(0.42, (width / 2 + 4) / rect.width) : 0.08;
      const marginY = rect?.height ? Math.min(0.42, (height / 2 + 4) / rect.height) : 0.1;
      const x = Math.min(1 - marginX, Math.max(marginX, normalized.x));
      const y = Math.min(1 - marginY, Math.max(marginY, normalized.y));
      node.style.left = `${x * 100}%`;
      node.style.top = `${y * 100}%`;
    }

    function createNode(device, index, count, scene) {
      const node = document.createElement("button");
      node.type = "button";
      node.className = "scene-device-node";
      node.dataset.deviceId = device.id;
      const position = ensurePosition(scene, device, index, count);
      node.style.left = `${position.x * 100}%`;
      node.style.top = `${position.y * 100}%`;
      node.style.setProperty("--node-index", String(index));
      node.classList.toggle("is-selected", coreState().selectedDeviceIds?.has(device.id));
      node.classList.toggle("is-offline", !device.online);
      node.classList.toggle("is-dragging", sceneState.drag?.id === device.id);
      const thumbFrame = document.createElement("span");
      thumbFrame.className = "scene-device-thumb-frame";
      const thumbnail = document.createElement("canvas");
      thumbnail.className = "scene-device-thumb";
      thumbFrame.append(thumbnail);
      const copy = document.createElement("span");
      copy.className = "scene-device-copy";
      const name = document.createElement("strong");
      name.textContent = device.name || device.id;
      name.title = name.textContent;
      const status = document.createElement("span");
      status.textContent = device.online ? "在线" : "离线";
      copy.append(name, status);
      const dot = document.createElement("span");
      dot.className = `scene-device-status${device.online ? "" : " offline"}`;
      dot.setAttribute("aria-label", device.online ? "在线" : "离线");
      node.append(thumbFrame, copy, dot);
      node.setAttribute("aria-label", `${name.textContent}，${device.online ? "在线" : "离线"}`);
      node.setAttribute("aria-pressed", String(coreState().selectedDeviceIds?.has(device.id)));
      node.addEventListener("click", () => {
        if (sceneState.drag || node.dataset.justDragged === "true") {
          delete node.dataset.justDragged;
          return;
        }
        toggleDevice(device).catch(() => {});
      });
      node.addEventListener("pointerdown", (event) => {
        if (!sceneState.editing || event.button !== 0) return;
        event.preventDefault();
        event.stopPropagation();
        sceneState.drag = { id: device.id, pointerId: event.pointerId, moved: false };
        node.setPointerCapture?.(event.pointerId);
        updateNodePositionFromPointer(event, node);
        node.classList.add("is-dragging");
      });
      node.addEventListener("pointermove", (event) => {
        if (!sceneState.drag || sceneState.drag.id !== device.id || sceneState.drag.pointerId !== event.pointerId) return;
        event.preventDefault();
        sceneState.drag.moved = true;
        updateNodePositionFromPointer(event, node);
      });
      const finishDrag = (event) => {
        if (!sceneState.drag || sceneState.drag.id !== device.id || sceneState.drag.pointerId !== event.pointerId) return;
        const moved = sceneState.drag.moved;
        node.releasePointerCapture?.(event.pointerId);
        sceneState.drag = null;
        if (moved) node.dataset.justDragged = "true";
        persist();
        render();
      };
      node.addEventListener("pointerup", finishDrag);
      node.addEventListener("pointercancel", finishDrag);
      return node;
    }

    function updateNodePositionFromPointer(event, node) {
      const map = mapElement();
      const scene = currentScene();
      if (!map || !scene || !sceneState.drag) return;
      const rect = map.getBoundingClientRect();
      if (!rect.width || !rect.height) return;
      const point = normalizePoint({
        x: (event.clientX - rect.left) / rect.width,
        y: (event.clientY - rect.top) / rect.height
      });
      scene.positions[sceneState.drag.id] = point;
      setNodePosition(node, point);
    }

    function refreshExistingNodes(devices) {
      const byId = new Map(devices.map((device) => [String(device.id), device]));
      const map = mapElement();
      if (!map) return;
      map.querySelectorAll(".scene-device-node").forEach((node) => {
        const device = byId.get(String(node.dataset.deviceId));
        if (!device) return;
        node.classList.toggle("is-selected", coreState().selectedDeviceIds?.has(device.id));
        node.classList.toggle("is-offline", !device.online);
        node.setAttribute("aria-pressed", String(coreState().selectedDeviceIds?.has(device.id)));
        const copy = node.querySelector(".scene-device-copy");
        const name = copy?.querySelector("strong");
        const status = copy?.querySelector("span");
        if (name) name.textContent = device.name || device.id;
        if (status) status.textContent = device.online ? "在线" : "离线";
        const dot = node.querySelector(".scene-device-status");
        if (dot) dot.className = `scene-device-status${device.online ? "" : " offline"}`;
        const thumbnail = node.querySelector(".scene-device-thumb");
        if (thumbnail?.isConnected) app.canvas?.renderDeviceThumbnail?.(thumbnail, device.state || app.core.readState(), device.width, device.height);
      });
    }

    function fitMapToViewport(scene) {
      const d = dom();
      const map = d.sceneMap;
      const viewport = d.sceneMapViewport;
      if (!map || !viewport || !scene) return;
      const viewportRect = viewport.getBoundingClientRect?.();
      if (!viewportRect?.width || !viewportRect?.height) return;
      // Keep the authored ratio exact.  Clamping here makes an extreme but
      // valid portrait/landscape scene look like a different floor plan;
      // fitting the largest rectangle inside the viewport already keeps both
      // dimensions bounded without changing that ratio.
      const ratio = Number(scene.width) / Number(scene.height);
      if (!Number.isFinite(ratio) || ratio <= 0) return;
      const availableWidth = Math.max(1, viewportRect.width - 36);
      const availableHeight = Math.max(1, viewportRect.height - 70);
      const width = Math.min(availableWidth, availableHeight * ratio);
      const height = width / ratio;
      map.style.maxHeight = "none";
      map.style.width = `${width}px`;
      map.style.height = `${height}px`;
    }

    function render() {
      syncCoreState();
      const scene = currentScene();
      const d = dom();
      sceneSelectOptions();
      updateMapReadouts();
      updateSelectedReadouts();
      if (!scene || !d.sceneMap) return;
      if (sceneState.drag) {
        // Device heartbeats can arrive while a card is being dragged.  Do not
        // replace the DOM under the active pointer; update lightweight status
        // fields and reconcile after pointerup.
        sceneState.renderPending = true;
        refreshExistingNodes(Array.isArray(coreState().lastDevices) ? coreState().lastDevices : []);
        return;
      }
      d.sceneMap.style.aspectRatio = `${scene.width} / ${scene.height}`;
      d.sceneMap.style.setProperty("--scene-zoom", String(sceneState.zoom));
      fitMapToViewport(scene);
      d.sceneMap.classList.toggle("is-editing", sceneState.editing);
      d.sceneMap.textContent = "";
      const devices = Array.isArray(coreState().lastDevices) ? coreState().lastDevices : [];
      if (!devices.length) {
        const empty = document.createElement("div");
        empty.className = "scene-map-empty";
        empty.textContent = "暂无投放端，连接设备后可在此布局";
        d.sceneMap.append(empty);
        return;
      }
      devices.forEach((device, index) => d.sceneMap.append(createNode(device, index, devices.length, scene)));
      d.sceneMap.querySelectorAll(".scene-device-node").forEach((node) => {
        const point = scene.positions[node.dataset.deviceId];
        if (point) setNodePosition(node, point);
        const device = devices.find((item) => String(item.id) === String(node.dataset.deviceId));
        const thumbnail = node.querySelector(".scene-device-thumb");
        if (device && thumbnail) app.canvas?.renderDeviceThumbnail?.(thumbnail, device.state || app.core.readState(), device.width, device.height);
      });
      if (sceneState.positionsDirty) {
        sceneState.positionsDirty = false;
        persist();
      }
      if (sceneState.editing && d.sceneMapHint) d.sceneMapHint.textContent = "拖动屏幕卡片调整位置，完成后自动保存到当前场景";
    }

    function renderQuickPresets() {
      const d = dom();
      const grid = d.sceneQuickPresets;
      if (!grid) return;
      grid.textContent = "";
      const state = coreState();
      const presets = Array.isArray(state.presets) ? state.presets : [];
      const recentIds = Array.isArray(state.recentPresetIds) ? state.recentPresetIds : [];
      const byId = new Map(presets.map((preset) => [String(preset.id), preset]));
      const recent = recentIds.map((id) => byId.get(String(id))).filter(Boolean);
      const favorites = presets.filter((preset) => state.favoritePresetIds?.has?.(String(preset.id)));
      const ordered = [...new Map([...recent, ...favorites, ...presets].map((preset) => [String(preset.id), preset])).values()].slice(0, 6);
      if (!ordered.length) {
        const empty = document.createElement("div");
        empty.className = "scene-quick-empty";
        empty.textContent = "暂无常用预设，可在全部预设库保存当前设置";
        grid.append(empty);
        return;
      }
      ordered.forEach((preset) => {
        const button = document.createElement("button");
        button.type = "button";
        button.className = "scene-quick-preset";
        button.title = `应用预设：${preset.name}`;
        const swatch = document.createElement("span");
        swatch.className = "swatch";
        const normalized = app.visualState?.canonicalizeState?.(preset.state || {}) || preset.state || {};
        const color = app.visualState?.colorWithOverallBrightness?.(normalized.bgColor || "#00ff00", normalized.overallBrightness) || normalized.bgColor || "#00ff00";
        swatch.style.background = color;
        swatch.style.setProperty("--cross", normalized.hideCross === "1" ? "transparent" : normalized.crossColor || "#0040d8");
        const label = document.createElement("span");
        label.textContent = preset.name;
        button.append(swatch, label);
        button.addEventListener("click", () => app.presets?.apply?.(preset));
        grid.append(button);
      });
    }

    function openOverlay(element) {
      if (!element) return;
      element.hidden = false;
      element.removeAttribute("aria-hidden");
    }

    function closeOverlay(element) {
      if (!element) return;
      element.hidden = true;
      element.setAttribute("aria-hidden", "true");
    }

    function setQuickConnectOpen(open) {
      const d = dom();
      if (!d.controlQuickconnect) return;
      d.controlQuickconnect.hidden = !open;
      d.controlQuickconnect.classList.toggle("is-open", open);
      d.controlQuickconnectBtn?.setAttribute("aria-expanded", String(open));
      if (open) d.closeQuickconnectBtn?.focus();
    }

    function openPresetLibrary() {
      if (coreState().role !== "control") {
        document.body?.classList?.remove("mobile-preset-management");
        return;
      }
      openOverlay(dom().presetLibraryOverlay);
    }

    function closePresetLibrary() {
      document.body?.classList?.remove("mobile-preset-management");
      closeOverlay(dom().presetLibraryOverlay);
    }

    function fillSceneForm(scene = currentScene()) {
      const d = dom();
      if (d.sceneNameInput) d.sceneNameInput.value = scene?.name || "";
      if (d.sceneWidthInput) d.sceneWidthInput.value = scene?.width || DEFAULT_SCENE.width;
      if (d.sceneHeightInput) d.sceneHeightInput.value = scene?.height || DEFAULT_SCENE.height;
      if (d.sceneUnitSelect) d.sceneUnitSelect.value = scene?.unit || "m";
      if (d.sceneManagerTitle) d.sceneManagerTitle.textContent = scene?.name || "场景管理";
      if (d.sceneManagerMeta) d.sceneManagerMeta.textContent = scene
        ? `${formatSceneMeta(scene)} · 屏幕位置按场景独立保存 · 场景仅保存在当前浏览器`
        : "创建场景后可保存屏幕位置 · 场景仅保存在当前浏览器";
      if (d.sceneDeleteBtn) d.sceneDeleteBtn.disabled = sceneState.document.scenes.length <= 1;
    }

    function beginCreateScene() {
      const d = dom();
      sceneState.managerMode = "create";
      if (d.sceneNameInput) d.sceneNameInput.value = "新场景";
      if (d.sceneWidthInput) d.sceneWidthInput.value = DEFAULT_SCENE.width;
      if (d.sceneHeightInput) d.sceneHeightInput.value = DEFAULT_SCENE.height;
      if (d.sceneUnitSelect) d.sceneUnitSelect.value = DEFAULT_SCENE.unit;
      if (d.sceneManagerMode) d.sceneManagerMode.textContent = "新建场景";
      if (d.sceneManagerTitle) d.sceneManagerTitle.textContent = "新建场景";
      if (d.sceneManagerMeta) d.sceneManagerMeta.textContent = "创建后即可为当前场景保存屏幕位置 · 场景仅保存在当前浏览器";
      if (d.sceneDeleteBtn) d.sceneDeleteBtn.disabled = true;
    }

    function openSceneManager(createMode = false) {
      const d = dom();
      if (createMode) {
        beginCreateScene();
      } else {
        sceneState.managerMode = "edit";
        fillSceneForm();
        if (d.sceneManagerMode) d.sceneManagerMode.textContent = "编辑当前场景";
      }
      renderSceneList();
      openOverlay(d.sceneManagerOverlay);
      d.sceneNameInput?.focus();
      d.sceneNameInput?.select?.();
    }

    function renderSceneList() {
      const list = dom().sceneManagerList;
      if (!list) return;
      list.textContent = "";
      sceneState.document.scenes.forEach((scene) => {
        const item = document.createElement("button");
        item.type = "button";
        item.className = "scene-manager-item";
        item.setAttribute("aria-pressed", String(scene.id === sceneState.activeSceneId));
        const name = document.createElement("strong");
        name.textContent = scene.name;
        const meta = document.createElement("span");
        meta.textContent = `${formatSceneMeta(scene)} · ${Object.keys(scene.positions || {}).length} 个位置`;
        item.append(name, meta);
        item.addEventListener("click", () => switchScene(scene.id));
        list.append(item);
      });
      if (dom().sceneCountReadout) dom().sceneCountReadout.textContent = `${sceneState.document.scenes.length} 个场景`;
      if (sceneState.managerMode === "edit") fillSceneForm();
    }

    function switchScene(id) {
      if (!sceneState.document.scenes.some((scene) => scene.id === String(id))) return;
      sceneState.managerMode = "edit";
      sceneState.activeSceneId = String(id);
      sceneState.document.activeSceneId = sceneState.activeSceneId;
      persist();
      render();
      renderSceneList();
      updateStatus("已切换场景");
    }

    function formValues() {
      const d = dom();
      const name = cleanName(d.sceneNameInput?.value, "");
      const width = Number(d.sceneWidthInput?.value);
      const height = Number(d.sceneHeightInput?.value);
      if (!name) return { error: "请输入场景名称" };
      if (!Number.isFinite(width) || width < 0.01 || width > 100000) return { error: "宽度需为 0.01–100000 之间的数字" };
      if (!Number.isFinite(height) || height < 0.01 || height > 100000) return { error: "高度需为 0.01–100000 之间的数字" };
      return { values: { name, width, height, unit: normalizeUnit(d.sceneUnitSelect?.value) } };
    }

    function readFormValues() {
      const result = formValues();
      if (result.error) {
        updateStatus(result.error);
        const input = result.error.startsWith("宽度") ? dom().sceneWidthInput : result.error.startsWith("高度") ? dom().sceneHeightInput : dom().sceneNameInput;
        input?.focus?.();
        return null;
      }
      return result.values;
    }

    function createFromForm() {
      const values = readFormValues();
      if (!values) return;
      const existingIds = new Set(sceneState.document.scenes.map((scene) => scene.id));
      const next = createScene(values, existingIds);
      sceneState.document = normalizeDocument({ ...sceneState.document, scenes: [...sceneState.document.scenes, next] });
      sceneState.activeSceneId = next.id;
      sceneState.managerMode = "edit";
      persist();
      renderSceneList();
      render();
      updateStatus(`已创建场景：${next.name}`);
    }

    function saveCurrentScene() {
      const current = currentScene();
      if (!current) return createFromForm();
      const values = readFormValues();
      if (!values) return;
      sceneState.document = normalizeDocument({
        ...sceneState.document,
        scenes: sceneState.document.scenes.map((scene) => scene.id === current.id ? { ...scene, ...values, updatedAt: Date.now() } : scene)
      });
      persist();
      renderSceneList();
      render();
      updateStatus("场景设置已保存");
    }

    function renameCurrentScene() {
      const current = currentScene();
      if (!current) return;
      const nextName = cleanName(global.prompt?.("场景名称", current.name), current.name);
      if (!nextName || nextName === current.name) return;
      current.name = nextName;
      current.updatedAt = Date.now();
      persist();
      renderSceneList();
      render();
      updateStatus(`已重命名场景：${nextName}`);
    }

    function duplicateCurrentScene() {
      const current = currentScene();
      if (!current) return;
      const next = duplicateScene(current, new Set(sceneState.document.scenes.map((scene) => scene.id)));
      sceneState.document = normalizeDocument({ ...sceneState.document, scenes: [...sceneState.document.scenes, next] });
      sceneState.activeSceneId = next.id;
      persist();
      renderSceneList();
      render();
      updateStatus(`已复制场景：${next.name}`);
    }

    function removeCurrentScene() {
      const current = currentScene();
      if (!current || sceneState.document.scenes.length <= 1) {
        updateStatus("至少保留一个场景");
        return;
      }
      if (global.confirm && !global.confirm(`删除场景“${current.name}”？`)) return;
      sceneState.document = deleteScene(sceneState.document, current.id);
      sceneState.activeSceneId = sceneState.document.activeSceneId;
      persist();
      renderSceneList();
      render();
      updateStatus(`已删除场景：${current.name}`);
    }

    function submitSceneForm(event) {
      event?.preventDefault?.();
      if (sceneState.managerMode === "create") createFromForm();
      else saveCurrentScene();
    }

    function setDensity(next) {
      if (!["detailed", "compact", "icon"].includes(next)) return;
      sceneState.density = next;
      document.body.dataset.sceneDensity = next;
      syncCoreState();
      render();
    }

    function init() {
      if (sceneState.initialized || coreState().role !== "control") return;
      sceneState.initialized = true;
      syncCoreState();
      const d = dom();
      app.canvas?.addRenderListener?.(() => render());
      d.sceneSelect?.addEventListener("change", () => switchScene(d.sceneSelect.value));
      d.sceneManageInlineBtn?.addEventListener("click", () => openSceneManager(false));
      d.sceneZoomInBtn?.addEventListener("click", () => { sceneState.zoom = Math.min(1.8, Number((sceneState.zoom + 0.1).toFixed(2))); render(); });
      d.sceneZoomOutBtn?.addEventListener("click", () => { sceneState.zoom = Math.max(0.65, Number((sceneState.zoom - 0.1).toFixed(2))); render(); });
      d.sceneZoomFitBtn?.addEventListener("click", () => { sceneState.zoom = 1; render(); });
      d.sceneEditLayoutBtn?.addEventListener("click", () => { sceneState.editing = !sceneState.editing; syncCoreState(); render(); });
      d.sceneDensitySelect?.addEventListener("change", () => setDensity(d.sceneDensitySelect.value));
      d.sceneClearSelectionBtn?.addEventListener("click", () => {
        const clear = app.devices?.selectSet?.([]);
        if (clear && typeof clear.then === "function") clear.catch(() => {});
        else {
          app.devices?.clearSelected?.();
          render();
        }
      });
      d.controlQuickconnectBtn?.addEventListener("click", () => setQuickConnectOpen(true));
      d.closeQuickconnectBtn?.addEventListener("click", () => setQuickConnectOpen(false));
      d.controlQuickconnect?.addEventListener("click", (event) => { if (event.target === d.controlQuickconnect) setQuickConnectOpen(false); });
      d.newSceneBtn?.addEventListener("click", () => openSceneManager(true));
      d.manageScenesBtn?.addEventListener("click", () => openSceneManager(false));
      d.sceneManagerCloseBtn?.addEventListener("click", () => closeOverlay(d.sceneManagerOverlay));
      d.sceneManagerOverlay?.addEventListener("click", (event) => { if (event.target === d.sceneManagerOverlay) closeOverlay(d.sceneManagerOverlay); });
      d.sceneCreateBtn?.addEventListener("click", beginCreateScene);
      d.sceneForm?.addEventListener("submit", submitSceneForm);
      d.sceneRenameBtn?.addEventListener("click", renameCurrentScene);
      d.sceneDuplicateBtn?.addEventListener("click", duplicateCurrentScene);
      d.sceneDeleteBtn?.addEventListener("click", removeCurrentScene);
      d.openPresetLibraryBtn?.addEventListener("click", openPresetLibrary);
      d.closePresetLibraryBtn?.addEventListener("click", closePresetLibrary);
      d.presetLibraryOverlay?.addEventListener("click", (event) => { if (event.target === d.presetLibraryOverlay) closePresetLibrary(); });
      if (d.presetLibraryBody && coreState().role === "control") {
        const existingPresetSection = document.querySelector(".settings-column > .preset-section");
        if (existingPresetSection && !d.presetLibraryBody.contains(existingPresetSection)) d.presetLibraryBody.append(existingPresetSection);
      }
      document.addEventListener("keydown", (event) => {
        if (event.key !== "Escape") return;
        if (d.sceneManagerOverlay && !d.sceneManagerOverlay.hidden) closeOverlay(d.sceneManagerOverlay);
        if (d.presetLibraryOverlay && !d.presetLibraryOverlay.hidden) closePresetLibrary();
        if (d.controlQuickconnect && !d.controlQuickconnect.hidden) setQuickConnectOpen(false);
      });
      window.addEventListener("resize", () => {
        if (!sceneState.drag) render();
      });
      renderSceneList();
      render();
      renderQuickPresets();
    }

    function onDevicesChanged() {
      if (!sceneState.initialized || coreState().role !== "control") return;
      render();
    }

    function onPresetsChanged() {
      if (!sceneState.initialized || coreState().role !== "control") return;
      renderQuickPresets();
    }

    return {
      ...exports,
      init,
      render,
      renderQuickPresets,
      onDevicesChanged,
      onPresetsChanged,
      switchScene,
      openSceneManager,
      setDensity,
      openPresetLibrary,
      closePresetLibrary,
      getState: () => ({ ...sceneState, document: normalizeDocument(sceneState.document), currentScene: copyScene(currentScene()) })
    };
  }

  const exports = {
    VERSION,
    STORAGE_KEY,
    DEFAULT_SCENE,
    VALID_UNITS: [...VALID_UNITS],
    finiteNumber,
    normalizePoint,
    denormalizePoint,
    normalizeUnit,
    normalizeScene,
    normalizeDocument,
    validateDocument,
    loadDocument,
    saveDocument,
    loadScenes: (storage) => loadDocument(storage).scenes,
    saveScenes: (storage, scenes, activeSceneId) => saveDocument(storage, { version: VERSION, scenes, activeSceneId }),
    createScene,
    duplicateScene,
    deleteScene,
    copyScene,
    createBrowserModule
  };

  return exports;
}
