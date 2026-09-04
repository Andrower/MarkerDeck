const MARKERDECK_MDNS_SERVICE_TYPE = "_markerdeck._tcp.local";
const MARKERDECK_MDNS_BONJOUR_TYPE = "markerdeck";
const MARKERDECK_MDNS_PROTOCOL = "tcp";
const MARKERDECK_MDNS_SERVICE_NAME = "markerdeck";

function safeMdnsName(value) {
  return String(value || "")
    .replace(/[\u0000-\u001f\u007f-\u009f]/g, "")
    .trim()
    .slice(0, 40) || "MarkerDeck";
}

function loadBonjourFactory() {
  try {
    const module = require("bonjour-service");
    const Bonjour = module.Bonjour || module.default || module;
    if (typeof Bonjour !== "function") return null;
    return (onError) => new Bonjour({}, onError);
  } catch (_) {
    return null;
  }
}

function createBonjourInstance(options = {}) {
  const factory = options.bonjourFactory || loadBonjourFactory();
  if (typeof factory !== "function") return null;
  try {
    return factory(options.onError);
  } catch (_) {
    return null;
  }
}

function discoveryTxt({ name, instanceId }) {
  return {
    service: MARKERDECK_MDNS_SERVICE_NAME,
    protocolVersion: "1",
    instanceId: String(instanceId || ""),
    name: safeMdnsName(name)
  };
}

function txtValue(value) {
  if (Buffer.isBuffer(value)) return value.toString("utf8");
  if (Array.isArray(value)) return Buffer.from(value).toString("utf8");
  return value == null ? undefined : String(value);
}

function attachErrorListener(target, onError) {
  if (!target || typeof target.on !== "function") return;
  try {
    target.on("error", (error) => onError(error instanceof Error ? error : new Error(String(error))));
  } catch (_) {
    // A test double or an older bonjour implementation may not support event listeners.
  }
}

function createMarkerDeckMdnsPublisher(options = {}) {
  let bonjour = null;
  let service = null;
  let available = false;
  let stopped = false;
  const errorListeners = new Set();
  const notifyError = (error) => {
    available = false;
    errorListeners.forEach((listener) => listener(error));
  };

  const notifyUp = () => {
    if (!stopped) available = true;
  };

  function start() {
    if (service && !stopped) return available;
    if (available) return true;
    if (options.disabled) return false;
    stopped = false;
    bonjour = createBonjourInstance({ ...options, onError: notifyError });
    if (!bonjour || typeof bonjour.publish !== "function") return false;
    try {
      service = bonjour.publish({
        name: safeMdnsName(options.name),
        type: MARKERDECK_MDNS_BONJOUR_TYPE,
        protocol: MARKERDECK_MDNS_PROTOCOL,
        port: Number(options.port),
        txt: discoveryTxt(options)
      });
      attachErrorListener(service, notifyError);
      attachErrorListener(bonjour, notifyError);
      if (typeof service?.on === "function") {
        try { service.on("up", notifyUp); } catch (_) {}
      }
      // bonjour-service emits `up` once the announcement is actually registered.
      // Test doubles and compatible implementations without that event are still
      // considered usable after publish returns.
      available = typeof service?.on === "function" ? false : !stopped;
      return !stopped;
    } catch (error) {
      notifyError(error instanceof Error ? error : new Error(String(error)));
      stop();
      return false;
    }
  }

  function stop() {
    stopped = true;
    available = false;
    try { service?.stop?.(); } catch (_) {}
    try { bonjour?.destroy?.(); } catch (_) {}
    service = null;
    bonjour = null;
  }

  return {
    start,
    stop,
    isAvailable: () => available,
    onError(listener) {
      if (typeof listener === "function") errorListeners.add(listener);
      return () => errorListeners.delete(listener);
    },
    serviceType: MARKERDECK_MDNS_SERVICE_TYPE,
    txt: discoveryTxt(options)
  };
}

function serviceAddresses(service) {
  const addresses = Array.isArray(service?.addresses)
    ? service.addresses
    : [service?.address || service?.host];
  return [...new Set(addresses
    .map((address) => String(address || "").replace(/^::ffff:/i, "").trim())
    .filter(Boolean))];
}

function serviceTxt(service) {
  const txt = service?.txt && typeof service.txt === "object" ? service.txt : {};
  return {
    service: txtValue(txt.service),
    protocolVersion: txtValue(txt.protocolVersion),
    instanceId: txtValue(txt.instanceId),
    name: txtValue(txt.name) || txtValue(service?.name)
  };
}

function normalizedServiceCandidates(service) {
  const txt = serviceTxt(service);
  const port = Number(service?.port);
  const rawType = String(service?.serviceType || "").trim().replace(/\.+$/, "");
  const protocol = String(
    service?.protocol || rawType.match(/^_[^.]+\._([^\.]+)/)?.[1] || ""
  ).replace(/^_/, "");
  const serviceType = rawType
    ? (rawType.endsWith(".local") ? rawType : `${rawType}.local`)
    : (
    service?.type && service?.protocol
      ? `_${String(service.type).replace(/^_/, "")}._${protocol}.local`
      : ""
  );
  return serviceAddresses(service).map((address) => ({
    serviceType,
    protocol,
    service: txt.service,
    protocolVersion: txt.protocolVersion,
    instanceId: txt.instanceId,
    name: txt.name,
    port,
    address
  }));
}

function createMarkerDeckMdnsBrowser(options = {}) {
  let activeScan = null;
  const configuredTimeoutMs = Number(options.scanTimeoutMs);
  const scanTimeoutMs = Number.isFinite(configuredTimeoutMs) && configuredTimeoutMs >= 0
    ? configuredTimeoutMs
    : 1800;

  async function scanOnce(timeoutMs = scanTimeoutMs, scanOptions = {}) {
    if (options.disabled) return [];
    const candidates = new Map();
    let browser = null;
    const onCandidate = typeof scanOptions.onCandidate === "function"
      ? scanOptions.onCandidate
      : null;
    const signal = scanOptions.signal;

    return new Promise((resolve) => {
      let finished = false;
      let bonjour = null;
      const finish = () => {
        if (finished) return;
        finished = true;
        clearTimeout(timer);
        const activeBrowser = browser;
        const activeBonjour = bonjour;
        browser = null;
        bonjour = null;
        try { activeBrowser?.stop?.(); } catch (_) {}
        try { activeBonjour?.destroy?.(); } catch (_) {}
        signal?.removeEventListener("abort", finish);
        resolve([...candidates.values()]);
      };
      const requestedTimeoutMs = Number(timeoutMs);
      const effectiveTimeoutMs = Number.isFinite(requestedTimeoutMs) && requestedTimeoutMs >= 0
        ? requestedTimeoutMs
        : scanTimeoutMs;
      const timer = setTimeout(finish, Math.max(1, effectiveTimeoutMs));
      timer.unref?.();
      signal?.addEventListener("abort", finish, { once: true });
      if (signal?.aborted) {
        finish();
        return;
      }

      try {
        bonjour = createBonjourInstance({ ...options, onError: finish });
        if (!bonjour || typeof bonjour.find !== "function") {
          finish();
          return;
        }
        browser = bonjour.find(
          { type: MARKERDECK_MDNS_BONJOUR_TYPE, protocol: MARKERDECK_MDNS_PROTOCOL },
          (service) => {
            if (finished) return;
            normalizedServiceCandidates(service).forEach((candidate) => {
              if (finished) return;
              const key = `${candidate.instanceId}|${candidate.address}|${candidate.port}`;
              if (candidates.has(key)) return;
              candidates.set(key, candidate);
              try { onCandidate?.(candidate); } catch (_) {}
            });
          }
        );
        attachErrorListener(browser, finish);
        attachErrorListener(bonjour, finish);
      } catch (_) {
        finish();
      }
    });
  }

  return {
    scan(timeoutMs, scanOptions) {
      if (!activeScan) {
        activeScan = scanOnce(timeoutMs, scanOptions).finally(() => { activeScan = null; });
      }
      return activeScan;
    },
    serviceType: MARKERDECK_MDNS_SERVICE_TYPE
  };
}

module.exports = {
  MARKERDECK_MDNS_SERVICE_TYPE,
  MARKERDECK_MDNS_BONJOUR_TYPE,
  MARKERDECK_MDNS_PROTOCOL,
  createMarkerDeckMdnsBrowser,
  createMarkerDeckMdnsPublisher,
  discoveryTxt,
  normalizedServiceCandidates,
  serviceTxt
};
