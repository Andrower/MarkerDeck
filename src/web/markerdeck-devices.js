(function (global) {
  "use strict";

  const app = global.MarkerDeck;
  const { dom, state, readState, updateStatus } = app.core;

  function selectedTargets() {
    return state.lastDevices.filter((device) => state.selectedDeviceIds.has(device.id));
  }

  function updateGroupOptions(devices) {
    const currentValue = dom.groupSelect.value;
    const groups = [...new Set(devices.map((device) => String(device.group || "").trim()).filter(Boolean))]
      .sort((a, b) => a.localeCompare(b, "zh-CN"));
    dom.groupSelect.innerHTML = '<option value="">选择分组</option>';
    groups.forEach((group) => {
      const option = document.createElement("option");
      option.value = group;
      option.textContent = group;
      dom.groupSelect.append(option);
    });
    if (groups.includes(currentValue)) dom.groupSelect.value = currentValue;
    dom.selectGroupBtn.disabled = !dom.groupSelect.value;
  }

  function updateSelectionUi() {
    const count = state.selectedDeviceIds.size;
    const onlineCount = selectedTargets().filter((device) => device.online).length;
    dom.selectionSummary.textContent = count ? `已选择 ${count} 个页面 · 在线 ${onlineCount} 个` : "已选择 0 个页面";
    app.presets.updateMobileTarget();
    dom.clearSelectionBtn.disabled = count === 0;
    dom.assignGroupBtn.disabled = count === 0;
    dom.clearGroupBtn.disabled = count === 0;
    dom.clearOfflineDevicesBtn.disabled = !state.lastDevices.some((device) => !device.online);
    updateRemoteLockButton();
  }

  function clearSelectedDevice() {
    state.selectedDeviceId = "";
    state.selectedDeviceInfo = null;
    state.selectedDeviceState = null;
    state.selectedDeviceNameId = "";
    state.selectedDeviceNameDraft = "";
    dom.deviceNameInput.value = "";
    dom.deviceNameInput.disabled = true;
    dom.saveDeviceNameBtn.disabled = true;
    dom.currentDeviceLabel.textContent = state.selectedDeviceIds.size
      ? `已选择 ${state.selectedDeviceIds.size} 个接收页面`
      : "当前未选择设备";
  }

  async function toggleDeviceSelection(device) {
    if (state.selectedDeviceIds.has(device.id)) {
      state.selectedDeviceIds.delete(device.id);
      if (state.selectedDeviceId === device.id) {
        const nextId = state.selectedDeviceIds.values().next().value || "";
        const nextDevice = state.lastDevices.find((item) => item.id === nextId);
        if (nextDevice) {
          await selectDevice(nextDevice.id, nextDevice.state);
          return;
        }
        clearSelectedDevice();
      }
      renderDevices(state.lastDevices);
      return;
    }
    state.selectedDeviceIds.add(device.id);
    await selectDevice(device.id, device.state);
  }

  async function selectDeviceSet(devices) {
    state.selectedDeviceIds = new Set(devices.map((device) => device.id));
    state.selectionInitialized = true;
    const first = devices[0];
    if (first) {
      await selectDevice(first.id, first.state);
    } else {
      clearSelectedDevice();
      renderDevices(state.lastDevices);
    }
  }

  async function assignSelectedGroup(group) {
    const ids = [...state.selectedDeviceIds];
    if (!ids.length) {
      updateStatus("请先选择设备");
      return;
    }
    await app.api.assignDeviceGroup(ids, group);
    updateStatus(group ? `已将 ${ids.length} 台设备设为 ${group}` : `已将 ${ids.length} 台设备移出分组`);
    await loadDevices();
  }

  async function selectDevice(id, deviceState) {
    state.selectedDeviceIds.add(id);
    state.selectedDeviceId = id;
    state.selectedDeviceInfo = null;
    state.selectedDeviceState = deviceState || null;
    state.selectedDeviceNameId = id;
    state.selectedDeviceNameDraft = state.lastDevices.find((device) => device.id === id)?.name || "";
    state.selectedDeviceNameLocked = false;
    if (deviceState) {
      state.activePresetId = "";
      app.core.setState(deviceState);
      app.canvas.render();
      app.presets.updateMobileCurrent();
    }
    await loadDeviceState(id);
    await loadDevices();
  }

  async function loadDeviceState(id) {
    try {
      state.selectedDeviceState = await app.api.getState(id);
      app.core.setState(state.selectedDeviceState);
      app.canvas.render();
      app.presets.reconcile(state.selectedDeviceState);
      updateRemoteLockButton();
      app.settings.updateCrossToggleButton();
      app.settings.updateRandomPointsButton();
    } catch (_) {}
  }

  function groupPhysicalDevices(devices) {
    const groups = new Map();
    devices.forEach((device) => {
      const physicalId = String(device.deviceId || device.id);
      if (!groups.has(physicalId)) groups.set(physicalId, { id: physicalId, devices: [] });
      groups.get(physicalId).devices.push(device);
    });
    return Array.from(groups.values());
  }

  async function togglePhysicalDeviceSelection(group) {
    const ids = group.devices.map((device) => device.id);
    const allSelected = ids.every((id) => state.selectedDeviceIds.has(id));
    if (allSelected) {
      ids.forEach((id) => state.selectedDeviceIds.delete(id));
      if (ids.includes(state.selectedDeviceId)) {
        const nextId = state.selectedDeviceIds.values().next().value || "";
        const nextDevice = state.lastDevices.find((device) => device.id === nextId);
        if (nextDevice) {
          await selectDevice(nextDevice.id, nextDevice.state);
          return;
        }
        clearSelectedDevice();
      }
      renderDevices(state.lastDevices);
      return;
    }
    ids.forEach((id) => state.selectedDeviceIds.add(id));
    state.selectionInitialized = true;
    const first = group.devices.find((device) => device.online) || group.devices[0];
    if (first) await selectDevice(first.id, first.state);
  }

  function createPhysicalDeviceCard() {
    const groupElement = document.createElement("div");
    groupElement.className = "device-physical-group";
    const item = document.createElement("div");
    item.className = "device-item device-parent-item";
    const selectButton = document.createElement("button");
    selectButton.type = "button";
    selectButton.className = "device-card-main";
    const mark = document.createElement("span");
    mark.className = "device-select-mark";
    mark.setAttribute("aria-hidden", "true");
    const frame = document.createElement("span");
    frame.className = "device-thumbnail-frame";
    const thumbnail = document.createElement("canvas");
    thumbnail.className = "device-thumbnail";
    frame.append(thumbnail);
    const name = document.createElement("span");
    name.className = "device-card-name";
    const status = document.createElement("span");
    status.className = "device-card-status";
    const dot = document.createElement("span");
    const statusText = document.createElement("span");
    statusText.className = "device-card-status-text";
    status.append(dot, statusText);
    selectButton.append(mark, frame, name, status);
    const expandButton = document.createElement("button");
    expandButton.type = "button";
    expandButton.className = "device-expand-button";
    const deleteButton = document.createElement("button");
    deleteButton.type = "button";
    deleteButton.className = "device-delete-button";
    deleteButton.textContent = "×";
    const children = document.createElement("div");
    children.className = "device-session-list";
    groupElement.append(item, children);
    item.append(selectButton, expandButton, deleteButton);

    const card = {
      groupElement,
      item,
      selectButton,
      thumbnail,
      name,
      dot,
      statusText,
      mark,
      expandButton,
      deleteButton,
      children,
      group: null
    };
    selectButton.addEventListener("click", () => {
      if (card.group) togglePhysicalDeviceSelection(card.group);
    });
    expandButton.addEventListener("click", () => {
      if (!card.group || card.group.devices.length < 2) return;
      if (state.expandedDeviceIds.has(card.group.id)) state.expandedDeviceIds.delete(card.group.id);
      else state.expandedDeviceIds.add(card.group.id);
      renderDevices(state.lastDevices);
    });
    deleteButton.addEventListener("click", async () => {
      if (!card.group || card.group.devices.some((device) => device.online)) return;
      try {
        await deleteOfflineDevices(card.group.devices.map((device) => device.id));
      } catch (_) {
        updateStatus("删除离线设备失败");
      }
    });
    return card;
  }

  function updatePhysicalDeviceCard(card, group) {
    card.group = group;
    const ids = group.devices.map((device) => device.id);
    const selectedCount = ids.filter((id) => state.selectedDeviceIds.has(id)).length;
    const onlineDevices = group.devices.filter((device) => device.online);
    const representative = group.devices.find((device) => device.id === state.selectedDeviceId) || onlineDevices[0] || group.devices[0];
    const expanded = group.devices.length > 1 && state.expandedDeviceIds.has(group.id);
    const active = ids.includes(state.selectedDeviceId);
    const allSelected = selectedCount === ids.length;
    const partiallySelected = selectedCount > 0 && !allSelected;
    card.groupElement.className = `device-physical-group${expanded ? " expanded" : ""}`;
    card.item.className = `device-item device-parent-item${group.devices.length > 1 ? " has-expand" : ""}${active ? " active" : ""}${allSelected ? " batch-selected" : ""}${partiallySelected ? " partially-selected" : ""}${onlineDevices.length ? "" : " offline"}`;
    card.mark.textContent = partiallySelected ? "−" : "✓";
    const deviceLabel = representative?.name || group.id;
    card.name.textContent = deviceLabel;
    card.name.title = deviceLabel;
    card.selectButton.setAttribute("aria-label", `${deviceLabel}，${group.devices.length} 个接收页面，${onlineDevices.length} 个在线`);
    card.selectButton.setAttribute("aria-pressed", partiallySelected ? "mixed" : String(allSelected));
    card.dot.className = `online-dot${onlineDevices.length ? "" : " offline"}`;
    const sharedGroup = group.devices.every((device) => device.group === group.devices[0].group)
      ? String(group.devices[0].group || "")
      : "";
    const groupText = sharedGroup ? ` · ${sharedGroup}` : "";
    card.statusText.textContent = `${group.devices.length} 个页面 · ${onlineDevices.length} 在线${groupText}`;
    card.statusText.title = card.statusText.textContent;
    card.expandButton.hidden = group.devices.length < 2;
    card.expandButton.textContent = expanded ? "▴" : "▾";
    card.expandButton.title = expanded ? `收起 ${deviceLabel} 的接收页面` : `展开 ${deviceLabel} 的接收页面`;
    card.expandButton.setAttribute("aria-label", card.expandButton.title);
    card.expandButton.setAttribute("aria-expanded", String(expanded));
    card.deleteButton.title = `删除离线设备：${deviceLabel}`;
    card.deleteButton.setAttribute("aria-label", card.deleteButton.title);
    card.children.hidden = !expanded;
    if (representative) {
      app.canvas.renderDeviceThumbnail(card.thumbnail, representative.state || readState(), representative.width, representative.height);
    }
  }

  function createDeviceCard() {
    const item = document.createElement("div");
    item.className = "device-item";
    const selectButton = document.createElement("button");
    selectButton.type = "button";
    selectButton.className = "device-card-main";
    const mark = document.createElement("span");
    mark.className = "device-select-mark";
    mark.textContent = "✓";
    mark.setAttribute("aria-hidden", "true");
    const frame = document.createElement("span");
    frame.className = "device-thumbnail-frame";
    const thumbnail = document.createElement("canvas");
    thumbnail.className = "device-thumbnail";
    frame.append(thumbnail);
    const name = document.createElement("span");
    name.className = "device-card-name";
    const status = document.createElement("span");
    status.className = "device-card-status";
    const dot = document.createElement("span");
    const statusText = document.createElement("span");
    statusText.className = "device-card-status-text";
    status.append(dot, statusText);
    selectButton.append(mark, frame, name, status);
    const deleteButton = document.createElement("button");
    deleteButton.type = "button";
    deleteButton.className = "device-delete-button";
    deleteButton.textContent = "×";
    item.append(selectButton, deleteButton);

    const card = { item, selectButton, thumbnail, name, dot, statusText, deleteButton, device: null };
    selectButton.addEventListener("click", () => {
      if (card.device) toggleDeviceSelection(card.device);
    });
    deleteButton.addEventListener("click", async () => {
      if (!card.device || card.device.online) return;
      try {
        await deleteOfflineDevices([card.device.id]);
      } catch (_) {
        updateStatus("删除离线设备失败");
      }
    });
    return card;
  }

  function updateDeviceCard(card, device, options = {}) {
    card.device = device;
    card.item.className = `device-item${device.id === state.selectedDeviceId ? " active" : ""}${state.selectedDeviceIds.has(device.id) ? " batch-selected" : ""}${device.online ? "" : " offline"}`;
    const age = Math.max(0, Math.round((Date.now() - device.lastSeen) / 1000));
    const deviceWidth = Math.max(1, Number(device.width) || 9);
    const deviceHeight = Math.max(1, Number(device.height) || 16);
    const deviceLabel = device.name || device.id;
    const sessionSuffix = String(device.sessionId || device.id).slice(-4);
    card.selectButton.setAttribute("aria-label", `${deviceLabel}，接收页面 ${sessionSuffix}，${device.online ? "在线" : "已离线"}`);
    card.selectButton.setAttribute("aria-pressed", String(state.selectedDeviceIds.has(device.id)));
    card.thumbnail.setAttribute("aria-label", `${deviceLabel} 投放预览`);
    card.name.textContent = options.sessionIndex
      ? `接收页面 ${options.sessionIndex}`
      : `${deviceLabel} · ${sessionSuffix}`;
    card.name.title = `${deviceLabel}，接收页面 ${options.sessionIndex || sessionSuffix}，会话 ${sessionSuffix}`;
    card.dot.className = `online-dot${device.online ? "" : " offline"}`;
    const groupText = device.group ? ` · ${device.group}` : "";
    card.statusText.textContent = device.online ? `${deviceWidth} × ${deviceHeight}${groupText}` : `已离线 · ${age}s${groupText}`;
    card.statusText.title = card.statusText.textContent;
    card.deleteButton.title = `删除离线设备：${deviceLabel}`;
    card.deleteButton.setAttribute("aria-label", `删除离线设备：${deviceLabel}`);
    app.canvas.renderDeviceThumbnail(card.thumbnail, device.state || readState(), deviceWidth, deviceHeight);
  }

  function reconcileChildCards(container, desiredNodes) {
    const desiredSet = new Set(desiredNodes);
    Array.from(container.children).forEach((node) => {
      if (!desiredSet.has(node)) node.remove();
    });
    desiredNodes.forEach((node, index) => {
      const current = container.children[index];
      if (current !== node) container.insertBefore(node, current || null);
    });
  }

  function renderDevices(devices) {
    state.lastDevices = devices;
    const previousScrollTop = dom.deviceList.scrollTop;
    const validIds = new Set(devices.map((device) => device.id));
    state.selectedDeviceIds.forEach((id) => {
      if (!validIds.has(id)) state.selectedDeviceIds.delete(id);
    });
    if (!state.selectionInitialized && devices.length) {
      const firstPhysicalId = devices[0].deviceId || devices[0].id;
      devices
        .filter((device) => (device.deviceId || device.id) === firstPhysicalId)
        .forEach((device) => state.selectedDeviceIds.add(device.id));
      state.selectedDeviceId = devices[0].id;
      state.selectionInitialized = true;
    }
    updateGroupOptions(devices);
    if (!devices.length) {
      state.selectionInitialized = false;
      state.deviceCards.forEach((card) => card.item.remove());
      state.deviceCards.clear();
      state.deviceGroupCards.forEach((card) => card.groupElement.remove());
      state.deviceGroupCards.clear();
      state.devicePreviewCanvases.clear();
      state.deviceGroupPreviewCanvases.clear();
      state.expandedDeviceIds.clear();
      dom.deviceList.textContent = "";
      const empty = document.createElement("div");
      empty.className = "readout device-list-empty";
      empty.textContent = "暂无投放端在线";
      dom.deviceList.append(empty);
      state.selectedDeviceInfo = null;
      state.selectedDeviceState = null;
      dom.deviceNameInput.value = "";
      dom.deviceNameInput.disabled = true;
      dom.saveDeviceNameBtn.disabled = true;
      dom.currentDeviceLabel.textContent = "当前未选择设备";
      updateRemoteLockButton();
      renderPreview(readState());
      updateSelectionUi();
      return;
    }
    const physicalGroups = groupPhysicalDevices(devices);
    const validPhysicalIds = new Set(physicalGroups.map((group) => group.id));
    state.expandedDeviceIds.forEach((id) => {
      if (!validPhysicalIds.has(id)) state.expandedDeviceIds.delete(id);
    });
    dom.deviceList.querySelector(".device-list-empty")?.remove();
    state.deviceCards.forEach((card, id) => {
      if (validIds.has(id)) return;
      card.item.remove();
      state.deviceCards.delete(id);
      state.devicePreviewCanvases.delete(id);
    });
    state.deviceGroupCards.forEach((card, id) => {
      if (validPhysicalIds.has(id)) return;
      card.groupElement.remove();
      state.deviceGroupCards.delete(id);
      state.deviceGroupPreviewCanvases.delete(id);
    });
    physicalGroups.forEach((group) => {
      let groupCard = state.deviceGroupCards.get(group.id);
      if (!groupCard) {
        groupCard = createPhysicalDeviceCard();
        state.deviceGroupCards.set(group.id, groupCard);
        state.deviceGroupPreviewCanvases.set(group.id, groupCard.thumbnail);
      }
      updatePhysicalDeviceCard(groupCard, group);
      if (state.expandedDeviceIds.has(group.id) && group.devices.length > 1) {
        const childNodes = group.devices.map((device, index) => {
          let card = state.deviceCards.get(device.id);
          if (!card) {
            card = createDeviceCard();
            state.deviceCards.set(device.id, card);
            state.devicePreviewCanvases.set(device.id, card.thumbnail);
          }
          updateDeviceCard(card, device, { sessionIndex: index + 1 });
          return card.item;
        });
        reconcileChildCards(groupCard.children, childNodes);
      }
      dom.deviceList.append(groupCard.groupElement);
    });
    dom.deviceList.scrollTop = Math.min(previousScrollTop, Math.max(0, dom.deviceList.scrollHeight - dom.deviceList.clientHeight));
    let selected = devices.find((device) => device.id === state.selectedDeviceId && state.selectedDeviceIds.has(device.id));
    if (!selected && state.selectedDeviceIds.size) {
      selected = devices.find((device) => state.selectedDeviceIds.has(device.id));
      state.selectedDeviceId = selected?.id || "";
    }
    if (selected?.state) {
      state.selectedDeviceInfo = selected;
      state.selectedDeviceState = selected.state;
      dom.currentDeviceLabel.textContent = `当前编辑：${selected.name || selected.id} · 共选择 ${state.selectedDeviceIds.size} 个页面`;
      if (state.selectedDeviceNameId !== selected.id) {
        state.selectedDeviceNameId = selected.id;
        state.selectedDeviceNameDraft = selected.name || "";
        state.selectedDeviceNameLocked = false;
      } else if (!state.selectedDeviceNameLocked && document.activeElement !== dom.deviceNameInput) {
        state.selectedDeviceNameDraft = selected.name || state.selectedDeviceNameDraft || "";
      }
      if (!state.applyingRemote) {
        app.core.setState(state.selectedDeviceState);
        app.canvas.render();
        app.presets.reconcile(state.selectedDeviceState);
      }
      updateRemoteLockButton();
      dom.deviceNameInput.disabled = false;
      dom.saveDeviceNameBtn.disabled = false;
      if (document.activeElement !== dom.deviceNameInput) dom.deviceNameInput.value = state.selectedDeviceNameDraft;
      renderPreview(selected.state);
    }
    if (!selected) {
      state.selectedDeviceInfo = null;
      state.selectedDeviceState = null;
      state.selectedDeviceNameId = "";
      state.selectedDeviceNameDraft = "";
      state.selectedDeviceNameLocked = false;
      dom.deviceNameInput.value = "";
      dom.deviceNameInput.disabled = true;
      dom.saveDeviceNameBtn.disabled = true;
      dom.currentDeviceLabel.textContent = "当前未选择设备";
      updateRemoteLockButton();
      app.settings.updateCrossToggleButton();
      app.settings.updateRandomPointsButton();
    }
    updateSelectionUi();
  }

  function updateRemoteLockButton() {
    const targets = selectedTargets();
    const allLocked = targets.length > 0 && targets.every((device) => String(device.state?.displayLocked || device.state?.forceLock || "0") === "1");
    const prefix = targets.length > 1 ? "批量" : "远程";
    dom.forceLockBtn.textContent = `${prefix}${allLocked ? "解锁" : "锁定"}${targets.length > 1 ? ` (${targets.length})` : ""}`;
    dom.forceLockBtn.disabled = targets.length === 0;
  }

  function renderPreview(visualState) {
    if (state.role !== "control") return;
    const selectedCanvas = state.devicePreviewCanvases.get(state.selectedDeviceId);
    if (selectedCanvas?.isConnected) {
      app.canvas.renderDeviceThumbnail(selectedCanvas, visualState, state.selectedDeviceInfo?.width, state.selectedDeviceInfo?.height);
    }
    const physicalId = state.selectedDeviceInfo?.deviceId || state.selectedDeviceInfo?.id;
    const groupCanvas = state.deviceGroupPreviewCanvases.get(physicalId);
    if (groupCanvas) app.canvas.renderDeviceThumbnail(groupCanvas, visualState, state.selectedDeviceInfo?.width, state.selectedDeviceInfo?.height);
  }

  async function loadDevices() {
    if (!state.serverMode || state.role !== "control" || state.deviceRequestInFlight) return;
    state.deviceRequestInFlight = true;
    try {
      const data = await app.api.getDevices();
      renderDevices(data.devices || []);
    } catch (_) {
      updateStatus("设备列表离线");
    } finally {
      state.deviceRequestInFlight = false;
    }
  }

  async function loadDeviceSettings() {
    if (!state.serverMode || state.role !== "control") return;
    try {
      const data = await app.api.getDeviceSettings();
      const value = String(data.deviceRetentionMs ?? 600000);
      if ([...dom.deviceRetentionSelect.options].some((option) => option.value === value)) {
        dom.deviceRetentionSelect.value = value;
      }
    } catch (_) {
      updateStatus("自动清理设置读取失败");
    }
  }

  async function saveDeviceRetention() {
    const result = await app.api.saveDeviceSettings(Number(dom.deviceRetentionSelect.value));
    updateStatus(dom.deviceRetentionSelect.value === "0" ? "离线设备将保留" : "自动清理时间已保存");
    if (result.deletedIds?.length) await loadDevices();
  }

  async function deleteOfflineDevices(ids = [], allOffline = false) {
    const result = await app.api.deleteDevices(ids, allOffline);
    updateStatus(result.deletedIds?.length ? `已删除 ${result.deletedIds.length} 台离线设备` : "没有可删除的离线设备");
    await loadDevices();
  }

  async function saveSelectedDeviceName(name) {
    if (!state.selectedDeviceId) return;
    const nextName = String(name || "").trim().slice(0, 40);
    if (!nextName) {
      updateStatus("名称不能为空");
      return;
    }
    dom.saveDeviceNameBtn.disabled = true;
    try {
      await app.api.renameDevice(state.selectedDeviceId, nextName);
      state.selectedDeviceInfo = { ...(state.selectedDeviceInfo || {}), name: nextName };
      state.selectedDeviceNameDraft = nextName;
      state.selectedDeviceNameLocked = false;
      updateStatus(`已改名为 ${nextName}`);
      await loadDevices();
    } finally {
      dom.saveDeviceNameBtn.disabled = !state.selectedDeviceId;
    }
  }

  function init() {
    app.canvas.addRenderListener(renderPreview);
    dom.refreshDevicesBtn.addEventListener("click", loadDevices);
    dom.deviceRetentionSelect.addEventListener("change", async () => {
      try {
        await saveDeviceRetention();
      } catch (_) {
        updateStatus("自动清理时间保存失败");
      }
    });
    dom.clearOfflineDevicesBtn.addEventListener("click", async () => {
      if (!state.lastDevices.some((device) => !device.online)) {
        updateStatus("没有离线设备");
        return;
      }
      if (!global.confirm("删除当前全部离线设备？在线设备不会受影响。")) return;
      try {
        await deleteOfflineDevices([], true);
      } catch (_) {
        updateStatus("清理离线设备失败");
      }
    });
    dom.selectOnlineBtn.addEventListener("click", () => selectDeviceSet(state.lastDevices.filter((device) => device.online)));
    dom.clearSelectionBtn.addEventListener("click", () => {
      state.selectedDeviceIds.clear();
      state.selectionInitialized = true;
      clearSelectedDevice();
      renderDevices(state.lastDevices);
    });
    dom.groupSelect.addEventListener("change", () => {
      dom.selectGroupBtn.disabled = !dom.groupSelect.value;
    });
    dom.selectGroupBtn.addEventListener("click", () => {
      const group = dom.groupSelect.value;
      if (!group) return;
      selectDeviceSet(state.lastDevices.filter((device) => device.group === group));
    });
    dom.assignGroupBtn.addEventListener("click", async () => {
      const group = dom.groupNameInput.value.trim().slice(0, 40);
      if (!group) {
        updateStatus("请输入分组名称");
        dom.groupNameInput.focus();
        return;
      }
      try {
        await assignSelectedGroup(group);
        dom.groupNameInput.value = "";
        dom.groupSelect.value = group;
        dom.selectGroupBtn.disabled = false;
      } catch (_) {
        updateStatus("设置分组失败");
      }
    });
    dom.clearGroupBtn.addEventListener("click", async () => {
      try {
        await assignSelectedGroup("");
      } catch (_) {
        updateStatus("移出分组失败");
      }
    });
    dom.groupNameInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter") dom.assignGroupBtn.click();
    });
    dom.saveDeviceNameBtn.addEventListener("click", async () => {
      try {
        await saveSelectedDeviceName(dom.deviceNameInput.value);
      } catch (_) {
        updateStatus("改名失败");
      }
    });
    dom.forceLockBtn.addEventListener("click", async () => {
      const targets = selectedTargets();
      const allLocked = targets.length > 0 && targets.every((device) => String(device.state?.displayLocked || device.state?.forceLock || "0") === "1");
      try {
        await setRemoteLock(!allLocked);
      } catch (_) {
        updateStatus("远程锁定失败");
      }
    });
    dom.deviceNameInput.addEventListener("keydown", (event) => {
      if (event.key === "Enter") dom.saveDeviceNameBtn.click();
    });
    dom.deviceNameInput.addEventListener("input", () => {
      state.selectedDeviceNameDraft = dom.deviceNameInput.value;
      state.selectedDeviceNameLocked = true;
    });
    dom.deviceNameInput.addEventListener("focus", () => {
      state.selectedDeviceNameLocked = true;
    });
    dom.deviceNameInput.addEventListener("blur", () => {
      state.selectedDeviceNameLocked = false;
      state.selectedDeviceNameDraft = dom.deviceNameInput.value;
    });
  }

  async function setRemoteLock(enabled) {
    const targets = selectedTargets();
    if (!targets.length) return;
    const result = await app.api.sendLockCommand(targets.map((device) => device.id), enabled);
    app.projection.showLockCommandStatus(result);
    await Promise.allSettled([
      state.selectedDeviceId ? loadDeviceState(state.selectedDeviceId) : Promise.resolve(),
      loadDevices()
    ]);
  }

  app.devices = {
    init,
    load: loadDevices,
    loadSettings: loadDeviceSettings,
    selectedTargets,
    render: renderDevices,
    selectSet: selectDeviceSet,
    clearSelected: clearSelectedDevice,
    assignGroup: assignSelectedGroup,
    saveName: saveSelectedDeviceName,
    setRemoteLock,
    updateRemoteLockButton,
    renderPreview
  };
})(window);
