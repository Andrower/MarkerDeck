(function (global, factory) {
  "use strict";

  if (typeof module === "object" && module.exports) {
    module.exports = factory();
  } else {
    global.MarkerDeckLockFlow = factory();
  }
})(typeof window !== "undefined" ? window : globalThis, function () {
  "use strict";

  function errorMessage(error, fallback) {
    return error?.message || String(error || fallback);
  }

  async function executeLockCommand(options = {}) {
    const applyVisible = options.applyVisible;
    const acknowledge = typeof options.acknowledge === "function"
      ? options.acknowledge
      : () => undefined;
    const fallbackError = options.fallbackError || "lock-failed";
    let applied = false;

    try {
      applied = applyVisible?.() === true;
    } catch (error) {
      await Promise.resolve()
        .then(() => acknowledge(false, errorMessage(error, fallbackError)))
        .catch(() => {});
      return { applied: false, acknowledged: false };
    }

    if (!applied) {
      await Promise.resolve()
        .then(() => acknowledge(false, fallbackError))
        .catch(() => {});
      return { applied: false, acknowledged: false };
    }

    // Start ACK before waiting for side effects. A side-effect failure cannot undo visible application.
    const acknowledgement = Promise.resolve()
      .then(() => acknowledge(true, ""))
      .catch(() => {});
    const sideEffects = Promise.resolve()
      .then(() => options.runSideEffects?.())
      .catch(() => {});
    await acknowledgement;
    await sideEffects;
    return { applied: true, acknowledged: true };
  }

  function acknowledgedCount(status) {
    const explicit = Number(status?.acknowledgedCount);
    if (Number.isFinite(explicit)) return explicit;
    const targetCount = Number(status?.targetCount);
    const pendingCount = Number(status?.pendingCount);
    return Number.isFinite(targetCount) && Number.isFinite(pendingCount)
      ? Math.max(0, targetCount - pendingCount)
      : 0;
  }

  function mergeLockCommandStatus(current, incoming) {
    if (!current) return incoming;
    if (!incoming) return current;
    const currentId = String(current.commandId || "");
    const incomingId = String(incoming.commandId || "");
    if (!currentId || !incomingId || currentId !== incomingId) return incoming;
    if (acknowledgedCount(incoming) < acknowledgedCount(current)) return current;
    if (current.complete === true && incoming.complete !== true) return current;
    return incoming;
  }

  return Object.freeze({ executeLockCommand, mergeLockCommandStatus });
});
