(function (global) {
  "use strict";

  const app = global.MarkerDeck;
  const { dom, state, makeRandomSeed, updateStatus } = app.core;

  function updateCrossToggleButton() {
    const active = state.role === "control"
      ? String(state.selectedDeviceState?.hideCross || "0") === "1"
      : String(document.body.dataset.hideCross || "0") === "1";
    dom.crossToggleBtn.textContent = active ? "显示十字" : "隐藏十字";
  }

  function updateRandomPointsButton() {
    const active = state.role === "control"
      ? String(state.selectedDeviceState?.randomPoints || "0") === "1"
      : String(document.body.dataset.randomPoints || "0") === "1";
    dom.randomPointsBtn.textContent = active ? "关闭随机点" : "开启随机点";
  }

  function publishVisualChange() {
    app.presets.markCustom();
    app.canvas.render();
    app.projection.publishState();
  }

  function restoreDefaultPoints() {
    dom.controls.crossSize.value = 6;
    dom.controls.crossThickness.value = 1.4;
    dom.controls.edgeRatio.value = 10;
    dom.controls.centerY.value = 50;
    dom.controls.randomPointCount.value = 12;
    document.body.dataset.randomPoints = "0";
    document.body.dataset.randomSeed = "";
    if (state.role === "control") {
      state.selectedDeviceState = {
        ...(state.selectedDeviceState || {}),
        randomPoints: "0",
        randomSeed: "",
        randomPointCount: "12"
      };
    }
    publishVisualChange();
  }

  function blackout() {
    dom.controls.bgColor.value = "#000000";
    dom.controls.crossColor.value = "#000000";
    publishVisualChange();
  }

  function toggleCross() {
    const current = state.role === "control"
      ? String(state.selectedDeviceState?.hideCross || "0") === "1"
      : String(document.body.dataset.hideCross || "0") === "1";
    const nextValue = current ? "0" : "1";
    document.body.dataset.hideCross = nextValue;
    if (state.role === "control") {
      state.selectedDeviceState = { ...(state.selectedDeviceState || {}), hideCross: nextValue };
    }
    publishVisualChange();
  }

  function toggleRandomPoints() {
    const current = state.role === "control"
      ? String(state.selectedDeviceState?.randomPoints || "0") === "1"
      : String(document.body.dataset.randomPoints || "0") === "1";
    const nextValue = current ? "0" : "1";
    let nextSeed = state.role === "control" ? state.selectedDeviceState?.randomSeed : document.body.dataset.randomSeed;
    if (nextValue === "1" && !nextSeed) nextSeed = makeRandomSeed();
    document.body.dataset.randomPoints = nextValue;
    document.body.dataset.randomSeed = nextSeed || "";
    if (state.role === "control") {
      state.selectedDeviceState = {
        ...(state.selectedDeviceState || {}),
        randomPoints: nextValue,
        randomSeed: nextSeed || "",
        randomPointCount: dom.controls.randomPointCount.value
      };
    }
    publishVisualChange();
  }

  function init() {
    Object.values(dom.controls).forEach((control) => {
      control.addEventListener("input", publishVisualChange);
    });
    dom.defaultBtn.addEventListener("click", restoreDefaultPoints);
    dom.blackoutBtn.addEventListener("click", blackout);
    dom.crossToggleBtn.addEventListener("click", toggleCross);
    dom.randomPointsBtn.addEventListener("click", toggleRandomPoints);
    app.canvas.addRenderListener(() => {
      updateCrossToggleButton();
      updateRandomPointsButton();
    });
  }

  app.settings = {
    init,
    updateCrossToggleButton,
    updateRandomPointsButton
  };
})(window);
