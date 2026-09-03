const assert = require("node:assert/strict");
const { test } = require("node:test");

const {
  DEFAULT_STATE,
  canonicalizeState,
  colorWithBrightness,
  colorWithOverallBrightness,
  createDefaultPresets,
  effectiveBrightnessPercent,
  normalizeOverallBrightness
} = require("../src/web/markerdeck-visual-state");

test("normalizes missing and out-of-range overall brightness values", () => {
  assert.equal(normalizeOverallBrightness(undefined), "100");
  assert.equal(normalizeOverallBrightness(""), "100");
  assert.equal(normalizeOverallBrightness("not-a-number"), "100");
  assert.equal(normalizeOverallBrightness("50.4"), "50");
  assert.equal(normalizeOverallBrightness("-10"), "0");
  assert.equal(normalizeOverallBrightness("150"), "100");
});

test("applies overall brightness as a multiplier to every rendered color", () => {
  assert.equal(effectiveBrightnessPercent("60", "50"), 30);
  assert.equal(effectiveBrightnessPercent("60", "100"), 60);
  assert.equal(colorWithBrightness("#ffffff", "60", "50"), "rgb(77, 77, 77)");
  assert.equal(
    colorWithBrightness("#123456", "60", "100"),
    colorWithBrightness("#123456", "60")
  );
});

test("folds legacy channel brightness into RGB and is idempotent", () => {
  const canonical = canonicalizeState({
    ...DEFAULT_STATE,
    bgColor: "#135790",
    bgBrightness: "60",
    crossColor: "#2468ac",
    crossBrightness: "30",
    overallBrightness: "50.4"
  });

  assert.equal(canonical.bgColor, "#0b3456");
  assert.equal(canonical.crossColor, "#0b1f34");
  assert.equal(canonical.bgBrightness, "100");
  assert.equal(canonical.crossBrightness, "100");
  assert.equal(canonical.overallBrightness, "50");
  assert.deepEqual(canonicalizeState(canonical), canonical);
});

test("keeps overall brightness as the only post-migration color multiplier", () => {
  const canonical = canonicalizeState({
    ...DEFAULT_STATE,
    bgColor: "#00ff00",
    bgBrightness: "60",
    crossColor: "#ffffff",
    crossBrightness: "30",
    overallBrightness: "50"
  });

  assert.equal(canonical.bgColor, "#009900");
  assert.equal(canonical.crossColor, "#4d4d4d");
  assert.equal(colorWithOverallBrightness(canonical.bgColor, canonical.overallBrightness), "rgb(0, 77, 0)");
  assert.equal(colorWithOverallBrightness(canonical.crossColor, canonical.overallBrightness), "rgb(39, 39, 39)");
});

test("default 60% and 30% presets are folded while retaining their names", () => {
  const presets = createDefaultPresets();

  assert.deepEqual(presets.map(({ name }) => name), [
    "绿底蓝十字",
    "60%绿底蓝十字",
    "30%绿底蓝十字",
    "蓝底绿十字",
    "60%蓝底绿十字",
    "30%蓝底绿十字",
    "浅灰底蓝十字",
    "浅灰底绿十字"
  ]);
  assert.equal(presets[1].state.bgColor, "#009900");
  assert.equal(presets[2].state.bgColor, "#004d00");
  assert.equal(presets[4].state.bgColor, "#002682");
  assert.equal(presets[5].state.bgColor, "#001341");
  presets.forEach(({ state }) => {
    assert.equal(state.bgBrightness, "100");
    assert.equal(state.crossBrightness, "100");
  });
});
