(function (global) {
  "use strict";

  const app = global.MarkerDeck;
  const { dom, state, STORAGE_KEYS, LEGACY_STORAGE_KEYS, colorWithBrightness, readStorageWithLegacy, readState, setState, updateStatus } = app.core;

  function saveMobilePresetPreferences() {
    localStorage.setItem(STORAGE_KEYS.recentPresets, JSON.stringify(state.recentPresetIds));
    localStorage.setItem(STORAGE_KEYS.favoritePresets, JSON.stringify([...state.favoritePresetIds]));
  }

  function setPresetSwatch(swatch, presetState = {}) {
    swatch.style.background = colorWithBrightness(presetState.bgColor || "#00ff00", presetState.bgBrightness ?? 100);
    swatch.style.setProperty("--cross", String(presetState.hideCross || "0") === "1"
      ? "transparent"
      : colorWithBrightness(presetState.crossColor || "#0040d8", presetState.crossBrightness ?? 100));
  }

  function updateMobileCurrentPreset() {
    const activePreset = state.presets.find((preset) => String(preset.id) === state.activePresetId);
    dom.mobileCurrentPresetName.textContent = activePreset?.name || "自定义设置";
    setPresetSwatch(dom.mobileCurrentPresetSwatch, activePreset?.state || readState());
  }

  function markPresetAsCustom() {
    state.activePresetId = "";
    updateMobileCurrentPreset();
  }

  function reconcileActivePreset(nextState) {
    const activePreset = state.presets.find((preset) => String(preset.id) === state.activePresetId);
    if (activePreset) {
      const ignoredKeys = new Set(["forceLock", "displayLocked", "lockCommand", "lockCommandId"]);
      const matches = Object.entries(activePreset.state || {}).every(([key, value]) =>
        ignoredKeys.has(key) || String(nextState?.[key] ?? "") === String(value ?? "")
      );
      if (!matches) state.activePresetId = "";
    }
    updateMobileCurrentPreset();
  }

  function updateMobilePresetTarget() {
    if (state.role !== "control") {
      dom.mobilePresetTarget.textContent = "当前设备";
      return;
    }
    const count = state.selectedDeviceIds.size;
    const onlineCount = app.devices.selectedTargets().filter((device) => device.online).length;
    dom.mobilePresetTarget.textContent = count
      ? `将应用到 ${count} 个接收页面 · 在线 ${onlineCount} 个`
      : "请先选择接收页面";
  }

  function closeMobilePresetSheet() {
    dom.mobilePresetSheet.classList.add("hidden");
  }

  function openMobilePresetSheet() {
    document.body.classList.remove("mobile-preset-management");
    updateMobilePresetTarget();
    buildMobilePresets();
    dom.mobilePresetSheet.classList.remove("hidden");
    document.getElementById("closeMobilePresetsBtn").focus();
  }

  function createMobilePresetChoice(preset) {
    const choice = document.createElement("div");
    choice.className = "mobile-preset-choice";
    choice.classList.toggle("active", String(preset.id) === state.activePresetId);
    choice.tabIndex = 0;
    choice.setAttribute("role", "button");
    choice.setAttribute("aria-label", `应用预设：${preset.name}`);

    const swatch = document.createElement("span");
    swatch.className = "swatch";
    setPresetSwatch(swatch, preset.state || {});
    const name = document.createElement("span");
    name.className = "mobile-preset-choice-name";
    name.textContent = preset.name;
    choice.append(swatch, name);

    const applyChoice = () => {
      applyPreset(preset);
      if (!dom.mobilePresetKeepOpen.checked) closeMobilePresetSheet();
    };
    choice.addEventListener("click", applyChoice);
    choice.addEventListener("keydown", (event) => {
      if (event.key !== "Enter" && event.key !== " ") return;
      event.preventDefault();
      applyChoice();
    });

    const favorite = document.createElement("button");
    favorite.type = "button";
    favorite.className = "mobile-preset-favorite";
    favorite.classList.toggle("active", state.favoritePresetIds.has(String(preset.id)));
    favorite.textContent = state.favoritePresetIds.has(String(preset.id)) ? "★" : "☆";
    favorite.title = state.favoritePresetIds.has(String(preset.id)) ? "取消收藏" : "收藏预设";
    favorite.setAttribute("aria-label", `${favorite.title}：${preset.name}`);
    favorite.addEventListener("click", (event) => {
      event.stopPropagation();
      const id = String(preset.id);
      if (state.favoritePresetIds.has(id)) state.favoritePresetIds.delete(id);
      else state.favoritePresetIds.add(id);
      saveMobilePresetPreferences();
      buildMobilePresets();
    });
    choice.append(favorite);
    return choice;
  }

  function buildMobilePresets() {
    const availableIds = new Set(state.presets.map((preset) => String(preset.id)));
    state.recentPresetIds = state.recentPresetIds.filter((id) => availableIds.has(id)).slice(0, 4);
    state.favoritePresetIds = new Set([...state.favoritePresetIds].filter((id) => availableIds.has(id)));
    saveMobilePresetPreferences();

    dom.mobileRecentPresets.innerHTML = "";
    dom.mobileAllPresets.innerHTML = "";
    const recent = state.recentPresetIds
      .map((id) => state.presets.find((preset) => String(preset.id) === id))
      .filter(Boolean);
    dom.mobileRecentPresetGroup.hidden = recent.length === 0;
    recent.forEach((preset) => dom.mobileRecentPresets.append(createMobilePresetChoice(preset)));

    const originalOrder = new Map(state.presets.map((preset, index) => [String(preset.id), index]));
    const orderedPresets = [...state.presets].sort((a, b) => {
      const favoriteDifference = Number(state.favoritePresetIds.has(String(b.id))) - Number(state.favoritePresetIds.has(String(a.id)));
      return favoriteDifference || originalOrder.get(String(a.id)) - originalOrder.get(String(b.id));
    });
    if (!orderedPresets.length) {
      const empty = document.createElement("div");
      empty.className = "preset-empty";
      empty.textContent = "暂无预设，请先在管理预设中保存当前设置";
      dom.mobileAllPresets.append(empty);
    } else {
      orderedPresets.forEach((preset) => dom.mobileAllPresets.append(createMobilePresetChoice(preset)));
    }
    dom.mobilePresetCount.textContent = `${state.presets.length} 个`;
    updateMobileCurrentPreset();
  }

  function applyPreset(preset) {
    state.activePresetId = String(preset.id);
    state.recentPresetIds = [state.activePresetId, ...state.recentPresetIds.filter((id) => id !== state.activePresetId)].slice(0, 4);
    saveMobilePresetPreferences();
    setState(preset.state || {});
    app.canvas.render();
    if (state.role === "control") {
      state.selectedDeviceState = { ...(state.selectedDeviceState || {}), ...(preset.state || {}) };
    }
    app.projection.publishState();
    buildMobilePresets();
  }

  function buildPresets() {
    dom.presetGrid.innerHTML = "";
    if (!state.presets.length) {
      const empty = document.createElement("div");
      empty.className = "preset-empty";
      empty.textContent = "暂无预设，可保存当前设置创建一个";
      dom.presetGrid.append(empty);
      buildMobilePresets();
      return;
    }
    state.presets.forEach((preset) => {
      const item = document.createElement("div");
      item.className = "preset-item";
      const button = document.createElement("button");
      button.className = "preset";
      const swatch = document.createElement("span");
      swatch.className = "swatch";
      setPresetSwatch(swatch, preset.state || {});
      const label = document.createElement("span");
      label.textContent = preset.name;
      button.append(swatch, label);
      button.addEventListener("click", () => applyPreset(preset));
      const deleteButton = document.createElement("button");
      deleteButton.className = "preset-delete";
      deleteButton.type = "button";
      deleteButton.textContent = "×";
      deleteButton.title = `删除预设：${preset.name}`;
      deleteButton.setAttribute("aria-label", `删除预设：${preset.name}`);
      deleteButton.addEventListener("click", () => deletePreset(preset));
      item.append(button, deleteButton);
      dom.presetGrid.append(item);
    });
    buildMobilePresets();
  }

  async function loadPresets() {
    if (!state.serverMode) {
      try {
        state.presets = JSON.parse(readStorageWithLegacy(
          localStorage,
          STORAGE_KEYS.presets,
          LEGACY_STORAGE_KEYS.presets
        ) || "[]");
      } catch (_) {
        state.presets = [];
      }
      buildPresets();
      return;
    }
    try {
      const result = await app.api.getPresets();
      state.presets = result.presets || [];
      buildPresets();
    } catch (_) {
      updateStatus("预设加载失败");
    }
  }

  async function saveCurrentPreset() {
    const name = dom.presetNameInput.value.trim().slice(0, 40);
    if (!name) {
      updateStatus("请先输入预设名称");
      dom.presetNameInput.focus();
      return;
    }
    dom.savePresetBtn.disabled = true;
    const presetState = readState();
    try {
      if (state.serverMode) {
        const result = await app.api.savePreset(name, presetState);
        state.presets = result.presets || state.presets;
      } else {
        state.presets.push({ id: `local-${Date.now()}`, name, state: presetState });
        localStorage.setItem(STORAGE_KEYS.presets, JSON.stringify(state.presets));
      }
      dom.presetNameInput.value = "";
      buildPresets();
      updateStatus(`已保存预设：${name}`);
    } catch (_) {
      updateStatus("预设保存失败");
    } finally {
      dom.savePresetBtn.disabled = false;
    }
  }

  async function deletePreset(preset) {
    if (!global.confirm(`确定删除预设“${preset.name}”吗？`)) return;
    try {
      if (state.serverMode) {
        const result = await app.api.deletePreset(preset.id);
        state.presets = result.presets || [];
      } else {
        state.presets = state.presets.filter((item) => item.id !== preset.id);
        localStorage.setItem(STORAGE_KEYS.presets, JSON.stringify(state.presets));
      }
      buildPresets();
      updateStatus(`已删除预设：${preset.name}`);
    } catch (_) {
      updateStatus("预设删除失败");
    }
  }

  function init() {
    dom.savePresetBtn.addEventListener("click", saveCurrentPreset);
    dom.presetNameInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter") saveCurrentPreset();
    });
    document.getElementById("openMobilePresetsBtn").addEventListener("click", openMobilePresetSheet);
    document.getElementById("closeMobilePresetsBtn").addEventListener("click", closeMobilePresetSheet);
    dom.mobilePresetSheet.addEventListener("click", (event) => {
      if (event.target === dom.mobilePresetSheet) closeMobilePresetSheet();
    });
    document.getElementById("mobileManagePresetsBtn").addEventListener("click", () => {
      closeMobilePresetSheet();
      document.body.classList.add("mobile-preset-management");
      document.querySelector(".preset-section")?.scrollIntoView({ behavior: "smooth", block: "start" });
    });
    document.getElementById("mobileCustomSettingsBtn").addEventListener("click", () => {
      closeMobilePresetSheet();
      document.body.classList.remove("mobile-preset-management");
      document.querySelector(".settings-section")?.scrollIntoView({ behavior: "smooth", block: "start" });
    });
    document.addEventListener("keydown", (event) => {
      if (event.key === "Escape" && !dom.mobilePresetSheet.classList.contains("hidden")) closeMobilePresetSheet();
    });
  }

  app.presets = {
    init,
    load: loadPresets,
    build: buildPresets,
    apply: applyPreset,
    markCustom: markPresetAsCustom,
    reconcile: reconcileActivePreset,
    updateMobileTarget: updateMobilePresetTarget,
    updateMobileCurrent: updateMobileCurrentPreset,
    saveMobilePreferences: saveMobilePresetPreferences
  };
})(window);
