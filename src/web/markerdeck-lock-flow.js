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

  return Object.freeze({ executeLockCommand });
});
