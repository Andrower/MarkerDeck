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

  function notifyAndroidEmergencyControls(visible) {
    const bridge = global.markerdeckAndroid;
    if (!bridge) return;
    try {
      if (visible) bridge.showEmergencyControls?.();
      else bridge.hideEmergencyControls?.();
    } catch (_) {}
  }

  function pushExitGuard() {
    try {
      history.pushState({ projectionLocked: true }, "");
    } catch (_) {}
  }

  async function lockProjection(options = {}) {
    state.lockedByRemote = !!options.remote;
    state.locked = true;
    notifyAndroidEmergencyControls(false);
    document.activeElement?.blur?.();
    global.chromaDesktop?.setProjectionLocked?.(true);
    document.body.classList.add("locked");
    dom.lockBtn.textContent = "已锁定";
    pushExitGuard();
    const root = document.documentElement;
    if (root.requestFullscreen) {
      try {
        await root.requestFullscreen({ navigationUI: "hide" });
      } catch (_) {}
    }
    document.activeElement?.blur?.();
    await requestWakeLock();
    app.canvas.render();
    if (state.role === "display") await publishState();
  }

  async function unlockProjection(options = {}) {
    const localEmergency = options.localEmergency === true;
    state.lockedByRemote = false;
    state.locked = false;
    state.deviceForceLock = "0";
    notifyAndroidEmergencyControls(false);
    global.chromaDesktop?.setProjectionLocked?.(false);
    document.body.classList.remove("locked");
    dom.lockBtn.textContent = "锁定投放";
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
    app.canvas.render();
    if (localEmergency && !state.locked) notifyAndroidEmergencyControls(true);
    if (state.role === "display") await publishState();
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
      if (state.role === "display") await handleLockCommand(enabled, result.commandId);
      showLockCommandStatus(result);
      return;
    }
    if (state.locked) await unlockProjection();
    else await lockProjection();
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

  async function handleLockCommand(enabled, commandId) {
    const id = String(commandId || "0");
    if (id === "0" || state.handledLockCommandIds.has(id)) return;
    state.handledLockCommandIds.add(id);
    if (state.handledLockCommandIds.size > 100) state.handledLockCommandIds.delete(state.handledLockCommandIds.values().next().value);
    state.lastLockCommandId = id;
    state.lastGlobalLockCommandId = id;
    try {
      if (enabled && !state.locked) await lockProjection({ remote: true });
      if (!enabled && state.locked) await unlockProjection();
      await acknowledgeLock(id, state.locked === !!enabled, state.locked === !!enabled ? "" : "state-mismatch");
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
          const nextState = { ...(device.state || {}), ...visualState };
          await app.api.postState(device.id, nextState);
          device.state = nextState;
          if (device.id === state.selectedDeviceId) state.selectedDeviceState = nextState;
        }));
        updateStatus(`已同步 ${targets.length} 台设备`);
        return;
      }
      if (state.role !== "display") return;
      const nextState = {
        ...app.core.readState(),
        forceLock: state.deviceForceLock,
        displayLocked: state.locked ? "1" : "0",
        lockCommand: "none",
        lockCommandId: state.lastLockCommandId
      };
      await app.api.postState(state.sessionId, nextState);
    } catch (_) {
      updateStatus(state.role === "control" ? "控制端 离线" : "投放端 离线");
    }
  }

  async function applyRemoteState(next) {
    state.deviceForceLock = String(next.forceLock || "0");
    app.core.setState(next);
    app.canvas.render();
    const commandId = String(next.lockCommandId || "0");
    const command = String(next.lockCommand || "none");
    const globalCommandId = String(next.globalLockCommandId || "0");
    const globalCommand = String(next.globalLockCommand || "none");
    if (globalCommandId !== "0" && globalCommandId !== state.lastGlobalLockCommandId) {
      await handleLockCommand(globalCommand === "lock", globalCommandId);
    }
    if (commandId !== "0" && commandId !== state.lastLockCommandId && commandId !== globalCommandId) {
      await handleLockCommand(command === "lock", commandId);
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
        handleLockCommand(!!message.enabled, message.commandId).catch(() => updateStatus("锁定命令执行失败"));
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
      }
      if (result.name) app.core.saveLocalDeviceName(result.name);
      if (result.state && !state.applyingRemote) {
        app.core.setState(result.state);
        app.canvas.render();
      }
      if (!state.globalLockBaselineInitialized) {
        state.lastGlobalLockCommandId = String(result.globalLockCommandId || "0");
        if (state.lastGlobalLockCommandId !== "0") state.handledLockCommandIds.add(state.lastGlobalLockCommandId);
        state.globalLockBaselineInitialized = true;
      }
    } catch (_) {}
  }

  async function startRole(nextRole) {
    state.role = nextRole;
    global.chromaDesktop?.setDisplayMode?.(state.role === "display");
    document.body.classList.toggle("control-mode", state.role === "control");
    dom.launcher.classList.add("hidden");
    stopSync();
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
    dom.lockBtn.addEventListener("click", () => {
      dom.lockBtn.blur();
      if (state.locked) unlockProjection();
      else lockProjection();
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
      if (state.cornerTapCount >= 3 && state.locked) {
        state.cornerTapCount = 0;
        unlockProjection({ localEmergency: true });
      }
    });
    global.chromaDesktop?.onProjectionLockHotkey?.(() => {
      toggleLockFromHotkey().catch(() => updateStatus("锁定广播失败"));
    });
    global.addEventListener("popstate", () => {
      if (state.locked) {
        pushExitGuard();
        lockProjection();
      }
    });
    document.addEventListener("visibilitychange", () => {
      if (state.locked && document.visibilityState === "visible") requestWakeLock();
    });
    global.markerdeckRelockProjection = () => {
      if (!state.locked) lockProjection().catch(() => {});
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
