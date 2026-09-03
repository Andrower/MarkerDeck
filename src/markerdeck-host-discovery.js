const crypto = require("node:crypto");
const dgram = require("node:dgram");
const os = require("node:os");

const DISCOVERY_SERVICE = "markerdeck";
const DISCOVERY_PROTOCOL_VERSION = 1;
const DISCOVERY_REQUEST_TYPE = "discover";
const DISCOVERY_RESPONSE_TYPE = "response";
const DISCOVERY_MULTICAST_ADDRESS = "239.255.77.77";
const DISCOVERY_MAX_PACKET_SIZE = 4096;
const DISCOVERY_MAX_HOSTS = 32;
const DISCOVERY_HTTP_BODY_LIMIT = 16 * 1024;

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
    !/[\u0000-\u001f\u007f]/.test(value);
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
      !/^[A-Za-z0-9_-]{8,80}$/.test(String(advertisement.instanceId || "")) ||
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

function createMarkerDeckHostScanner(options = {}) {
  const discoveryPort = Number(options.discoveryPort || 8766);
  const selfInstanceId = String(options.selfInstanceId || "");
  const scanTimeoutMs = Number(options.scanTimeoutMs || 1800);
  const fetchImpl = options.fetchImpl || fetch;
  const configuredTargets = Array.isArray(options.targets) ? options.targets.filter(Boolean) : [];
  let activeScan = null;

  async function scanOnce() {
    if (!Number.isInteger(discoveryPort) || discoveryPort < 1 || discoveryPort > 65535) return [];
    const nonce = crypto.randomBytes(18).toString("base64url");
    const request = Buffer.from(JSON.stringify({
      service: DISCOVERY_SERVICE,
      protocolVersion: DISCOVERY_PROTOCOL_VERSION,
      type: DISCOVERY_REQUEST_TYPE,
      nonce
    }), "utf8");
    const candidates = new Map();
    const socket = dgram.createSocket("udp4");

    return new Promise((resolve) => {
      let finished = false;
      const finish = async () => {
        if (finished) return;
        finished = true;
        clearTimeout(scanTimer);
        try { socket.close(); } catch (_) {}
        const verified = await Promise.all(
          [...candidates.values()].slice(0, DISCOVERY_MAX_HOSTS).map(({ candidate, sourceAddress }) =>
            verifyCandidate(candidate, nonce, sourceAddress, selfInstanceId, fetchImpl)
          )
        );
        resolve(verified.filter(Boolean).sort((a, b) =>
          a.name.localeCompare(b.name, "zh-CN") || a.serviceAddress.localeCompare(b.serviceAddress)
        ));
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
        candidates.set(`${candidate.instanceId}|${candidate.serviceAddress}`, { candidate, sourceAddress });
      });
      socket.on("error", finish);
      socket.bind(0, "0.0.0.0", () => {
        try { socket.setBroadcast(true); } catch (_) {}
        const targets = configuredTargets.length ? configuredTargets : interfaceBroadcastAddresses();
        targets.forEach((target) => {
          socket.send(request, discoveryPort, target, () => {});
        });
      });
    });
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
  validateAdvertisement
};
