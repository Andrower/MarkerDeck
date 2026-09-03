(function (root, factory) {
  "use strict";

  if (typeof module === "object" && module.exports) {
    module.exports = factory();
    return;
  }
  const app = root.MarkerDeck = root.MarkerDeck || {};
  app.controlInteraction = factory();
})(typeof window !== "undefined" ? window : globalThis, function () {
  "use strict";

  function createControlInteractionReasonTracker() {
    const focusReasons = new WeakMap();
    const activeKeyboardReasons = new Map();
    let nextFocusInteractionId = 0;
    let nextKeyboardInteractionId = 0;

    function focusReason(target) {
      let reason = focusReasons.get(target);
      if (!reason) {
        reason = `focus:${++nextFocusInteractionId}`;
        focusReasons.set(target, reason);
      }
      return reason;
    }

    function keyboardKey(event) {
      return String(event?.code || event?.key || "");
    }

    function beginKeyboard(event) {
      const key = keyboardKey(event);
      if (!key) return null;
      const existingReason = activeKeyboardReasons.get(key);
      if (existingReason) return { reason: existingReason, started: false };
      const reason = `keyboard:${++nextKeyboardInteractionId}:${key}`;
      activeKeyboardReasons.set(key, reason);
      return { reason, started: true };
    }

    function endKeyboard(event) {
      const key = keyboardKey(event);
      if (!key) return null;
      const reason = activeKeyboardReasons.get(key);
      if (!reason) return null;
      activeKeyboardReasons.delete(key);
      return reason;
    }

    function resetKeyboard() {
      const reasons = [...activeKeyboardReasons.values()];
      activeKeyboardReasons.clear();
      return reasons;
    }

    return {
      focusReason,
      beginKeyboard,
      endKeyboard,
      resetKeyboard,
      reset: resetKeyboard,
      activeKeyboardCount: () => activeKeyboardReasons.size
    };
  }

  function createControlInteractionController({ onStart = () => {}, onEnd = () => {} } = {}) {
    const activeReasons = new Set();

    function begin(reason = "interaction") {
      const key = String(reason);
      if (activeReasons.has(key)) return false;
      const wasActive = activeReasons.size > 0;
      activeReasons.add(key);
      if (!wasActive) onStart();
      return true;
    }

    function end(reason = "interaction") {
      const key = String(reason);
      if (!activeReasons.delete(key)) return false;
      if (activeReasons.size === 0) onEnd();
      return true;
    }

    function reset() {
      if (activeReasons.size === 0) return false;
      activeReasons.clear();
      onEnd();
      return true;
    }

    return {
      begin,
      end,
      reset,
      isActive: () => activeReasons.size > 0,
      activeReasonCount: () => activeReasons.size
    };
  }

  return { createControlInteractionController, createControlInteractionReasonTracker };
});
