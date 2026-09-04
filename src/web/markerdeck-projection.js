(function (global) {
  "use strict";

  const app = global.MarkerDeck;
  const { dom, state, updateStatus } = app.core;

  async function ensureUniqueSessionId() {
    if (!("BroadcastChannel" in global)) return;
    if (!state.sessionClaimChannel) {
      state.sessionClaimChannel = new BroadcastChannel(app.core.STORAGE_KEYS.sessionClaims);
      state.sessionClaimChannel.onmessage = (event) => {
        const message = event.data || {};
        if (message.type === "claim" && message.sessionId === state.sessionId && message.pageInstanceId !== state.pageInstanceId) {
          state.sessionClaimChannel.postMessage({
            type: "occupied",
            sessionId: state.sessionId,
            targetInstanceId: message.pageInstanceId
          });
        }
        if (message.type === "occupied" && message.sessionId === state.sessionId && message.targetInstanceId === state.pageInstanceId) {
          state.sessionConflictResolver?.(true);
        }
      };
    }
    const conflicted = await new Promise((resolve) => {
      state.sessionConflictResolver = resolve;
      state.sessionClaimChannel.postMessage({ type: "claim", sessionId: state.sessionId, pageInstanceId: state.pageInstanceId });
      setTimeout(() => resolve(false), 100);
    });
    state.sessionConflictResolver = null;
    if (!conflicted) return;
    state.sessionId = app.core.createRandomId("screen");
    sessionStorage.setItem(app.core.STORAGE_KEYS.sessionId, state.sessionId);
    state.sessionClaimChannel.postMessage({ type: "claim", sessionId: state.sessionId, pageInstanceId: state.pageInstanceId });
  }

  async function requestWakeLock() {
    if (!("wakeLock" in navigator)) return;
    try {
      state.wakeLock = await navigator.wakeLock.request("screen");
    } catch (_) {
      state.wakeLock = null;
    }
  }

  function invokeAndroidEmergencyControl(methodName) {
    const bridge = global.markerdeckAndroid;
    if (!bridge) return;
    try {
      bridge[methodName]?.();
    } catch (_) {}
  }

  function hideAndroidEmergencyControls() {
    invokeAndroidEmergencyControl("hideEmergencyControls");
  }

  function showAndroidEmergencyControlsAfterLocalUnlock() {
    invokeAndroidEmergencyControl("showEmergencyControls");
  }

  function showAndroidEmergencyControlsForUnlockedProjection() {
    invokeAndroidEmergencyControl("showEmergencyControlsForUnlockedProjection");
  }

  function showAndroidEmergencyExitWhileUnlocked() {
    invokeAndroidEmergencyControl("showEmergencyExitWhileUnlocked");
  }

  function beginProjectionControlInteraction() {
    invokeAndroidEmergencyControl("beginProjectionControlInteraction");
  }

  function endProjectionControlInteraction() {
    invokeAndroidEmergencyControl("endProjectionControlInteraction");
  }

  function isPersistentFocusTarget(target) {
    if (!target?.matches) return false;
    if (target.matches("textarea, [contenteditable='true']")) return true;
    if (!target.matches("input, select")) return false;
    const type = String(target.type || "text").toLowerCase();
    return !["button", "checkbox", "color", "file", "hidden", "image", "radio", "range", "reset", "submit"].includes(type);
  }

  function isPickerFocusTarget(target) {
    return !!target?.matches?.("select, input[type='color']");
  }

  function initControlInteractionGuards() {
    const interactionFactory = app.controlInteraction?.createControlInteractionController;
    const reasonTrackerFactory = app.controlInteraction?.createControlInteractionReasonTracker;
    if (!interactionFactory || !reasonTrackerFactory) return;
    const surfaces = [dom.panel, dom.mobilePresetBar, dom.mobilePresetSheet].filter(Boolean);
    if (!surfaces.length) return;

    const interaction = interactionFactory({
      onStart: beginProjectionControlInteraction,
      onEnd: endProjectionControlInteraction
    });
    const reasonTracker = reasonTrackerFactory();
    const activePointers = new Set();

    const containsTarget = (target) => surfaces.some((surface) => surface.contains(target));
    const pointerReason = (pointerId) => `pointer:${String(pointerId ?? "mouse")}`;
    const isControlTarget = (target) => !!target?.closest?.(
      "button, input, select, textarea, [contenteditable='true'], [role='button'], [tabindex]"
    );
    const stopSurfaceEvent = (event) => event.stopPropagation();
    const endPointer = (event) => {
      const reason = pointerReason(event.pointerId);
      if (!activePointers.delete(reason)) return;
      interaction.end(reason);
      event.stopPropagation();
    };
    const endKeyboardInteraction = (event) => {
      const reason = reasonTracker.endKeyboard(event);
      if (!reason) return false;
      interaction.end(reason);
      return true;
    };
    const endAllKeyboardInteractions = () => {
      reasonTracker.resetKeyboard().forEach((reason) => interaction.end(reason));
    };
    const resetControlInteractions = () => {
      activePointers.clear();
      reasonTracker.reset();
      interaction.reset();
    };
    const endFocusInteraction = (event) => {
      const target = event.target;
      if (isPersistentFocusTarget(target) || isPickerFocusTarget(target)) {
        interaction.end(reasonTracker.focusReason(target));
      }
      endAllKeyboardInteractions();
      event.stopPropagation();
    };
    const restoreFocusedControl = () => {
      const target = document.activeElement;
      if (!containsTarget(target)) return;
      if (isPersistentFocusTarget(target) || isPickerFocusTarget(target)) {
        interaction.begin(reasonTracker.focusReason(target));
      }
    };

    surfaces.forEach((surface) => {
      surface.addEventListener("pointerdown", (event) => {
        const reason = pointerReason(event.pointerId);
        activePointers.add(reason);
        interaction.begin(reason);
        event.stopPropagation();
      });
      surface.addEventListener("pointerup", endPointer);
      surface.addEventListener("pointercancel", endPointer);
      surface.addEventListener("lostpointercapture", endPointer);
      surface.addEventListener("pointermove", stopSurfaceEvent);
      surface.addEventListener("click", (event) => {
        event.stopPropagation();
        if (!interaction.isActive()) {
          interaction.begin("activation");
          interaction.end("activation");
        }
      });
      surface.addEventListener("focusin", (event) => {
        if (isPersistentFocusTarget(event.target) || isPickerFocusTarget(event.target)) {
          interaction.begin(reasonTracker.focusReason(event.target));
        }
        event.stopPropagation();
      });
      surface.addEventListener("focusout", endFocusInteraction);
      surface.addEventListener("blur", endFocusInteraction, true);
      surface.addEventListener("input", (event) => {
        if (isPersistentFocusTarget(event.target)) {
          interaction.begin(reasonTracker.focusReason(event.target));
        }
        event.stopPropagation();
      });
      surface.addEventListener("change", (event) => {
        if (isPickerFocusTarget(event.target)) {
          interaction.end(reasonTracker.focusReason(event.target));
        }
        event.stopPropagation();
      });
      surface.addEventListener("keydown", (event) => {
        if (!isControlTarget(event.target)) return;
        const keyboardInteraction = reasonTracker.beginKeyboard(event);
        if (keyboardInteraction?.started) interaction.begin(keyboardInteraction.reason);
        event.stopPropagation();
      });
      surface.addEventListener("keyup", (event) => {
        endKeyboardInteraction(event);
        event.stopPropagation();
      });
      ["touchstart", "touchmove", "touchend", "touchcancel"].forEach((eventName) => {
        surface.addEventListener(eventName, stopSurfaceEvent);
      });
    });

    global.addEventListener("pointerup", endPointer, true);
    global.addEventListener("pointercancel", endPointer, true);
    global.addEventListener("keyup", (event) => {
      if (endKeyboardInteraction(event)) event.stopPropagation();
    }, true);
    global.addEventListener("blur", resetControlInteractions);
    global.addEventListener("focus", restoreFocusedControl);
    document.addEventListener("visibilitychange", () => {
      if (document.visibilityState !== "visible") resetControlInteractions();
    });
  }

  function pushExitGuard() {
    try {
      history.pushState({ projectionLocked: true }, "");
    } catch (_) {}
  }

  function applyVisibleLockState(enabled, options = {}) {
    if (enabled) {
      state.lockedByRemote = !!options.remote;
      state.locked = true;
      state.deviceForceLock = "1";
      hideAndroidEmergencyControls();
      document.activeElement?.blur?.();
      global.chromaDesktop?.setProjectionLocked?.(true);
      document.body.classList.add("locked");
      dom.lockBtn.textContent = "已锁定";
      pushExitGuard();
    } else {
      state.lockedByRemote = false;
      state.locked = false;
      state.deviceForceLock = "0";
      hideAndroidEmergencyControls();
      global.chromaDesktop?.setProjectionLocked?.(false);
      document.body.classList.remove("locked");
      dom.lockBtn.textContent = "锁定投放";
    }
    app.canvas.render();
    return state.locked === enabled;
  }

  async function finishLockProjection(enabled, options = {}) {
    const localUserAction = options.localUserAction === true && options.remote !== true;
    if (!enabled) {
      if (document.fullscreenElement && document.exitFullscreen) {
        try {
          await document.exitFullscreen();
        } catch (_) {}
      }
      if (state.wakeLock) {
        try {
          await state.wakeLock.release();
        } catch (_) {}
        state.wakeLock = null;
      }
      if (options.localEmergency === true && !state.locked) showAndroidEmergencyControlsAfterLocalUnlock();
      if (state.role === "display") await publishState();
      return;
    }

    const root = document.documentElement;
    if (localUserAction && root.requestFullscreen) {
      try {
        await root.requestFullscreen({ navigationUI: "hide" });
      } catch (_) {}
    }
    document.activeElement?.blur?.();
    await requestWakeLock();
    if (state.role === "display") await publishState();
  }

  async function lockProjection(options = {}) {
    if (!applyVisibleLockState(true, options)) throw new Error("lock-apply-failed");
    await finishLockProjection(true, options);
  }

  async function unlockProjection(options = {}) {
    if (!applyVisibleLockState(false, options)) throw new Error("unlock-apply-failed");
    await finishLockProjection(false, options);
  }

  function isLockHotkey(event) {
    return event.ctrlKey && event.altKey && event.shiftKey &&
      !event.metaKey && (event.code === "KeyL" || event.key.toLowerCase() === "l");
  }

  async function toggleLockFromHotkey() {
    if (state.serverMode && (state.role === "control" || state.role === "display")) {
      const onlineDisplays = state.lastDevices.filter((device) => device.online && device.role === "display");
      const allLocked = onlineDisplays.length > 0 && onlineDisplays.every((device) =>
        String(device.state?.displayLocked || device.state?.forceLock || "0") === "1"
      );
      const enabled = state.role === "display" ? !state.locked : !allLocked;
      const result = await app.api.broadcastLock(enabled);
      if (state.role === "display") {
        await handleLockCommand(enabled, result.commandId, {
          global: true,
          remote: false,
          localUserAction: true
        });
      }
      showLockCommandStatus(result);
      return;
    }
    if (state.locked) await unlockProjection({ localUserAction: true });
    else await lockProjection({ localUserAction: true });
  }

  function showLockCommandStatus(status) {
    const action = status.enabled ? "锁定" : "解锁";
    const targetCount = Number(status.targetCount || 0);
    const confirmedCount = Number(status.confirmedCount || 0);
    const failedCount = Number(status.failedCount || 0);
    const pendingCount = Number(status.pendingCount ?? Math.max(0, targetCount - Number(status.acknowledgedCount || 0)));
    const failureText = failedCount ? `，${failedCount} 个失败` : "";
    const pendingText = pendingCount ? `，${pendingCount} 个未响应` : "";
    updateStatus(`${action}确认 ${confirmedCount}/${targetCount}${failureText}${pendingText}`);
  }

  async function acknowledgeLock(commandId, ok = true, error = "") {
    if (!state.serverMode || !commandId || commandId === "0") return;
    try {
      await app.api.acknowledgeLock(commandId, state.sessionId, ok, state.locked, error);
    } catch (_) {}
  }

  async function handleLockCommand(enabled, commandId, options = {}) {
    const id = String(commandId || "0");
    const isGlobal = options.global === true;
    if (id === "0") return;
    if (state.handledLockCommandIds.has(id)) {
      if (isGlobal) state.lastGlobalLockCommandId = id;
      else state.lastLockCommandId = id;
      return;
    }
    state.handledLockCommandIds.add(id);
    if (state.handledLockCommandIds.size > 100) state.handledLockCommandIds.delete(state.handledLockCommandIds.values().next().value);
    if (isGlobal) state.lastGlobalLockCommandId = id;
    else state.lastLockCommandId = id;
    try {
      const projectionOptions = {
        remote: options.remote !== false,
        localUserAction: options.localUserAction === true
      };
      if (state.locked === !!enabled) {
        await acknowledgeLock(id, true, "");
        return;
      }
      await global.MarkerDeckLockFlow.executeLockCommand({
        applyVisible: () => enabled
          ? applyVisibleLockState(true, projectionOptions)
          : applyVisibleLockState(false, projectionOptions),
        runSideEffects: () => finishLockProjection(enabled, projectionOptions),
        acknowledge: (ok, error) => acknowledgeLock(id, ok, error),
        fallbackError: "state-mismatch"
      });
    } catch (error) {
      await acknowledgeLock(id, false, error?.message || "lock-failed");
    }
  }

  async function publishState() {
    if (!state.serverMode || state.applyingRemote) return;
    try {
      if (state.role === "control") {
        const targets = app.devices.selectedTargets();
        if (!targets.length) return;
        const visualState = app.core.readState();
        await Promise.all(targets.map(async (device) => {
          const nextState = app.core.canonicalizeState({ ...(device.state || {}), ...visualState });
          await app.api.postState(device.id, nextState);
          device.state = nextState;
          if (device.id === state.selectedDeviceId) state.selectedDeviceState = nextState;
        }));
        updateStatus(`已同步 ${targets.length} 台设备`);
        return;
      }
      if (state.role !== "display") return;
      const nextState = app.core.canonicalizeState({
        ...app.core.readState(),
        forceLock: state.deviceForceLock,
        displayLocked: state.locked ? "1" : "0",
        lockCommand: "none",
        lockCommandId: state.lastLockCommandId
      });
      await app.api.postState(state.sessionId, nextState);
    } catch (_) {
      updateStatus(state.role === "control" ? "控制端 离线" : "投放端 离线");
    }
  }

  async function applyRemoteState(next) {
    const incoming = next || {};
    const globalCommandId = String(incoming.globalLockCommandId || "0");
    const globalCommand = String(incoming.globalLockCommand || "none");
    const firstRemoteState = !state.globalLockBaselineInitialized;
    if (firstRemoteState) {
      state.lastGlobalLockCommandId = globalCommandId;
      if (globalCommandId !== "0") state.handledLockCommandIds.add(globalCommandId);
      state.globalLockBaselineInitialized = true;
    }
    state.deviceForceLock = String(incoming.forceLock || "0");
    app.core.setState(incoming);
    app.canvas.render();
    const commandId = String(incoming.lockCommandId || "0");
    const command = String(incoming.lockCommand || "none");
    const globalEnabled = globalCommand === "lock" ? true : globalCommand === "unlock" ? false : null;
    const targetEnabled = command === "lock" ? true : command === "unlock" ? false : null;
    if (!firstRemoteState && globalEnabled !== null && globalCommandId !== "0" && globalCommandId !== state.lastGlobalLockCommandId) {
      await handleLockCommand(globalEnabled, globalCommandId, { global: true, remote: true });
    }
    if (targetEnabled !== null && commandId !== "0" && commandId !== state.lastLockCommandId && commandId !== globalCommandId) {
      await handleLockCommand(targetEnabled, commandId, { global: false, remote: true });
    }
    updateStatus("投放端 已连接");
  }

  async function pullState() {
    if (!state.serverMode || state.role !== "display") return;
    try {
      await applyRemoteState(await app.api.getState(state.sessionId));
    } catch (_) {
      updateStatus("投放端 离线");
    }
  }

  function connectEventStream() {
    state.eventSource?.close();
    state.eventSource = null;
    if (!state.serverMode || (state.role !== "control" && state.role !== "display") || !("EventSource" in global)) return;
    state.eventSource = app.api.connectEvents(state.role, state.sessionId, state.pageInstanceId, {
      onConnected: () => {
        if (state.role === "control") app.devices.load();
        if (state.role === "display") pullState();
      },
      onDevices: () => {
        if (state.role === "control") app.devices.load();
      },
      onState: (message) => {
        if (state.role !== "display") return;
        if (!message.sessionId || message.sessionId === state.sessionId) {
          applyRemoteState(message.state || {}).catch(() => updateStatus("投放端状态同步失败"));
        }
      },
      onLockCommand: (message) => {
        if (state.role !== "display") return;
        handleLockCommand(!!message.enabled, message.commandId, {
          global: message.global === true,
          remote: true
        }).catch(() => updateStatus("锁定命令执行失败"));
      },
      onLockAck: (message) => {
        if (state.role === "control") showLockCommandStatus(message);
      },
      onError: () => {
        updateStatus(state.role === "control" ? "控制端 正在重连" : "投放端 正在重连");
      }
    });
  }

  function stopSync() {
    clearInterval(state.syncTimer);
    clearInterval(state.deviceTimer);
    clearInterval(state.heartbeatTimer);
    state.eventSource?.close();
    state.eventSource = null;
    state.syncTimer = 0;
    state.deviceTimer = 0;
    state.heartbeatTimer = 0;
  }

  async function registerDevice(options = {}) {
    if (!state.serverMode || state.role !== "display") return;
    try {
      const result = await app.api.registerDevice({
        id: state.sessionId,
        sessionId: state.sessionId,
        deviceId: state.deviceId,
        pageInstanceId: state.pageInstanceId,
        name: app.core.deviceName(),
        updateName: !!options.updateName,
        role: state.role,
        width: global.innerWidth,
        height: global.innerHeight,
        dpr: global.devicePixelRatio || 1,
        userAgent: navigator.userAgent
      });
      if (result.sessionId && result.sessionId !== state.sessionId) {
        state.sessionId = String(result.sessionId);
        sessionStorage.setItem(app.core.STORAGE_KEYS.sessionId, state.sessionId);
        state.globalLockBaselineInitialized = false;
        state.lastLockCommandId = "0";
        state.lastGlobalLockCommandId = "0";
      }
      if (result.name) app.core.saveLocalDeviceName(result.name);
      await applyRemoteState({
        ...(result.state || {}),
        globalLockCommand: result.globalLockCommand ?? result.state?.globalLockCommand ?? "none",
        globalLockCommandId: result.globalLockCommandId ?? result.state?.globalLockCommandId ?? "0"
      });
    } catch (_) {}
  }

  async function startRole(nextRole) {
    state.role = nextRole;
    global.chromaDesktop?.setDisplayMode?.(state.role === "display");
    document.body.classList.toggle("control-mode", state.role === "control");
    dom.launcher.classList.add("hidden");
    stopSync();
    if (state.role === "local" || state.role === "display") {
      showAndroidEmergencyExitWhileUnlocked();
    }
    if (state.role === "local") {
      updateStatus("本地");
    }
    if (state.role === "control") {
      updateStatus("控制端");
      app.devices.updateRemoteLockButton();
      app.devices.loadSettings();
      app.devices.load();
      connectEventStream();
      state.deviceTimer = setInterval(app.devices.load, 15000);
    }
    if (state.role === "display") {
      updateStatus("投放端");
      await ensureUniqueSessionId();
      const providedDeviceName = app.core.androidProvidedDeviceName();
      if (providedDeviceName) {
        app.core.saveLocalDeviceName(providedDeviceName);
      } else {
        await app.core.requestDisplayName();
      }
      state.globalLockBaselineInitialized = false;
      await registerDevice({ updateName: true });
      connectEventStream();
      state.heartbeatTimer = setInterval(registerDevice, 1500);
      pullState();
      state.syncTimer = setInterval(pullState, 15000);
    }
  }

  function init() {
    initControlInteractionGuards();
    dom.lockBtn.addEventListener("click", () => {
      dom.lockBtn.blur();
      if (state.locked) unlockProjection();
      else lockProjection({ localUserAction: true });
    });
    document.addEventListener("keydown", async (event) => {
      if (isLockHotkey(event)) {
        event.preventDefault();
        event.stopPropagation();
        await toggleLockFromHotkey();
        return;
      }
      if (state.locked && (event.key === "Enter" || event.key === " ")) {
        event.preventDefault();
        event.stopImmediatePropagation();
      }
    }, true);
    dom.hotCorner.addEventListener("pointerdown", () => {
      state.cornerTapCount += 1;
      clearTimeout(state.cornerTimer);
      state.cornerTimer = setTimeout(() => {
        state.cornerTapCount = 0;
      }, 850);
      if (state.cornerTapCount >= 3) {
        state.cornerTapCount = 0;
        if (state.locked) unlockProjection({ localEmergency: true });
        else showAndroidEmergencyControlsForUnlockedProjection();
      }
    });
    global.chromaDesktop?.onProjectionLockHotkey?.(() => {
      toggleLockFromHotkey().catch(() => updateStatus("锁定广播失败"));
    });
    global.addEventListener("popstate", () => {
      if (state.locked) {
        pushExitGuard();
        lockProjection({ localUserAction: false });
      }
    });
    document.addEventListener("visibilitychange", () => {
      if (state.locked && document.visibilityState === "visible") requestWakeLock();
    });
    global.markerdeckRelockProjection = () => {
      if (!state.locked) lockProjection({ localUserAction: false }).catch(() => {});
    };
  }

  app.projection = {
    init,
    startRole,
    stopSync,
    publishState,
    applyRemoteState,
    lock: lockProjection,
    unlock: unlockProjection,
    toggleLockFromHotkey,
    setRemoteLock: (enabled) => app.devices.setRemoteLock(enabled),
    showLockCommandStatus,
    handleLockCommand
  };
})(window);
