const crypto = require("node:crypto");
const dgram = require("node:dgram");
const os = require("node:os");
const {
  MARKERDECK_MDNS_SERVICE_TYPE,
  createMarkerDeckMdnsBrowser
} = require("./markerdeck-mdns");

const DISCOVERY_SERVICE = "markerdeck";
const DISCOVERY_PROTOCOL_VERSION = 1;
const DISCOVERY_REQUEST_TYPE = "discover";
const DISCOVERY_RESPONSE_TYPE = "response";
const DISCOVERY_MULTICAST_ADDRESS = "239.255.77.77";
const DISCOVERY_MAX_PACKET_SIZE = 4096;
const DISCOVERY_MAX_HOSTS = 32;
const DISCOVERY_HTTP_BODY_LIMIT = 16 * 1024;
const DISCOVERY_MDNS_TIMEOUT_MS = 1400;

function isAllowedDiscoveryIpv4(value) {
  const octets = String(value || "").trim().split(".");
  if (octets.length !== 4 || octets.some((part) => !/^\d{1,3}$/.test(part))) return false;
  const numbers = octets.map(Number);
  if (numbers.some((number) => number < 0 || number > 255)) return false;
  return numbers[0] === 127 ||
    numbers[0] === 10 ||
    (numbers[0] === 172 && numbers[1] >= 16 && numbers[1] <= 31) ||
    (numbers[0] === 192 && numbers[1] === 168) ||
    (numbers[0] === 169 && numbers[1] === 254);
}

function isSafeDiscoveryText(value, maxLength) {
  return typeof value === "string" && value.length > 0 && value.length <= maxLength &&
    !/[\u0000-\u001f\u007f-\u009f]/.test(value);
}

function parseAdvertisement(payload) {
  try {
    return JSON.parse(Buffer.isBuffer(payload) ? payload.toString("utf8") : String(payload));
  } catch (_) {
    return null;
  }
}

function validateAdvertisement(advertisement, nonce, sourceAddress, selfInstanceId = "") {
  if (!advertisement || advertisement.service !== DISCOVERY_SERVICE ||
      advertisement.protocolVersion !== DISCOVERY_PROTOCOL_VERSION ||
      advertisement.type !== DISCOVERY_RESPONSE_TYPE ||
      advertisement.nonce !== nonce ||
      !isSafeDiscoveryText(advertisement.name, 40) ||
      typeof advertisement.instanceId !== "string" ||
      !/^[A-Za-z0-9_-]{8,80}$/.test(advertisement.instanceId) ||
      !Number.isInteger(advertisement.port) || advertisement.port < 1 || advertisement.port > 65535 ||
      !isAllowedDiscoveryIpv4(sourceAddress) ||
      advertisement.instanceId === selfInstanceId) {
    return null;
  }

  let advertisedUrl;
  try {
    advertisedUrl = new URL(String(advertisement.httpUrl || ""));
  } catch (_) {
    return null;
  }
  const advertisedPort = Number(advertisedUrl.port || 80);
  if (advertisedUrl.protocol !== "http:" || advertisedUrl.username || advertisedUrl.password ||
      advertisedUrl.search || advertisedUrl.hash ||
      (advertisedUrl.pathname !== "/" && advertisedUrl.pathname !== "") ||
      !isAllowedDiscoveryIpv4(advertisedUrl.hostname) || advertisedPort !== advertisement.port) {
    return null;
  }

  const serviceAddress = `http://${sourceAddress}:${advertisement.port}`;
  return {
    instanceId: advertisement.instanceId,
    name: advertisement.name,
    serviceAddress,
    controlUrl: `${serviceAddress}/markerdeck-screen.html?mode=control`,
    port: advertisement.port
  };
}

function mdnsText(value, maxLength) {
  const text = Buffer.isBuffer(value)
    ? value.toString("utf8")
    : typeof value === "string" ? value : "";
  return isSafeDiscoveryText(text, maxLength) ? text : null;
}

function validateMdnsCandidate(candidate, selfInstanceId = "") {
  const service = mdnsText(candidate?.service, 40);
  const protocolVersion = mdnsText(candidate?.protocolVersion, 8);
  const instanceId = mdnsText(candidate?.instanceId, 80);
  const name = mdnsText(candidate?.name, 40);
  const sourceAddress = String(candidate?.address || "").replace(/^::ffff:/i, "").trim();
  const port = Number(candidate?.port);
  if (candidate?.serviceType !== MARKERDECK_MDNS_SERVICE_TYPE ||
      candidate?.protocol !== "tcp" ||
      service !== DISCOVERY_SERVICE ||
      protocolVersion !== String(DISCOVERY_PROTOCOL_VERSION) ||
      !instanceId || !/^[A-Za-z0-9_-]{8,80}$/.test(instanceId) ||
      !name || !Number.isInteger(port) || port < 1 || port > 65535 ||
      !isAllowedDiscoveryIpv4(sourceAddress) || instanceId === selfInstanceId) {
    return null;
  }
  const serviceAddress = `http://${sourceAddress}:${port}`;
  return {
    instanceId,
    name,
    serviceAddress,
    port,
    sourceAddress,
    source: "mdns"
  };
}

function ipv4ToNumber(address) {
  return address.split(".").reduce((value, octet) => (value * 256) + Number(octet), 0) >>> 0;
}

function numberToIpv4(value) {
  return [24, 16, 8, 0].map((shift) => (value >>> shift) & 255).join(".");
}

function interfaceBroadcastAddresses() {
  const addresses = new Set(["255.255.255.255", DISCOVERY_MULTICAST_ADDRESS]);
  Object.values(os.networkInterfaces()).flat().forEach((entry) => {
    if (!entry || entry.family !== "IPv4" || entry.internal || !entry.address || !entry.netmask) return;
    const address = ipv4ToNumber(entry.address);
    const netmask = ipv4ToNumber(entry.netmask);
    addresses.add(numberToIpv4((address & netmask) | (~netmask >>> 0)));
  });
  return [...addresses];
}

async function verifyCandidate(candidate, nonce, sourceAddress, selfInstanceId, fetchImpl) {
  const controller = new AbortController();
  const timeout = setTimeout(() => controller.abort(), 900);
  timeout.unref?.();
  try {
    const response = await fetchImpl(
      `${candidate.serviceAddress}/api/discovery?nonce=${encodeURIComponent(nonce)}`,
      { cache: "no-store", redirect: "manual", signal: controller.signal }
    );
    if (!response.ok || response.status < 200 || response.status > 299) return null;
    const contentLength = Number(response.headers.get("content-length") || 0);
    if (contentLength > DISCOVERY_HTTP_BODY_LIMIT) return null;
    const body = await response.text();
    if (Buffer.byteLength(body, "utf8") > DISCOVERY_HTTP_BODY_LIMIT) return null;
    const verified = validateAdvertisement(
      parseAdvertisement(body),
      nonce,
      sourceAddress,
      selfInstanceId
    );
    if (!verified || verified.instanceId !== candidate.instanceId || verified.port !== candidate.port) {
      return null;
    }
    return verified;
  } catch (_) {
    return null;
  } finally {
    clearTimeout(timeout);
  }
}

function scanUdpCandidates({ discoveryPort, configuredTargets, scanTimeoutMs, selfInstanceId, nonce: requestedNonce }) {
  const nonce = requestedNonce || crypto.randomBytes(18).toString("base64url");
  const request = Buffer.from(JSON.stringify({
    service: DISCOVERY_SERVICE,
    protocolVersion: DISCOVERY_PROTOCOL_VERSION,
    type: DISCOVERY_REQUEST_TYPE,
    nonce
  }), "utf8");
  const candidates = new Map();
  let socket;
  try {
    socket = dgram.createSocket("udp4");
  } catch (_) {
    return Promise.resolve({ nonce, candidates: [] });
  }

  return new Promise((resolve) => {
    let finished = false;
    const finish = () => {
      if (finished) return;
      finished = true;
      clearTimeout(scanTimer);
      try { socket.close(); } catch (_) {}
      resolve({ nonce, candidates: [...candidates.values()] });
    };
    const scanTimer = setTimeout(finish, scanTimeoutMs);
    scanTimer.unref?.();

    socket.on("message", (message, remote) => {
      if (message.length > DISCOVERY_MAX_PACKET_SIZE || remote.family !== "IPv4") return;
      const sourceAddress = remote.address.replace(/^::ffff:/, "");
      const candidate = validateAdvertisement(
        parseAdvertisement(message),
        nonce,
        sourceAddress,
        selfInstanceId
      );
      if (!candidate) return;
      candidates.set(`${candidate.instanceId}|${sourceAddress}|${candidate.port}`, {
        candidate,
        sourceAddress,
        source: "udp"
      });
    });
    socket.on("error", finish);
    try {
      socket.bind(0, "0.0.0.0", () => {
        try { socket.setBroadcast(true); } catch (_) {}
        const targets = configuredTargets.length ? configuredTargets : interfaceBroadcastAddresses();
        targets.forEach((target) => {
          socket.send(request, discoveryPort, target, () => {});
        });
      });
    } catch (_) {
      finish();
    }
  });
}

function mdnsCandidateEntries(candidates, selfInstanceId) {
  return candidates
    .map((candidate) => validateMdnsCandidate(candidate, selfInstanceId))
    .filter(Boolean)
    .map((candidate) => ({
      candidate,
      sourceAddress: candidate.sourceAddress,
      source: "mdns"
    }));
}

function createMarkerDeckHostScanner(options = {}) {
  const discoveryPort = Number(options.discoveryPort || 8766);
  const selfInstanceId = String(options.selfInstanceId || "");
  const scanTimeoutMs = Number(options.scanTimeoutMs || 1800);
  const fetchImpl = options.fetchImpl || fetch;
  const configuredTargets = Array.isArray(options.targets) ? options.targets.filter(Boolean) : [];
  const udpScanner = options.udpScanner || scanUdpCandidates;
  const mdnsBrowser = options.mdnsBrowser || createMarkerDeckMdnsBrowser({
    scanTimeoutMs: Number(options.mdnsScanTimeoutMs || DISCOVERY_MDNS_TIMEOUT_MS),
    bonjourFactory: options.bonjourFactory,
    disabled: options.mdnsDisabled
  });
  let activeScan = null;

  async function scanOnce() {
    if (!Number.isInteger(discoveryPort) || discoveryPort < 1 || discoveryPort > 65535) return [];
    const scanNonce = crypto.randomBytes(18).toString("base64url");
    const udpScan = Promise.resolve().then(() => udpScanner({
      discoveryPort,
      configuredTargets,
      scanTimeoutMs,
      selfInstanceId,
      nonce: scanNonce
    })).then((result) => ({
      nonce: scanNonce,
      candidates: Array.isArray(result?.candidates) ? result.candidates : []
    })).catch(() => ({ nonce: scanNonce, candidates: [] }));
    let mdnsScan;
    try {
      mdnsScan = Promise.resolve(mdnsBrowser?.scan?.(
        Number(options.mdnsScanTimeoutMs || DISCOVERY_MDNS_TIMEOUT_MS)
      ) || []);
    } catch (_) {
      mdnsScan = Promise.resolve([]);
    }
    mdnsScan = mdnsScan.catch(() => []);
    const [{ nonce, candidates: udpCandidates }, mdnsCandidates] = await Promise.all([
      udpScan,
      mdnsScan
    ]);
    const candidates = new Map();
    udpCandidates.forEach((entry) => {
      const candidate = entry?.candidate;
      if (!candidate || typeof entry?.sourceAddress !== "string") return;
      candidates.set(`${candidate.instanceId}|${entry.sourceAddress}|${candidate.port}`, entry);
    });
    mdnsCandidateEntries(Array.isArray(mdnsCandidates) ? mdnsCandidates : [], selfInstanceId).forEach((entry) => {
      entry.candidate = {
        ...entry.candidate,
        type: DISCOVERY_RESPONSE_TYPE,
        nonce,
        httpUrl: `http://${entry.sourceAddress}:${entry.candidate.port}`
      };
      candidates.set(`${entry.candidate.instanceId}|${entry.sourceAddress}|${entry.candidate.port}`, entry);
    });
    const verified = await Promise.all(
      [...candidates.values()].slice(0, DISCOVERY_MAX_HOSTS).map(({ candidate, sourceAddress }) => {
        if (candidate.instanceId === selfInstanceId) return null;
        return verifyCandidate(candidate, nonce, sourceAddress, selfInstanceId, fetchImpl);
      })
    );
    const merged = new Map();
    verified.filter(Boolean).forEach((host) => {
      const key = `${host.instanceId}|${host.serviceAddress}`;
      merged.set(key, host);
    });
    return [...merged.values()].sort((a, b) =>
      a.name.localeCompare(b.name, "zh-CN") || a.serviceAddress.localeCompare(b.serviceAddress)
    );
  }

  return {
    scan() {
      if (!activeScan) {
        activeScan = scanOnce().finally(() => { activeScan = null; });
      }
      return activeScan;
    }
  };
}

module.exports = {
  createMarkerDeckHostScanner,
  isAllowedDiscoveryIpv4,
  validateAdvertisement,
  validateMdnsCandidate
};
