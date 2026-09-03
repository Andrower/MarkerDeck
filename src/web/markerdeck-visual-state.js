(function (root, factory) {
  "use strict";

  if (typeof module === "object" && module.exports) {
    module.exports = factory();
    return;
  }
  const app = root.MarkerDeck = root.MarkerDeck || {};
  app.visualState = factory();
})(typeof window !== "undefined" ? window : globalThis, function () {
  "use strict";

  const DEFAULT_OVERALL_BRIGHTNESS = 100;
  const DEFAULT_LEGACY_BRIGHTNESS = 100;
  const DEFAULT_STATE = Object.freeze({
    bgColor: "#00ff00",
    bgBrightness: "100",
    overallBrightness: "100",
    crossColor: "#0040d8",
    crossBrightness: "100",
    crossSize: "6",
    crossThickness: "1.4",
    edgeRatio: "10",
    centerY: "50",
    hideCross: "0",
    randomPoints: "0",
    randomPointCount: "12",
    randomSeed: "",
    forceLock: "0",
    displayLocked: "0",
    lockCommand: "none",
    lockCommandId: "0"
  });
  const DEFAULT_PRESET_DEFINITIONS = Object.freeze([
    { name: "绿底蓝十字", bgColor: "#00ff00", crossColor: "#0040d8" },
    { name: "60%绿底蓝十字", bgColor: "#009900", crossColor: "#0040d8" },
    { name: "30%绿底蓝十字", bgColor: "#004d00", crossColor: "#0040d8" },
    { name: "蓝底绿十字", bgColor: "#0040d8", crossColor: "#00ff00" },
    { name: "60%蓝底绿十字", bgColor: "#002682", crossColor: "#00ff00" },
    { name: "30%蓝底绿十字", bgColor: "#001341", crossColor: "#00ff00" },
    { name: "浅灰底蓝十字", bgColor: "#d8d8d8", crossColor: "#0040d8" },
    { name: "浅灰底绿十字", bgColor: "#d8d8d8", crossColor: "#00ff00" }
  ].map(Object.freeze));

  function brightnessPercent(value, fallback = DEFAULT_OVERALL_BRIGHTNESS) {
    const fallbackNumeric = Number(fallback);
    const fallbackValue = Number.isFinite(fallbackNumeric)
      ? Math.min(100, Math.max(0, fallbackNumeric))
      : DEFAULT_OVERALL_BRIGHTNESS;
    if (value === null || value === undefined || String(value).trim() === "") return fallbackValue;
    const numeric = Number(value);
    if (!Number.isFinite(numeric)) return fallbackValue;
    return Math.min(100, Math.max(0, numeric));
  }

  function normalizeBrightnessPercent(value, fallback = DEFAULT_OVERALL_BRIGHTNESS) {
    return Math.round(brightnessPercent(value, fallback));
  }

  function normalizeOverallBrightness(value) {
    return String(normalizeBrightnessPercent(value));
  }

  function scaleHexColor(hex, brightness = DEFAULT_LEGACY_BRIGHTNESS) {
    const source = String(hex || "").trim();
    const match = /^#([0-9a-f]{6})$/i.exec(source);
    const level = brightnessPercent(brightness, DEFAULT_LEGACY_BRIGHTNESS);
    if (!match || level === 100) return source;
    const values = [0, 2, 4].map((offset) => parseInt(match[1].slice(offset, offset + 2), 16));
    return `#${values.map((value) => Math.round(value * level / 100).toString(16).padStart(2, "0")).join("")}`;
  }

  function migrateLegacyBrightness(next) {
    const normalized = { ...(next || {}) };
    const has = (key) => Object.prototype.hasOwnProperty.call(normalized, key);
    if (has("bgColor")) normalized.bgColor = scaleHexColor(normalized.bgColor, normalized.bgBrightness);
    if (has("crossColor")) normalized.crossColor = scaleHexColor(normalized.crossColor, normalized.crossBrightness);
    normalized.bgBrightness = String(DEFAULT_LEGACY_BRIGHTNESS);
    normalized.crossBrightness = String(DEFAULT_LEGACY_BRIGHTNESS);
    normalized.overallBrightness = normalizeOverallBrightness(normalized.overallBrightness);
    return normalized;
  }

  function canonicalizeState(next) {
    return migrateLegacyBrightness(next);
  }

  function normalizeVisualState(next) {
    return canonicalizeState(next);
  }

  function createDefaultPresets() {
    return DEFAULT_PRESET_DEFINITIONS.map((definition, index) => ({
      id: `default-${index + 1}`,
      name: definition.name,
      state: canonicalizeState({
        ...DEFAULT_STATE,
        bgColor: definition.bgColor,
        crossColor: definition.crossColor
      })
    }));
  }

  function effectiveBrightnessPercent(channelBrightness, overallBrightness = DEFAULT_OVERALL_BRIGHTNESS) {
    const channel = normalizeBrightnessPercent(channelBrightness);
    const overall = normalizeBrightnessPercent(overallBrightness);
    return (channel * overall) / 100;
  }

  function hexToRgb(hex) {
    const value = String(hex || "#000000").replace("#", "");
    return {
      r: parseInt(value.slice(0, 2), 16) || 0,
      g: parseInt(value.slice(2, 4), 16) || 0,
      b: parseInt(value.slice(4, 6), 16) || 0
    };
  }

  function colorWithBrightness(hex, brightness, overallBrightness = DEFAULT_OVERALL_BRIGHTNESS) {
    const rgb = hexToRgb(hex);
    const level = effectiveBrightnessPercent(brightness, overallBrightness) / 100;
    return `rgb(${Math.round(rgb.r * level)}, ${Math.round(rgb.g * level)}, ${Math.round(rgb.b * level)})`;
  }

  function colorWithOverallBrightness(hex, overallBrightness = DEFAULT_OVERALL_BRIGHTNESS) {
    return colorWithBrightness(hex, DEFAULT_LEGACY_BRIGHTNESS, overallBrightness);
  }

  return {
    DEFAULT_OVERALL_BRIGHTNESS,
    DEFAULT_LEGACY_BRIGHTNESS,
    DEFAULT_STATE,
    DEFAULT_PRESET_DEFINITIONS,
    normalizeBrightnessPercent,
    normalizeOverallBrightness,
    scaleHexColor,
    migrateLegacyBrightness,
    normalizeVisualState,
    canonicalizeState,
    createDefaultPresets,
    effectiveBrightnessPercent,
    colorWithBrightness,
    colorWithOverallBrightness
  };
});
