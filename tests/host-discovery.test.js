const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { test } = require("node:test");
const {
  createMarkerDeckHostScanner,
  isAllowedDiscoveryIpv4,
  scanUdpCandidates,
  validateAdvertisement
} = require("../src/markerdeck-host-discovery");

function advertisement(overrides = {}) {
  return {
    service: "markerdeck",
    protocolVersion: 1,
    type: "response",
    nonce: "test-discovery-nonce",
    name: "测试手机宿主",
    port: 8765,
    httpUrl: "http://192.168.1.20:8765",
    instanceId: "test-mobile-host-01",
    ...overrides
  };
}

function fakeUdpSocket(onSend) {
  const socket = new EventEmitter();
  socket.bind = (...args) => setImmediate(args[args.length - 1]);
  socket.setBroadcast = () => {};
  socket.send = (...args) => onSend(socket, ...args);
  socket.close = () => {};
  return socket;
}

function discoveryFetchResponse(candidate, nonce) {
  return {
    ok: true,
    status: 200,
    headers: { get: () => "200" },
    text: async () => JSON.stringify(advertisement({
      nonce,
      instanceId: candidate.instanceId,
      name: candidate.name,
      port: candidate.port,
      httpUrl: candidate.serviceAddress
    }))
  };
}

test("accepts private and loopback discovery peers but rejects public peers", () => {
  assert.equal(isAllowedDiscoveryIpv4("192.168.1.20"), true);
  assert.equal(isAllowedDiscoveryIpv4("10.0.0.8"), true);
  assert.equal(isAllowedDiscoveryIpv4("127.0.0.1"), true);
  assert.equal(isAllowedDiscoveryIpv4("8.8.8.8"), false);
});

test("builds a control URL only from a valid nonce-correlated response", () => {
  assert.deepEqual(
    validateAdvertisement(
      advertisement(),
      "test-discovery-nonce",
      "192.168.1.20",
      "desktop-host-01"
    ),
    {
      instanceId: "test-mobile-host-01",
      name: "测试手机宿主",
      serviceAddress: "http://192.168.1.20:8765",
      controlUrl: "http://192.168.1.20:8765/markerdeck-screen.html?mode=control",
      port: 8765
    }
  );
  assert.equal(
    validateAdvertisement(advertisement(), "different-nonce", "192.168.1.20"),
    null
  );
});

test("rejects the current host and malformed advertised endpoints", () => {
  assert.equal(
    validateAdvertisement(
      advertisement(),
      "test-discovery-nonce",
      "192.168.1.20",
      "test-mobile-host-01"
    ),
    null
  );
  assert.equal(
    validateAdvertisement(
      advertisement({ httpUrl: "http://example.com:8765" }),
      "test-discovery-nonce",
      "192.168.1.20"
    ),
    null
  );
});

test("returns after the first verified candidate and aborts unfinished discovery sources", async () => {
  const candidate = {
    instanceId: "fast-host-instance-01",
    name: "快速宿主",
    serviceAddress: "http://192.168.1.20:8765",
    port: 8765
  };
  let udpSignal;
  let fetchCount = 0;
  const scanner = createMarkerDeckHostScanner({
    multiHostGraceMs: 0,
    mdnsBrowser: {
      scan: (_timeout, options) => {
        options.signal.addEventListener("abort", () => {}, { once: true });
        return new Promise(() => {});
      }
    },
    udpScanner: ({ onCandidate, signal }) => {
      udpSignal = signal;
      onCandidate({ candidate, sourceAddress: "192.168.1.20", source: "udp" });
      onCandidate({ candidate, sourceAddress: "192.168.1.20", source: "udp" });
      return new Promise(() => {});
    },
    fetchImpl: async (url) => {
      fetchCount += 1;
      const nonce = new URL(url).searchParams.get("nonce");
      return {
        ok: true,
        status: 200,
        headers: { get: () => "200" },
        text: async () => JSON.stringify(advertisement({
          nonce,
          instanceId: candidate.instanceId,
          name: candidate.name
        }))
      };
    }
  });

  const hosts = await scanner.scan();

  assert.equal(fetchCount, 1);
  assert.equal(hosts.length, 1);
  assert.equal(hosts[0].instanceId, candidate.instanceId);
  assert.equal(udpSignal.aborted, true);
});

test("includes a second host that verifies during the multi-host grace", async () => {
  const first = {
    instanceId: "grace-host-instance-01",
    name: "主控一",
    serviceAddress: "http://192.168.1.20:8765",
    port: 8765
  };
  const second = {
    instanceId: "grace-host-instance-02",
    name: "主控二",
    serviceAddress: "http://192.168.1.21:8765",
    port: 8765
  };
  const scanner = createMarkerDeckHostScanner({
    multiHostGraceMs: 80,
    mdnsBrowser: { scan: async () => [] },
    udpScanner: async ({ onCandidate }) => {
      onCandidate({ candidate: first, sourceAddress: "192.168.1.20", source: "udp" });
      onCandidate({ candidate: second, sourceAddress: "192.168.1.21", source: "udp" });
      return { candidates: [] };
    },
    fetchImpl: async (url, options) => {
      const parsed = new URL(url);
      const candidate = parsed.hostname === "192.168.1.20" ? first : second;
      if (candidate === second) {
        await new Promise((resolve, reject) => {
          const timer = setTimeout(resolve, 25);
          options.signal.addEventListener("abort", () => {
            clearTimeout(timer);
            reject(new Error("aborted"));
          }, { once: true });
        });
      }
      return discoveryFetchResponse(candidate, parsed.searchParams.get("nonce"));
    }
  });

  const hosts = await scanner.scan();

  assert.deepEqual(
    hosts.map((host) => host.instanceId).sort(),
    [first.instanceId, second.instanceId].sort()
  );
});

test("ends at the grace deadline and aborts unfinished verification and discovery resources", async () => {
  const first = {
    instanceId: "deadline-host-instance-01",
    name: "已验证宿主",
    serviceAddress: "http://192.168.1.30:8765",
    port: 8765
  };
  const unfinished = {
    instanceId: "deadline-host-instance-02",
    name: "未完成宿主",
    serviceAddress: "http://192.168.1.31:8765",
    port: 8765
  };
  let sourceSignal;
  let unfinishedVerificationAborted = false;
  const scanner = createMarkerDeckHostScanner({
    multiHostGraceMs: 40,
    mdnsBrowser: { scan: async () => [] },
    udpScanner: async ({ onCandidate, signal }) => {
      sourceSignal = signal;
      onCandidate({ candidate: first, sourceAddress: "192.168.1.30", source: "udp" });
      onCandidate({ candidate: unfinished, sourceAddress: "192.168.1.31", source: "udp" });
      return { candidates: [] };
    },
    fetchImpl: async (url, options) => {
      const parsed = new URL(url);
      if (parsed.hostname === "192.168.1.31") {
        return new Promise((resolve, reject) => {
          options.signal.addEventListener("abort", () => {
            unfinishedVerificationAborted = true;
            reject(new Error("aborted"));
          }, { once: true });
        });
      }
      return discoveryFetchResponse(first, parsed.searchParams.get("nonce"));
    }
  });

  const hosts = await scanner.scan();

  assert.deepEqual(hosts.map((host) => host.instanceId), [first.instanceId]);
  assert.equal(sourceSignal.aborted, true);
  assert.equal(unfinishedVerificationAborted, true);
});

test("a send error for one UDP target does not end the whole scan", async () => {
  const controller = new AbortController();
  const socket = fakeUdpSocket((currentSocket, payload, _port, target, callback) => {
    if (target === "unavailable-target") {
      queueMicrotask(() => callback(new Error("unavailable")));
      return;
    }
    callback(null);
    setTimeout(() => {
      currentSocket.emit(
        "message",
        Buffer.from(JSON.stringify(advertisement({ httpUrl: "http://127.0.0.1:8765" }))),
        { family: "IPv4", address: "127.0.0.1" }
      );
    }, 10);
  });
  const result = await scanUdpCandidates({
    discoveryPort: 8766,
    configuredTargets: ["unavailable-target", "127.0.0.1"],
    scanTimeoutMs: 800,
    selfInstanceId: "desktop-host-01",
    nonce: "test-discovery-nonce",
    onCandidate: () => controller.abort(),
    signal: controller.signal,
    socketFactory: () => socket
  });

  assert.equal(result.candidates.length, 1);
  assert.equal(result.candidates[0].candidate.serviceAddress, "http://127.0.0.1:8765");
});

test("UDP discovery retries once with the same nonce", async () => {
  const controller = new AbortController();
  const requests = [];
  const socket = fakeUdpSocket((_currentSocket, payload, _port, _target, callback) => {
    requests.push(JSON.parse(payload.toString("utf8")));
    callback(null);
    if (requests.length === 2) controller.abort();
  });
  const fallback = setTimeout(() => controller.abort(), 800);
  await scanUdpCandidates({
    discoveryPort: 8766,
    configuredTargets: ["127.0.0.1"],
    scanTimeoutMs: 1_000,
    selfInstanceId: "desktop-host-01",
    nonce: "retry-discovery-nonce",
    signal: controller.signal,
    socketFactory: () => socket
  });
  clearTimeout(fallback);

  assert.equal(requests.length, 2);
  assert.equal(requests[0].nonce, "retry-discovery-nonce");
  assert.equal(requests[1].nonce, requests[0].nonce);
});

test("aborting UDP discovery before the retry prevents later sends", async () => {
  const controller = new AbortController();
  let sendCount = 0;
  const socket = fakeUdpSocket((_currentSocket, _payload, _port, _target, callback) => {
    sendCount += 1;
    callback(null);
  });
  const scan = scanUdpCandidates({
    discoveryPort: 8766,
    configuredTargets: ["127.0.0.1"],
    scanTimeoutMs: 1_000,
    selfInstanceId: "desktop-host-01",
    nonce: "abort-discovery-nonce",
    signal: controller.signal,
    socketFactory: () => socket
  });
  setTimeout(() => controller.abort(), 20);
  await scan;
  await new Promise((resolve) => setTimeout(resolve, 330));

  assert.equal(sendCount, 1);
});
