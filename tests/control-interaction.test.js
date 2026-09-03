const assert = require("node:assert/strict");
const { test } = require("node:test");

const {
  createControlInteractionController,
  createControlInteractionReasonTracker
} = require("../src/web/markerdeck-control-interaction");

test("assigns stable unique focus reasons with a monotonic per-element counter", () => {
  const tracker = createControlInteractionReasonTracker();
  const firstInput = {};
  const secondInput = {};

  const firstReason = tracker.focusReason(firstInput);
  const secondReason = tracker.focusReason(secondInput);

  assert.notEqual(firstReason, secondReason);
  assert.equal(firstReason, tracker.focusReason(firstInput));
  assert.equal(secondReason, tracker.focusReason(secondInput));
  assert.equal(Number(firstReason.split(":")[1]) + 1, Number(secondReason.split(":")[1]));
});

test("matches keyup to keydown by code, ignores repeats, and clears on reset", () => {
  const tracker = createControlInteractionReasonTracker();
  const keydown = { code: "Space", key: " " };
  const repeatedKeydown = { code: "Space", key: " " };
  const keyup = { code: "Space", key: " " };

  const started = tracker.beginKeyboard(keydown);
  const repeated = tracker.beginKeyboard(repeatedKeydown);

  assert.equal(started.started, true);
  assert.equal(repeated.started, false);
  assert.equal(repeated.reason, started.reason);
  assert.equal(tracker.activeKeyboardCount(), 1);
  assert.equal(tracker.endKeyboard(keyup), started.reason);
  assert.equal(tracker.endKeyboard(keyup), null);

  tracker.beginKeyboard({ code: "Enter", key: "Enter" });
  assert.equal(tracker.activeKeyboardCount(), 1);
  assert.deepEqual(tracker.resetKeyboard(), ["keyboard:2:Enter"]);
  assert.equal(tracker.activeKeyboardCount(), 0);
  assert.equal(tracker.beginKeyboard({ code: "Enter", key: "Enter" }).started, true);
});

test("keeps the native timeout paused until every overlapping control interaction ends", () => {
  const events = [];
  const controller = createControlInteractionController({
    onStart: () => events.push("start"),
    onEnd: () => events.push("end")
  });

  controller.begin("pointer:7");
  controller.begin("focus:presetNameInput");
  controller.end("pointer:7");

  assert.equal(controller.isActive(), true);
  assert.deepEqual(events, ["start"]);

  controller.end("focus:presetNameInput");

  assert.equal(controller.isActive(), false);
  assert.deepEqual(events, ["start", "end"]);
});

test("does not duplicate native timer transitions for repeated events", () => {
  const events = [];
  const controller = createControlInteractionController({
    onStart: () => events.push("start"),
    onEnd: () => events.push("end")
  });

  assert.equal(controller.begin("pointer:1"), true);
  assert.equal(controller.begin("pointer:1"), false);
  assert.equal(controller.end("pointer:missing"), false);
  assert.equal(controller.end("pointer:1"), true);
  assert.equal(controller.end("pointer:1"), false);

  assert.deepEqual(events, ["start", "end"]);
});

test("reset ends an interrupted interaction so a later timeout can be scheduled", () => {
  const events = [];
  const controller = createControlInteractionController({
    onStart: () => events.push("start"),
    onEnd: () => events.push("end")
  });

  controller.begin("focus:textInput");
  assert.equal(controller.reset(), true);
  assert.equal(controller.reset(), false);
  assert.equal(controller.activeReasonCount(), 0);
  assert.deepEqual(events, ["start", "end"]);
});
