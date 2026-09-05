(function (global) {
  "use strict";

  const app = global.MarkerDeck;

  function init() {
    app.core.initInputGuards();
    app.exporter.init();
    app.presets.init();
    app.devices.init();
    app.scenes?.init?.();
    app.settings.init();
    app.projection.init();
    app.launcher.init();
    app.canvas.render();
    app.presets.load();
    app.launcher.start();
  }

  init();
})(window);
