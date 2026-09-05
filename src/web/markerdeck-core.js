(function (global) {
  "use strict";

  const app = global.MarkerDeck = global.MarkerDeck || {};
  const byId = (id) => document.getElementById(id);
  const visualState = app.visualState;

  const dom = {
    canvas: byId("stage"),
    panel: byId("panel"),
    readout: byId("readout"),
    lockBtn: byId("lockBtn"),
    hotCorner: byId("hotCorner"),
    launcher: byId("launcher"),
    lanAddress: byId("lanAddress"),
    qrImage: byId("qrImage"),
    qrBox: document.querySelector(".qr-box"),
    statusPill: byId("statusPill"),
    controlQuickconnectBtn: byId("controlQuickconnectBtn"),
    closeQuickconnectBtn: byId("closeQuickconnectBtn"),
    controlQuickconnect: byId("controlQuickconnect"),
    controlQrImage: byId("controlQrImage"),
    controlUrlText: byId("controlUrlText"),
    deviceList: byId("deviceList"),
    forceLockBtn: byId("forceLockBtn"),
    stopServerBtn: byId("stopServerBtn"),
    deviceNameInput: byId("deviceNameInput"),
    saveDeviceNameBtn: byId("saveDeviceNameBtn"),
    currentDeviceLabel: byId("currentDeviceLabel"),
    selectOnlineBtn: byId("selectOnlineBtn"),
    clearSelectionBtn: byId("clearSelectionBtn"),
    selectionSummary: byId("selectionSummary"),
    deviceRetentionSelect: byId("deviceRetentionSelect"),
    clearOfflineDevicesBtn: byId("clearOfflineDevicesBtn"),
    groupSelect: byId("groupSelect"),
    selectGroupBtn: byId("selectGroupBtn"),
    groupNameInput: byId("groupNameInput"),
    assignGroupBtn: byId("assignGroupBtn"),
    clearGroupBtn: byId("clearGroupBtn"),
    crossToggleBtn: byId("crossToggleBtn"),
    randomPointsBtn: byId("randomPointsBtn"),
    exportPngBtn: byId("exportPngBtn"),
    exportAllPresetsBtn: byId("exportAllPresetsBtn"),
    exportChoiceDialog: byId("exportChoiceDialog"),
    exportZipBtn: byId("exportZipBtn"),
    exportFilesBtn: byId("exportFilesBtn"),
    cancelExportChoiceBtn: byId("cancelExportChoiceBtn"),
    videoExportChoiceDialog: byId("videoExportChoiceDialog"),
    exportVideoZipBtn: byId("exportVideoZipBtn"),
    exportVideoFilesBtn: byId("exportVideoFilesBtn"),
    cancelVideoExportChoiceBtn: byId("cancelVideoExportChoiceBtn"),
    exportWidthInput: byId("exportWidth"),
    exportHeightInput: byId("exportHeight"),
    videoDurationInput: byId("videoDuration"),
    exportVideoBtn: byId("exportVideoBtn"),
    exportAllVideosBtn: byId("exportAllVideosBtn"),
    videoExportTools: byId("videoExportTools"),
    videoProgressWindow: byId("videoProgressWindow"),
    videoProgressTitle: byId("videoProgressTitle"),
    videoProgressPercent: byId("videoProgressPercent"),
    videoProgressBar: byId("videoProgressBar"),
    videoProgressDetail: byId("videoProgressDetail"),
    displayNameDialog: byId("displayNameDialog"),
    displayNameForm: byId("displayNameForm"),
    displayNameInput: byId("displayNameInput"),
    presetNameInput: byId("presetNameInput"),
    savePresetBtn: byId("savePresetBtn"),
    presetGrid: byId("presets"),
    mobileCurrentPresetSwatch: byId("mobileCurrentPresetSwatch"),
    mobileCurrentPresetName: byId("mobileCurrentPresetName"),
    mobilePresetBar: byId("mobilePresetBar"),
    mobilePresetSheet: byId("mobilePresetSheet"),
    mobilePresetTarget: byId("mobilePresetTarget"),
    mobileRecentPresetGroup: byId("mobileRecentPresetGroup"),
    mobileRecentPresets: byId("mobileRecentPresets"),
    mobileAllPresets: byId("mobileAllPresets"),
    mobilePresetCount: byId("mobilePresetCount"),
    mobilePresetKeepOpen: byId("mobilePresetKeepOpen"),
    defaultBtn: byId("defaultBtn"),
    blackoutBtn: byId("blackoutBtn"),
    copyAddressBtn: byId("copyAddressBtn"),
    copyControlUrlBtn: byId("copyControlUrlBtn"),
    localModeBtn: byId("localModeBtn"),
    displayModeBtn: byId("displayModeBtn"),
    controlModeBtn: byId("controlModeBtn"),
    refreshDevicesBtn: byId("refreshDevicesBtn")
  };
  Object.assign(dom, {
    newSceneBtn: byId("newSceneBtn"),
    manageScenesBtn: byId("manageScenesBtn"),
    sceneManageInlineBtn: byId("sceneManageInlineBtn"),
    sceneSelect: byId("sceneSelect"),
    sceneMapViewport: byId("sceneMapViewport"),
    sceneMap: byId("sceneMap"),
    sceneMetaReadout: byId("sceneMetaReadout"),
    sceneNameReadout: byId("sceneNameReadout"),
    sceneSizeReadout: byId("sceneSizeReadout"),
    sceneZoomReadout: byId("sceneZoomReadout"),
    sceneZoomInBtn: byId("sceneZoomInBtn"),
    sceneZoomOutBtn: byId("sceneZoomOutBtn"),
    sceneZoomFitBtn: byId("sceneZoomFitBtn"),
    sceneEditLayoutBtn: byId("sceneEditLayoutBtn"),
    sceneDensitySelect: byId("sceneDensitySelect"),
    sceneCurrentTarget: byId("sceneCurrentTarget"),
    sceneCurrentLocation: byId("sceneCurrentLocation"),
    sceneSelectedCount: byId("sceneSelectedCount"),
    selectedScreenTarget: byId("selectedScreenTarget"),
    sceneDockTitle: byId("sceneDockTitle"),
    sceneDockDetail: byId("sceneDockDetail"),
    sceneMapHint: byId("sceneMapHint"),
    sceneClearSelectionBtn: byId("sceneClearSelectionBtn"),
    sceneQuickPresets: byId("sceneQuickPresets"),
    openPresetLibraryBtn: byId("openPresetLibraryBtn"),
    closePresetLibraryBtn: byId("closePresetLibraryBtn"),
    presetLibraryOverlay: byId("presetLibraryOverlay"),
    presetLibraryBody: document.querySelector(".preset-library-body"),
    sceneManagerOverlay: byId("sceneManagerOverlay"),
    sceneManagerCloseBtn: byId("sceneManagerCloseBtn"),
    sceneManagerList: byId("sceneManagerList"),
    sceneCountReadout: byId("sceneCountReadout"),
    sceneManagerTitle: byId("sceneManagerTitle"),
    sceneManagerMeta: byId("sceneManagerMeta"),
    sceneManagerMode: byId("sceneManagerMode"),
    sceneForm: byId("sceneForm"),
    sceneNameInput: byId("sceneNameInput"),
    sceneWidthInput: byId("sceneWidthInput"),
    sceneHeightInput: byId("sceneHeightInput"),
    sceneUnitSelect: byId("sceneUnitSelect"),
    sceneRatioReadout: byId("sceneRatioReadout"),
    sceneCreateBtn: byId("sceneCreateBtn"),
    sceneApplyBtn: byId("sceneApplyBtn"),
    sceneRenameBtn: byId("sceneRenameBtn"),
    sceneDuplicateBtn: byId("sceneDuplicateBtn"),
    sceneDeleteBtn: byId("sceneDeleteBtn"),
    forceLockBtn: byId("forceLockBtn")
  });
  dom.controls = {
    bgColor: byId("bgColor"),
    overallBrightness: byId("overallBrightness"),
    crossColor: byId("crossColor"),
    crossSize: byId("crossSize"),
    crossThickness: byId("crossThickness"),
    edgeRatio: byId("edgeRatio"),
    centerY: byId("centerY"),
    randomPointCount: byId("randomPointCount")
  };
  const outputIds = [
    "overallBrightness",
    "crossSize",
    "crossThickness",
    "edgeRatio",
    "centerY",
    "randomPointCount"
  ];
  dom.outputs = Object.fromEntries(
    outputIds.map((id) => [id, byId(`${id}Value`)])
  );

  const STORAGE_KEYS = Object.freeze({
    deviceId: "markerdeckDeviceId",
    sessionId: "markerdeckSessionId",
    deviceName: "markerdeckDeviceName",
    presets: "markerdeckPresets",
    recentPresets: "markerdeckRecentPresets",
    favoritePresets: "markerdeckFavoritePresets",
    sessionClaims: "markerdeck-session-claims"
  });
  const LEGACY_STORAGE_KEYS = Object.freeze({
    deviceId: "chromaCrossDeviceId",
    sessionId: "chromaCrossSessionId",
    deviceName: "chromaCrossDeviceName",
    presets: "chromaCrossPresets",
    recentPresets: "chromaCrossRecentPresets",
    favoritePresets: "chromaCrossFavoritePresets"
  });

  const colorWithBrightness = visualState.colorWithBrightness;

  function readStorageWithLegacy(storage, key, legacyKey) {
    const value = storage.getItem(key);
    if (value !== null) return value;
    const legacyValue = storage.getItem(legacyKey);
    if (legacyValue !== null) {
      try {
        storage.setItem(key, legacyValue);
      } catch (_) {}
      return legacyValue;
    }
    return null;
  }

  function readStoredIdList(key, legacyKey, limit = Infinity) {
    try {
      const value = JSON.parse(readStorageWithLegacy(localStorage, key, legacyKey) || "[]");
      return Array.isArray(value) ? value.map(String).slice(0, limit) : [];
    } catch (_) {
      return [];
    }
  }

  function createRandomId(prefix) {
    const randomPart = global.crypto?.randomUUID
      ? global.crypto.randomUUID()
      : `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
    return `${prefix}-${randomPart}`.slice(0, 80);
  }

  function getDeviceId() {
    let id = readStorageWithLegacy(localStorage, STORAGE_KEYS.deviceId, LEGACY_STORAGE_KEYS.deviceId);
    if (!id) {
      id = `dev-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`;
      localStorage.setItem(STORAGE_KEYS.deviceId, id);
    }
    return id;
  }

  function createSessionId() {
    const existing = readStorageWithLegacy(sessionStorage, STORAGE_KEYS.sessionId, LEGACY_STORAGE_KEYS.sessionId);
    if (existing) return existing;
    const id = createRandomId("screen");
    sessionStorage.setItem(STORAGE_KEYS.sessionId, id);
    return id;
  }

  function deviceName() {
    const savedName = readStorageWithLegacy(
      localStorage,
      `${STORAGE_KEYS.deviceName}:${state.deviceId}`,
      `${LEGACY_STORAGE_KEYS.deviceName}:${state.deviceId}`
    );
    if (savedName) return savedName;
    const platform = navigator.platform || "设备";
    return `${platform} ${state.deviceId.slice(-4)}`;
  }

  function saveLocalDeviceName(name) {
    const cleanName = String(name || "").trim().slice(0, 40);
    if (cleanName) localStorage.setItem(`${STORAGE_KEYS.deviceName}:${state.deviceId}`, cleanName);
    return cleanName;
  }

  function requestDisplayName() {
    dom.displayNameInput.value = deviceName();
    dom.displayNameDialog.classList.remove("hidden");
    requestAnimationFrame(() => {
      dom.displayNameInput.focus();
      dom.displayNameInput.select();
    });
    return new Promise((resolve) => {
      dom.displayNameForm.onsubmit = (event) => {
        event.preventDefault();
        const name = saveLocalDeviceName(dom.displayNameInput.value);
        if (!name) {
          dom.displayNameInput.focus();
          return;
        }
        dom.displayNameDialog.classList.add("hidden");
        resolve(name);
      };
    });
  }

  function androidProvidedDeviceName() {
    const value = new URLSearchParams(global.location.search).get("androidDeviceName");
    return value ? String(value).trim().slice(0, 40) : "";
  }

  function readState() {
    return visualState.canonicalizeState({
      bgColor: dom.controls.bgColor.value,
      bgBrightness: String(visualState.DEFAULT_LEGACY_BRIGHTNESS),
      overallBrightness: visualState.normalizeOverallBrightness(dom.controls.overallBrightness.value),
      crossColor: dom.controls.crossColor.value,
      crossBrightness: String(visualState.DEFAULT_LEGACY_BRIGHTNESS),
      crossSize: dom.controls.crossSize.value,
      crossThickness: dom.controls.crossThickness.value,
      edgeRatio: dom.controls.edgeRatio.value,
      centerY: dom.controls.centerY.value,
      hideCross: document.body.dataset.hideCross || "0",
      randomPoints: document.body.dataset.randomPoints || "0",
      randomSeed: document.body.dataset.randomSeed || "",
      randomPointCount: dom.controls.randomPointCount.value
    });
  }

  function currentStateWithFlags() {
    return {
      ...readState(),
      forceLock: state.selectedDeviceState?.forceLock || "0",
      displayLocked: state.selectedDeviceState?.displayLocked || "0",
      lockCommand: state.selectedDeviceState?.lockCommand || "none",
      lockCommandId: state.selectedDeviceState?.lockCommandId || "0",
      hideCross: state.selectedDeviceState?.hideCross || document.body.dataset.hideCross || "0",
      randomPoints: state.selectedDeviceState?.randomPoints || document.body.dataset.randomPoints || "0",
      randomSeed: state.selectedDeviceState?.randomSeed || document.body.dataset.randomSeed || "",
      randomPointCount: state.selectedDeviceState?.randomPointCount || dom.controls.randomPointCount.value
    };
  }

  function setState(next) {
    const incoming = visualState.canonicalizeState({
      ...readState(),
      ...(next || {})
    });
    state.applyingRemote = true;
    try {
      Object.entries(incoming).forEach(([key, value]) => {
        if (dom.controls[key] && value !== undefined) {
          dom.controls[key].value = key === "overallBrightness"
            ? visualState.normalizeOverallBrightness(value)
            : value;
        }
        if (key === "hideCross" && value !== undefined) {
          document.body.dataset.hideCross = String(value === "1" || value === 1 || value === true ? "1" : "0");
        }
        if (key === "randomPoints" && value !== undefined) {
          document.body.dataset.randomPoints = String(value === "1" || value === 1 || value === true ? "1" : "0");
        }
        if (key === "randomSeed" && value !== undefined) {
          document.body.dataset.randomSeed = String(value || "");
        }
      });
    } finally {
      state.applyingRemote = false;
    }
    return incoming;
  }

  function makeRandomSeed() {
    return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
  }

  function updateStatus(text) {
    dom.statusPill.textContent = text;
  }

  function isTextEditingTarget(target) {
    return !!target?.closest?.("input, textarea, select, [contenteditable='true']");
  }

  const state = {
    presets: [],
    activePresetId: "",
    recentPresetIds: readStoredIdList(STORAGE_KEYS.recentPresets, LEGACY_STORAGE_KEYS.recentPresets, 4),
    favoritePresetIds: new Set(readStoredIdList(STORAGE_KEYS.favoritePresets, LEGACY_STORAGE_KEYS.favoritePresets)),
    wakeLock: null,
    locked: false,
    role: "local",
    serverMode: global.location.protocol.startsWith("http"),
    syncTimer: 0,
    deviceTimer: 0,
    heartbeatTimer: 0,
    eventSource: null,
    applyingRemote: false,
    selectedDeviceId: "",
    selectedDeviceIds: new Set(),
    selectionInitialized: false,
    lastDevices: [],
    selectedDeviceInfo: null,
    selectedDeviceState: null,
    devicePreviewCanvases: new Map(),
    deviceCards: new Map(),
    deviceGroupCards: new Map(),
    deviceGroupSectionElements: new Map(),
    deviceGroupPreviewCanvases: new Map(),
    expandedDeviceIds: new Set(),
    deviceRequestInFlight: false,
    deviceRefreshPending: false,
    selectedDeviceNameId: "",
    selectedDeviceNameDraft: "",
    selectedDeviceNameLocked: false,
    scenes: [],
    activeSceneId: "",
    sceneZoom: 1,
    sceneDensity: "compact",
    sceneEditing: false,
    deviceForceLock: "0",
    lastShownLockCommandStatus: null,
    lastLockCommandId: "0",
    lastGlobalLockCommandId: "0",
    globalLockBaselineInitialized: false,
    handledLockCommandIds: new Set(),
    lockedByRemote: false,
    cornerTapCount: 0,
    cornerTimer: 0,
    deviceId: getDeviceId(),
    sessionId: createSessionId(),
    pageInstanceId: createRandomId("page"),
    sessionClaimChannel: null,
    sessionConflictResolver: null,
    capabilities: { videoExport: true, pngExport: true }
  };

  function applyCapabilities(capabilities = {}) {
    state.capabilities = { ...state.capabilities, ...capabilities };
    const videoSupported = state.capabilities.videoExport !== false;
    if (dom.videoExportTools) dom.videoExportTools.hidden = !videoSupported;
    if (dom.exportVideoBtn) dom.exportVideoBtn.disabled = !videoSupported;
    if (dom.exportAllVideosBtn) dom.exportAllVideosBtn.disabled = !videoSupported;
  }

  function initInputGuards() {
    document.querySelectorAll("img, canvas").forEach((element) => {
      element.setAttribute("draggable", "false");
    });
    ["touchmove", "gesturestart", "gesturechange", "contextmenu", "dragstart", "selectstart", "dblclick"].forEach((eventName) => {
      document.addEventListener(eventName, (event) => {
        if (isTextEditingTarget(event.target)) return;
        if (state.locked || eventName !== "touchmove") event.preventDefault();
        if (eventName === "dblclick") global.getSelection?.()?.removeAllRanges?.();
      }, { passive: false });
    });
  }

  app.core = {
    dom,
    state,
    STORAGE_KEYS,
    LEGACY_STORAGE_KEYS,
    colorWithBrightness,
    readStorageWithLegacy,
    readStoredIdList,
    createRandomId,
    deviceName,
    saveLocalDeviceName,
    requestDisplayName,
    androidProvidedDeviceName,
    readState,
    currentStateWithFlags,
    setState,
    normalizeVisualState: visualState.normalizeVisualState,
    canonicalizeState: visualState.canonicalizeState,
    makeRandomSeed,
    updateStatus,
    applyCapabilities,
    effectiveBrightnessPercent: visualState.effectiveBrightnessPercent,
    normalizeOverallBrightness: visualState.normalizeOverallBrightness,
    isTextEditingTarget,
    initInputGuards
  };
})(window);
