(function (global) {
  "use strict";

  const app = global.MarkerDeck;
  const { dom, state, updateStatus } = app.core;

  async function loadServerInfo() {
    if (!state.serverMode) {
      dom.lanAddress.textContent = "当前是 file:// 文件模式：只能本地投放。请打开启动页地址：http://172.20.10.2:8765/markerdeck-launch.html";
      dom.qrImage.removeAttribute("src");
      dom.qrBox.classList.add("unavailable");
      dom.controlUrlText.textContent = "请先通过控制服务地址打开页面，当前 file:// 模式不支持二维码。";
      dom.controlQrImage.removeAttribute("src");
      return;
    }
    try {
      const info = await app.api.getInfo();
      dom.lanAddress.textContent = info.url;
      dom.qrImage.src = `/qr.svg?text=${encodeURIComponent(info.url)}`;
      dom.qrBox.classList.remove("unavailable");
      dom.controlUrlText.textContent = info.url;
      dom.controlQrImage.src = `/qr.svg?text=${encodeURIComponent(info.url)}`;
    } catch (_) {
      dom.lanAddress.textContent = `控制服务未响应，请确认服务已启动。当前地址：${global.location.href}`;
      dom.qrImage.removeAttribute("src");
      dom.qrBox.classList.add("unavailable");
      dom.controlUrlText.textContent = `控制服务未响应：${global.location.href}`;
      dom.controlQrImage.removeAttribute("src");
    }
  }

  async function stopServer() {
    if (!state.serverMode) {
      updateStatus("文件模式无法停止服务");
      return;
    }
    dom.stopServerBtn.disabled = true;
    dom.stopServerBtn.textContent = "正在停止";
    try {
      await app.api.shutdown();
      updateStatus("服务已停止");
      dom.stopServerBtn.textContent = "服务已停止";
    } catch (_) {
      updateStatus("停止失败");
      dom.stopServerBtn.disabled = false;
      dom.stopServerBtn.textContent = "停止服务";
    }
  }

  function startRequestedRole() {
    const mode = new URLSearchParams(global.location.search).get("mode");
    if (mode === "control") {
      app.projection.startRole("control");
      return;
    }
    if (mode === "local") {
      app.projection.startRole("local");
      return;
    }
    if (mode === "display" || state.serverMode) app.projection.startRole("display");
  }

  function start() {
    return loadServerInfo().then(startRequestedRole);
  }

  function init() {
    dom.copyAddressBtn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(dom.lanAddress.textContent);
      } catch (_) {}
    });
    dom.copyControlUrlBtn.addEventListener("click", async () => {
      try {
        await navigator.clipboard.writeText(dom.controlUrlText.textContent);
      } catch (_) {}
    });
    dom.openControlUrlBtn.addEventListener("click", () => {
      if (state.serverMode && dom.controlUrlText.textContent.startsWith("http")) {
        global.location.href = dom.controlUrlText.textContent;
      }
    });
    dom.localModeBtn.addEventListener("click", () => app.projection.startRole("local"));
    dom.displayModeBtn.addEventListener("click", () => app.projection.startRole("display"));
    dom.controlModeBtn.addEventListener("click", () => app.projection.startRole("control"));
    dom.stopServerBtn.addEventListener("click", stopServer);
  }

  app.launcher = { init, start, loadServerInfo };
})(window);
