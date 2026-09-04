const assert = require("node:assert/strict");
const { test } = require("node:test");

const {
  executeLockCommand,
  mergeLockCommandStatus
} = require("../src/web/markerdeck-lock-flow");

const tick = () => new Promise((resolve) => setImmediate(resolve));

test("acknowledges after the visible lock state is applied, before slow side effects finish", async () => {
  const events = [];
  let finishSideEffects;
  let completed = false;
  const sideEffects = new Promise((resolve) => { finishSideEffects = resolve; });

  const execution = executeLockCommand({
    applyVisible: () => {
      events.push("visible");
      return true;
    },
    acknowledge: async (ok) => {
      events.push(ok ? "ack" : "nack");
    },
    runSideEffects: async () => {
      events.push("side-effects");
      await sideEffects;
      events.push("side-effects-done");
    }
  }).then((result) => {
    completed = true;
    return result;
  });

  await tick();
  assert.deepEqual(events, ["visible", "ack", "side-effects"]);
  assert.equal(completed, false);

  finishSideEffects();
  assert.deepEqual(await execution, { applied: true, acknowledged: true });
  assert.deepEqual(events, ["visible", "ack", "side-effects", "side-effects-done"]);
});

test("does not acknowledge success when the visible state cannot be applied", async () => {
  const acknowledgements = [];
  let sideEffectsStarted = false;

  const result = await executeLockCommand({
    applyVisible: () => false,
    acknowledge: async (ok, error) => acknowledgements.push({ ok, error }),
    runSideEffects: async () => { sideEffectsStarted = true; }
  });

  assert.deepEqual(result, { applied: false, acknowledged: false });
  assert.deepEqual(acknowledgements, [{ ok: false, error: "lock-failed" }]);
  assert.equal(sideEffectsStarted, false);
});

test("does not let a pending POST snapshot overwrite a completed SSE acknowledgement", () => {
  const complete = {
    commandId: "1000-1",
    targetCount: 1,
    acknowledgedCount: 1,
    confirmedCount: 1,
    pendingCount: 0,
    complete: true
  };
  const stalePending = {
    commandId: "1000-1",
    targetCount: 1,
    acknowledgedCount: 0,
    confirmedCount: 0,
    pendingCount: 1,
    complete: false
  };

  assert.equal(mergeLockCommandStatus(complete, stalePending), complete);
});

test("accepts forward progress and a different lock command", () => {
  const pending = { commandId: "1000-1", acknowledgedCount: 0, complete: false };
  const progressed = { commandId: "1000-1", acknowledgedCount: 1, complete: true };
  const nextCommand = { commandId: "1001-2", acknowledgedCount: 0, complete: false };

  assert.equal(mergeLockCommandStatus(pending, progressed), progressed);
  assert.equal(mergeLockCommandStatus(progressed, nextCommand), nextCommand);
});
