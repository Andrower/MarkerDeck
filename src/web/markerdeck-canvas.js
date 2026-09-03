(function (global) {
  "use strict";

  const app = global.MarkerDeck;
  const { dom, readState, colorWithBrightness } = app.core;
  const ctx = dom.canvas.getContext("2d", { alpha: false });
  let renderListeners = [];

  function seedToNumber(seed) {
    let value = 2166136261;
    String(seed || "chroma-random-points").split("").forEach((char) => {
      value ^= char.charCodeAt(0);
      value = Math.imul(value, 16777619);
    });
    return value >>> 0;
  }

  function seededRandom(seed) {
    let value = seedToNumber(seed);
    return () => {
      value = Math.imul(1664525, value) + 1013904223;
      return (value >>> 0) / 4294967296;
    };
  }

  function clampNumber(value, min, max) {
    return Math.min(max, Math.max(min, Number(value) || min));
  }

  function isFarEnough(candidate, points, minDistance) {
    return points.every(([x, y]) => {
      const dx = candidate[0] - x;
      const dy = candidate[1] - y;
      return Math.hypot(dx, dy) >= minDistance;
    });
  }

  function drawStateToContext(targetCtx, width, height, state) {
    const bg = colorWithBrightness(state.bgColor, state.bgBrightness);
    const cross = colorWithBrightness(state.crossColor, state.crossBrightness);
    const base = Math.min(width, height);
    const size = base * (Number(state.crossSize) / 100);
    const thickness = Math.max(1, base * (Number(state.crossThickness) / 100));
    const inset = base * (Number(state.edgeRatio) / 100);
    const centerY = height * (Number(state.centerY) / 100);

    targetCtx.fillStyle = bg;
    targetCtx.fillRect(0, 0, width, height);
    if (String(state.hideCross || "0") === "1") return;
    targetCtx.fillStyle = cross;
    const basePoints = [
      [inset, inset],
      [width - inset, inset],
      [width / 2, centerY],
      [inset, height - inset],
      [width - inset, height - inset]
    ];
    const points = [...basePoints];
    if (String(state.randomPoints || "0") === "1") {
      const random = seededRandom(`${state.randomSeed || "default"}:${width}x${height}`);
      const count = clampNumber(state.randomPointCount, 4, 80);
      const margin = Math.max(size, base * 0.04);
      const availableArea = Math.max(1, (width - margin * 2) * (height - margin * 2));
      const spacingByArea = Math.sqrt(availableArea / Math.max(1, count + basePoints.length)) * 0.78;
      const minDistance = Math.max(size * 1.9, Math.min(base * 0.15, spacingByArea));
      let placed = 0;
      let attempts = 0;
      const maxAttempts = count * 160;
      while (placed < count && attempts < maxAttempts) {
        attempts += 1;
        const candidate = [
          margin + random() * Math.max(1, width - margin * 2),
          margin + random() * Math.max(1, height - margin * 2),
          0.72
        ];
        if (!isFarEnough(candidate, points, minDistance)) continue;
        points.push(candidate);
        placed += 1;
      }
    }
    points.forEach(([x, y, scale = 1]) => {
      const pointSize = size * scale;
      const pointThickness = Math.max(1, thickness * scale);
      targetCtx.fillRect(Math.round(x - pointSize / 2), Math.round(y - pointThickness / 2), Math.round(pointSize), Math.round(pointThickness));
      targetCtx.fillRect(Math.round(x - pointThickness / 2), Math.round(y - pointSize / 2), Math.round(pointThickness), Math.round(pointSize));
    });
  }

  function render() {
    const dpr = Math.max(1, global.devicePixelRatio || 1);
    const w = Math.max(1, Math.round(global.innerWidth));
    const h = Math.max(1, Math.round(global.innerHeight));
    const pixelW = Math.round(w * dpr);
    const pixelH = Math.round(h * dpr);

    if (dom.canvas.width !== pixelW || dom.canvas.height !== pixelH) {
      dom.canvas.width = pixelW;
      dom.canvas.height = pixelH;
      dom.canvas.style.width = `${w}px`;
      dom.canvas.style.height = `${h}px`;
    }

    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    const state = readState();
    drawStateToContext(ctx, w, h, state);
    dom.outputs.bgBrightness.textContent = `${state.bgBrightness}%`;
    dom.outputs.crossBrightness.textContent = `${state.crossBrightness}%`;
    dom.outputs.crossSize.textContent = `${state.crossSize}%`;
    dom.outputs.crossThickness.textContent = `${state.crossThickness}%`;
    dom.outputs.edgeRatio.textContent = `${state.edgeRatio}%`;
    dom.outputs.centerY.textContent = `${state.centerY}%`;
    dom.outputs.randomPointCount.textContent = state.randomPointCount;
    dom.readout.textContent = `${w} x ${h} CSS px | ${pixelW} x ${pixelH} device px | DPR ${dpr}`;
    renderListeners.forEach((listener) => listener(state));
  }

  function renderDeviceThumbnail(targetCanvas, state, sourceWidth, sourceHeight) {
    const rect = targetCanvas.getBoundingClientRect();
    const dpr = Math.max(1, global.devicePixelRatio || 1);
    const w = Math.max(1, Math.round(rect.width));
    const h = Math.max(1, Math.round(rect.height));
    const logicalWidth = Math.max(1, Math.round(Number(sourceWidth) || w));
    const logicalHeight = Math.max(1, Math.round(Number(sourceHeight) || h));
    targetCanvas.width = Math.round(w * dpr);
    targetCanvas.height = Math.round(h * dpr);
    const targetContext = targetCanvas.getContext("2d", { alpha: false });
    targetContext.setTransform(dpr, 0, 0, dpr, 0, 0);
    targetContext.fillStyle = "#080b09";
    targetContext.fillRect(0, 0, w, h);
    const scale = Math.min(w / logicalWidth, h / logicalHeight);
    const offsetX = (w - logicalWidth * scale) / 2;
    const offsetY = (h - logicalHeight * scale) / 2;
    targetContext.setTransform(dpr * scale, 0, 0, dpr * scale, dpr * offsetX, dpr * offsetY);
    drawStateToContext(targetContext, logicalWidth, logicalHeight, state);
  }

  function addRenderListener(listener) {
    renderListeners.push(listener);
  }

  global.addEventListener("resize", render);
  global.addEventListener("orientationchange", () => setTimeout(render, 250));

  app.canvas = { ctx, drawStateToContext, render, renderDeviceThumbnail, addRenderListener };
})(window);
