const assert = require("node:assert/strict");
const { test } = require("node:test");

const { executeLockCommand } = require("../src/web/markerdeck-lock-flow");

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
