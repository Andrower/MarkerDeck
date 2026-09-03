(function (global) {
  "use strict";

  const app = global.MarkerDeck;

  async function readJson(path, options = {}, errorCode = "request") {
    const response = await fetch(path, options);
    if (!response.ok) {
      const error = new Error(errorCode);
      error.status = response.status;
      error.detail = await response.text().catch(() => "");
      throw error;
    }
    return response.json();
  }

  async function readBlob(path, options = {}, errorCode = "request") {
    const response = await fetch(path, options);
    if (!response.ok) throw new Error(errorCode);
    return response.blob();
  }

  function statePath(deviceId) {
    return deviceId ? `/api/state?deviceId=${encodeURIComponent(deviceId)}` : "/api/state";
  }

  function connectEvents(role, sessionId, pageInstanceId, handlers = {}) {
    const query = new URLSearchParams({ role });
    if (role === "display") {
      query.set("sessionId", sessionId);
      query.set("pageInstanceId", pageInstanceId);
    }
    const source = new EventSource(`/api/events?${query}`);
    source.addEventListener("connected", () => handlers.onConnected?.());
    source.addEventListener("devices", () => handlers.onDevices?.());
    source.addEventListener("state", (event) => {
      try {
        handlers.onState?.(JSON.parse(event.data));
      } catch (_) {}
    });
    source.addEventListener("lock-command", (event) => {
      try {
        handlers.onLockCommand?.(JSON.parse(event.data));
      } catch (_) {}
    });
    source.addEventListener("lock-ack", (event) => {
      try {
        handlers.onLockAck?.(JSON.parse(event.data));
      } catch (_) {}
    });
    source.onerror = () => handlers.onError?.();
    return source;
  }

  app.api = {
    getInfo: () => readJson("/api/info", { cache: "no-store" }, "info"),
    getPresets: () => readJson("/api/presets", { cache: "no-store" }, "presets"),
    savePreset: (name, state) => readJson("/api/presets", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ name, state })
    }, "save"),
    deletePreset: (id) => readJson("/api/presets/delete", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ id })
    }, "delete"),
    getDevices: () => readJson("/api/devices", { cache: "no-store" }, "devices"),
    getDeviceSettings: () => readJson("/api/device-settings", { cache: "no-store" }, "settings"),
    saveDeviceSettings: (deviceRetentionMs) => readJson("/api/device-settings", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ deviceRetentionMs })
    }, "settings"),
    deleteDevices: (ids, allOffline) => readJson("/api/devices/delete", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ ids, allOffline })
    }, "delete-devices"),
    renameDevice: (id, name) => readJson("/api/device-name", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ id, name })
    }, "rename"),
    assignDeviceGroup: (ids, group) => readJson("/api/device-group", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ ids, group })
    }, "group"),
    getState: (deviceId) => readJson(statePath(deviceId), { cache: "no-store" }, "state"),
    postState: (deviceId, nextState) => readJson(statePath(deviceId), {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(nextState)
    }, "sync"),
    registerDevice: (payload) => readJson("/api/register", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify(payload)
    }, "register"),
    broadcastLock: (enabled) => readJson("/api/lock-broadcast", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ enabled })
    }, "broadcast lock"),
    sendLockCommand: (ids, enabled) => readJson("/api/lock-command", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ ids, enabled })
    }, "lock"),
    acknowledgeLock: (commandId, sessionId, ok, locked, error) => readJson("/api/lock-ack", {
      method: "POST",
      headers: { "content-type": "application/json" },
      body: JSON.stringify({ commandId, sessionId, ok, locked, error })
    }, "lock-ack"),
    shutdown: () => fetch("/api/shutdown", { method: "POST" }),
    connectEvents,
    startVideo: async (png, duration) => {
      const response = await fetch(`/api/video/start?duration=${encodeURIComponent(duration)}`, {
        method: "POST",
        headers: { "content-type": "image/png" },
        body: png
      });
      if (!response.ok) {
        const detail = await response.text();
        const error = new Error(
          detail.includes("ffmpeg-not-found")
            ? "ffmpeg-not-found"
            : response.status === 429 ? "video-busy" : "video-conversion"
        );
        throw error;
      }
      return response.json();
    },
    getVideoStatus: (id) => readJson(`/api/video/status?id=${encodeURIComponent(id)}`, { cache: "no-store" }, "video-conversion"),
    getVideoResult: (id) => readBlob(`/api/video/result?id=${encodeURIComponent(id)}`, { cache: "no-store" }, "video-conversion")
  };
})(window);
