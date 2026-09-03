(function (global) {
  "use strict";

  const app = global.MarkerDeck;
  const { dom, state, readState, updateStatus } = app.core;
  let videoProgressHideTimer = null;

  function exportTimestamp() {
    const now = new Date();
    const pad = (value) => String(value).padStart(2, "0");
    return `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}-${pad(now.getHours())}${pad(now.getMinutes())}${pad(now.getSeconds())}`;
  }

  function fullscreenExportPixelSize() {
    let width = Math.max(1, Math.round(Number(global.screen?.width) || global.innerWidth));
    let height = Math.max(1, Math.round(Number(global.screen?.height) || global.innerHeight));
    const viewportIsLandscape = global.innerWidth > global.innerHeight;
    const screenIsLandscape = width > height;
    if (viewportIsLandscape !== screenIsLandscape) [width, height] = [height, width];
    const dpr = Math.max(1, global.devicePixelRatio || 1);
    return {
      width: Math.round(Math.max(width, global.innerWidth) * dpr),
      height: Math.round(Math.max(height, global.innerHeight) * dpr)
    };
  }

  function initializeExportResolution() {
    const size = fullscreenExportPixelSize();
    dom.exportWidthInput.value = size.width;
    dom.exportHeightInput.value = size.height;
  }

  function readExportSize() {
    const width = Math.round(Number(dom.exportWidthInput.value));
    const height = Math.round(Number(dom.exportHeightInput.value));
    if (!Number.isFinite(width) || !Number.isFinite(height) || width < 64 || height < 64 || width > 16384 || height > 16384) {
      throw new Error("resolution");
    }
    if (width * height > 67108864) throw new Error("resolution-too-large");
    return { width, height };
  }

  function readVideoDuration() {
    const duration = Number(dom.videoDurationInput.value);
    if (!Number.isFinite(duration) || duration < 0.1 || duration > 3600) throw new Error("video-duration");
    return Math.round(duration * 10) / 10;
  }

  function safeFilePart(value, fallback) {
    const cleaned = String(value || "").replace(/[\\/:*?"<>|\u0000-\u001f]/g, "-").replace(/\s+/g, " ").trim();
    return (cleaned || fallback).slice(0, 60);
  }

  function makePngBlob(renderState, size) {
    const exportCanvas = document.createElement("canvas");
    exportCanvas.width = size.width;
    exportCanvas.height = size.height;
    const exportContext = exportCanvas.getContext("2d", { alpha: false });
    app.canvas.drawStateToContext(exportContext, size.width, size.height, renderState);
    return new Promise((resolve, reject) => {
      exportCanvas.toBlob((result) => result ? resolve(result) : reject(new Error("png")), "image/png");
    });
  }

  function downloadBlob(blob, fileName) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = url;
    link.download = fileName;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(url), 2000);
  }

  const crcTable = (() => {
    const table = new Uint32Array(256);
    for (let index = 0; index < 256; index += 1) {
      let value = index;
      for (let bit = 0; bit < 8; bit += 1) {
        value = (value & 1) ? (0xedb88320 ^ (value >>> 1)) : (value >>> 1);
      }
      table[index] = value >>> 0;
    }
    return table;
  })();

  function crc32(bytes) {
    let crc = 0xffffffff;
    for (const byte of bytes) crc = crcTable[(crc ^ byte) & 0xff] ^ (crc >>> 8);
    return (crc ^ 0xffffffff) >>> 0;
  }

  function zipDateTime(date = new Date()) {
    const year = Math.max(1980, date.getFullYear());
    return {
      time: (date.getHours() << 11) | (date.getMinutes() << 5) | Math.floor(date.getSeconds() / 2),
      date: ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate()
    };
  }

  async function makeZipBlob(files) {
    const encoder = new TextEncoder();
    const localParts = [];
    const centralParts = [];
    let offset = 0;
    const stamp = zipDateTime();

    for (const file of files) {
      const name = encoder.encode(file.name);
      const bytes = new Uint8Array(await file.blob.arrayBuffer());
      const checksum = crc32(bytes);
      const local = new ArrayBuffer(30 + name.length);
      const localView = new DataView(local);
      localView.setUint32(0, 0x04034b50, true);
      localView.setUint16(4, 20, true);
      localView.setUint16(6, 0x0800, true);
      localView.setUint16(8, 0, true);
      localView.setUint16(10, stamp.time, true);
      localView.setUint16(12, stamp.date, true);
      localView.setUint32(14, checksum, true);
      localView.setUint32(18, bytes.length, true);
      localView.setUint32(22, bytes.length, true);
      localView.setUint16(26, name.length, true);
      new Uint8Array(local, 30).set(name);
      localParts.push(local, bytes);

      const central = new ArrayBuffer(46 + name.length);
      const centralView = new DataView(central);
      centralView.setUint32(0, 0x02014b50, true);
      centralView.setUint16(4, 20, true);
      centralView.setUint16(6, 20, true);
      centralView.setUint16(8, 0x0800, true);
      centralView.setUint16(10, 0, true);
      centralView.setUint16(12, stamp.time, true);
      centralView.setUint16(14, stamp.date, true);
      centralView.setUint32(16, checksum, true);
      centralView.setUint32(20, bytes.length, true);
      centralView.setUint32(24, bytes.length, true);
      centralView.setUint16(28, name.length, true);
      centralView.setUint32(42, offset, true);
      new Uint8Array(central, 46).set(name);
      centralParts.push(central);
      offset += local.byteLength + bytes.byteLength;
    }

    const centralSize = centralParts.reduce((total, part) => total + part.byteLength, 0);
    const end = new ArrayBuffer(22);
    const endView = new DataView(end);
    endView.setUint32(0, 0x06054b50, true);
    endView.setUint16(8, files.length, true);
    endView.setUint16(10, files.length, true);
    endView.setUint32(12, centralSize, true);
    endView.setUint32(16, offset, true);
    return new Blob([...localParts, ...centralParts, end], { type: "application/zip" });
  }

  function choosePresetExportMode() {
    dom.exportChoiceDialog.classList.remove("hidden");
    dom.exportZipBtn.focus();
  }

  function closePresetExportChoice() {
    dom.exportChoiceDialog.classList.add("hidden");
    dom.exportAllPresetsBtn.focus();
  }

  function chooseVideoExportMode() {
    dom.videoExportChoiceDialog.classList.remove("hidden");
    dom.exportVideoZipBtn.focus();
  }

  function closeVideoExportChoice() {
    dom.videoExportChoiceDialog.classList.add("hidden");
    dom.exportAllVideosBtn.focus();
  }

  function showVideoProgress(title, detail, percent) {
    if (videoProgressHideTimer) clearTimeout(videoProgressHideTimer);
    const normalized = Math.max(0, Math.min(100, Number(percent) || 0));
    dom.videoProgressWindow.classList.remove("hidden", "failed");
    dom.videoProgressTitle.textContent = title;
    dom.videoProgressDetail.textContent = detail;
    dom.videoProgressBar.value = normalized;
    dom.videoProgressPercent.textContent = `${Math.round(normalized)}%`;
  }

  function finishVideoProgress(detail, failed = false) {
    dom.videoProgressWindow.classList.toggle("failed", failed);
    dom.videoProgressTitle.textContent = failed ? "视频生成失败" : "视频生成完成";
    dom.videoProgressDetail.textContent = detail;
    if (!failed) {
      dom.videoProgressBar.value = 100;
      dom.videoProgressPercent.textContent = "100%";
    }
    videoProgressHideTimer = setTimeout(() => dom.videoProgressWindow.classList.add("hidden"), failed ? 3500 : 1800);
  }

  async function makeMp4Blob(renderState, size, duration, onProgress = () => {}) {
    if (!state.serverMode) throw new Error("video-service");
    const png = await makePngBlob(renderState, size);
    onProgress(1);
    const { id } = await app.api.startVideo(png, duration);
    while (true) {
      await new Promise((resolve) => setTimeout(resolve, 220));
      const job = await app.api.getVideoStatus(id);
      onProgress(job.progress || 0);
      if (job.status === "failed") {
        if (String(job.error || "").includes("ffmpeg-not-found")) throw new Error("ffmpeg-not-found");
        throw new Error("video-conversion");
      }
      if (job.status === "ready") break;
    }
    const result = await app.api.getVideoResult(id);
    onProgress(100);
    return result;
  }

  function videoExportMessage(error, batch = false) {
    if (error?.message === "resolution") return "请输入 64 至 16384 的有效分辨率";
    if (error?.message === "resolution-too-large") return "分辨率过大，总像素不能超过 6710 万";
    if (error?.message === "video-duration") return "视频时长需在 0.1 至 3600 秒之间";
    if (error?.message === "video-service") return "请通过本地控制服务打开页面后导出视频";
    if (error?.message === "ffmpeg-not-found") return "未找到 FFmpeg，请检查服务端运行时";
    if (error?.message === "video-busy") return "已有视频正在生成，请稍后重试";
    if (error?.name === "AbortError") return batch ? "已取消批量视频导出" : "已取消视频导出";
    return batch ? "批量视频导出失败" : "MP4 导出失败";
  }

  async function exportVideo() {
    dom.exportVideoBtn.disabled = true;
    dom.exportAllVideosBtn.disabled = true;
    dom.exportVideoBtn.textContent = "正在生成 MP4";
    try {
      const size = readExportSize();
      const duration = readVideoDuration();
      showVideoProgress("正在生成视频", `${size.width} × ${size.height} · ${duration} 秒`, 0);
      const blob = await makeMp4Blob(readState(), size, duration, (progress) => {
        showVideoProgress("正在生成视频", `${size.width} × ${size.height} · ${duration} 秒`, progress);
      });
      const durationLabel = String(duration).replace(".", "p");
      const fileName = `chroma-screen-${size.width}x${size.height}-${durationLabel}s-${exportTimestamp()}.mp4`;
      if (typeof global.showSaveFilePicker === "function") {
        const handle = await global.showSaveFilePicker({
          suggestedName: fileName,
          types: [{ description: "MP4 视频", accept: { "video/mp4": [".mp4"] } }]
        });
        const writable = await handle.createWritable();
        await writable.write(blob);
        await writable.close();
      } else {
        downloadBlob(blob, fileName);
      }
      updateStatus(`MP4 已导出 · ${duration} 秒`);
      finishVideoProgress("文件已保存");
    } catch (error) {
      const message = videoExportMessage(error);
      updateStatus(message);
      finishVideoProgress(message, true);
    } finally {
      dom.exportVideoBtn.disabled = false;
      dom.exportAllVideosBtn.disabled = false;
      dom.exportVideoBtn.textContent = "导出当前 MP4";
    }
  }

  async function exportAllVideos(mode) {
    if (!state.presets.length) {
      updateStatus("暂无可导出的预设");
      return;
    }
    dom.exportVideoBtn.disabled = true;
    dom.exportAllVideosBtn.disabled = true;
    const originalLabel = dom.exportAllVideosBtn.textContent;
    try {
      const size = readExportSize();
      const duration = readVideoDuration();
      const durationLabel = String(duration).replace(".", "p");
      const directory = mode === "files" && typeof global.showDirectoryPicker === "function"
        ? await global.showDirectoryPicker({ mode: "readwrite" })
        : null;
      const zipFiles = [];
      showVideoProgress("正在批量生成视频", `共 ${state.presets.length} 个预设`, 0);
      for (let index = 0; index < state.presets.length; index += 1) {
        const preset = state.presets[index];
        dom.exportAllVideosBtn.textContent = `正在生成 ${index + 1}/${state.presets.length}`;
        const blob = await makeMp4Blob(preset.state || {}, size, duration, (progress) => {
          const overall = ((index + progress / 100) / state.presets.length) * 100;
          showVideoProgress("正在批量生成视频", `${index + 1}/${state.presets.length} · ${preset.name}`, overall);
        });
        const order = String(index + 1).padStart(2, "0");
        const name = safeFilePart(preset.name, `preset-${order}`);
        const fileName = `${order}-${name}-${size.width}x${size.height}-${durationLabel}s.mp4`;
        if (mode === "zip") {
          zipFiles.push({ name: fileName, blob });
        } else if (directory) {
          const handle = await directory.getFileHandle(fileName, { create: true });
          const writable = await handle.createWritable();
          await writable.write(blob);
          await writable.close();
        } else {
          downloadBlob(blob, fileName);
          await new Promise((resolve) => setTimeout(resolve, 160));
        }
      }
      if (mode === "zip") {
        dom.exportAllVideosBtn.textContent = "正在生成 ZIP";
        const zip = await makeZipBlob(zipFiles);
        downloadBlob(zip, `chroma-videos-${size.width}x${size.height}-${durationLabel}s.zip`);
      }
      updateStatus(`已导出 ${state.presets.length} 个 MP4 · 每个 ${duration} 秒`);
      finishVideoProgress(`已完成 ${state.presets.length} 个视频`);
    } catch (error) {
      const message = videoExportMessage(error, true);
      updateStatus(message);
      finishVideoProgress(message, true);
    } finally {
      dom.exportVideoBtn.disabled = false;
      dom.exportAllVideosBtn.disabled = false;
      dom.exportAllVideosBtn.textContent = originalLabel;
    }
  }

  async function exportPng() {
    dom.exportPngBtn.disabled = true;
    dom.exportPngBtn.textContent = "正在导出";
    try {
      const size = readExportSize();
      const blob = await makePngBlob(readState(), size);
      const fileName = `chroma-screen-${size.width}x${size.height}-${exportTimestamp()}.png`;
      const exportedPixels = `${size.width} x ${size.height}`;

      if (typeof global.showSaveFilePicker === "function") {
        const handle = await global.showSaveFilePicker({
          suggestedName: fileName,
          types: [{ description: "PNG 图片", accept: { "image/png": [".png"] } }]
        });
        const writable = await handle.createWritable();
        await writable.write(blob);
        await writable.close();
        updateStatus(`PNG 已保存 ${exportedPixels}`);
        return;
      }

      const file = new File([blob], fileName, { type: "image/png" });
      if (navigator.share && navigator.canShare?.({ files: [file] })) {
        await navigator.share({ files: [file], title: fileName });
        updateStatus(`PNG 已导出 ${exportedPixels}`);
        return;
      }

      downloadBlob(blob, fileName);
      updateStatus(`PNG 已下载 ${exportedPixels}`);
    } catch (error) {
      const message = error?.message === "resolution"
        ? "请输入 64 至 16384 的有效分辨率"
        : error?.message === "resolution-too-large"
          ? "分辨率过大，总像素不能超过 6710 万"
          : error?.name === "AbortError" ? "已取消导出" : "PNG 导出失败";
      updateStatus(message);
    } finally {
      dom.exportPngBtn.disabled = false;
      dom.exportPngBtn.textContent = "导出当前 PNG";
    }
  }

  async function exportAllPresets(mode) {
    if (!state.presets.length) {
      updateStatus("暂无可导出的预设");
      return;
    }
    dom.exportAllPresetsBtn.disabled = true;
    dom.exportPngBtn.disabled = true;
    const originalLabel = dom.exportAllPresetsBtn.textContent;
    try {
      const size = readExportSize();
      const directory = mode === "files" && typeof global.showDirectoryPicker === "function"
        ? await global.showDirectoryPicker({ mode: "readwrite" })
        : null;
      const zipFiles = [];
      for (let index = 0; index < state.presets.length; index += 1) {
        const preset = state.presets[index];
        dom.exportAllPresetsBtn.textContent = `正在导出 ${index + 1}/${state.presets.length}`;
        const blob = await makePngBlob(preset.state || {}, size);
        const order = String(index + 1).padStart(2, "0");
        const name = safeFilePart(preset.name, `preset-${order}`);
        const fileName = `${order}-${name}-${size.width}x${size.height}.png`;
        if (mode === "zip") {
          zipFiles.push({ name: fileName, blob });
        } else if (directory) {
          const handle = await directory.getFileHandle(fileName, { create: true });
          const writable = await handle.createWritable();
          await writable.write(blob);
          await writable.close();
        } else {
          downloadBlob(blob, fileName);
          await new Promise((resolve) => setTimeout(resolve, 120));
        }
        await new Promise((resolve) => requestAnimationFrame(resolve));
      }
      if (mode === "zip") {
        dom.exportAllPresetsBtn.textContent = "正在生成 ZIP";
        const zip = await makeZipBlob(zipFiles);
        downloadBlob(zip, `markerdeck-presets-${size.width}x${size.height}.zip`);
      }
      updateStatus(`已导出 ${state.presets.length} 个预设，${size.width} x ${size.height}`);
    } catch (error) {
      const message = error?.message === "resolution"
        ? "请输入 64 至 16384 的有效分辨率"
        : error?.message === "resolution-too-large"
          ? "分辨率过大，总像素不能超过 6710 万"
          : error?.name === "AbortError" ? "已取消批量导出" : "批量导出失败";
      updateStatus(message);
    } finally {
      dom.exportAllPresetsBtn.disabled = false;
      dom.exportPngBtn.disabled = false;
      dom.exportAllPresetsBtn.textContent = originalLabel;
    }
  }

  function init() {
    initializeExportResolution();
    dom.exportPngBtn.addEventListener("click", exportPng);
    dom.exportAllPresetsBtn.addEventListener("click", choosePresetExportMode);
    dom.exportZipBtn.addEventListener("click", () => {
      closePresetExportChoice();
      exportAllPresets("zip");
    });
    dom.exportFilesBtn.addEventListener("click", () => {
      closePresetExportChoice();
      exportAllPresets("files");
    });
    dom.cancelExportChoiceBtn.addEventListener("click", closePresetExportChoice);
    dom.exportChoiceDialog.addEventListener("click", (event) => {
      if (event.target === dom.exportChoiceDialog) closePresetExportChoice();
    });
    dom.exportVideoBtn.addEventListener("click", exportVideo);
    dom.exportAllVideosBtn.addEventListener("click", chooseVideoExportMode);
    dom.exportVideoZipBtn.addEventListener("click", () => {
      closeVideoExportChoice();
      exportAllVideos("zip");
    });
    dom.exportVideoFilesBtn.addEventListener("click", () => {
      closeVideoExportChoice();
      exportAllVideos("files");
    });
    dom.cancelVideoExportChoiceBtn.addEventListener("click", closeVideoExportChoice);
    dom.videoExportChoiceDialog.addEventListener("click", (event) => {
      if (event.target === dom.videoExportChoiceDialog) closeVideoExportChoice();
    });
  }

  app.exporter = { init, makePngBlob, makeZipBlob, readExportSize, readVideoDuration };
})(window);
