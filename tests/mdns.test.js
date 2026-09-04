const assert = require("node:assert/strict");
const { EventEmitter } = require("node:events");
const { test } = require("node:test");
const {
  MARKERDECK_MDNS_SERVICE_TYPE,
  createMarkerDeckMdnsPublisher,
  normalizedServiceCandidates
} = require("../src/markerdeck-mdns");
const {
  createMarkerDeckHostScanner,
  validateMdnsCandidate
} = require("../src/markerdeck-host-discovery");

const instanceId = "mdns-test-instance-01";

test("publishes the versioned DNS-SD TXT contract and exposes availability after up", () => {
  const service = new EventEmitter();
  service.stop = () => {};
  let publishOptions;
  const bonjour = new EventEmitter();
  bonjour.publish = (options) => {
    publishOptions = options;
    return service;
  };
  bonjour.destroy = () => {};

  const publisher = createMarkerDeckMdnsPublisher({
    name: "测试宿主",
    port: 8765,
    instanceId,
    bonjourFactory: () => bonjour
  });

  assert.equal(publisher.start(), true);
  assert.equal(publisher.isAvailable(), false);
  assert.equal(publishOptions.type, "markerdeck");
  assert.equal(publishOptions.protocol, "tcp");
  assert.equal(publishOptions.txt.service, "markerdeck");
  assert.equal(publishOptions.txt.protocolVersion, "1");
  assert.equal(publishOptions.txt.instanceId, instanceId);
  service.emit("up");
  assert.equal(publisher.isAvailable(), true);
  publisher.stop();
  assert.equal(publisher.isAvailable(), false);
});

test("keeps HTTP service alive when mDNS publication fails", () => {
  const publisher = createMarkerDeckMdnsPublisher({
    name: "MarkerDeck",
    port: 8765,
    instanceId,
    bonjourFactory: () => ({
      publish: () => { throw new Error("mDNS unavailable"); },
      destroy: () => {}
    })
  });

  assert.doesNotThrow(() => assert.equal(publisher.start(), false));
  assert.equal(publisher.isAvailable(), false);
});

test("normalizes bonjour TXT buffers and rejects unsafe mDNS candidates", () => {
  const candidates = normalizedServiceCandidates({
    type: "markerdeck",
    protocol: "tcp",
    name: "MarkerDeck",
    port: 8765,
    addresses: ["192.168.1.20"],
    txt: {
      service: Buffer.from("markerdeck"),
      protocolVersion: Buffer.from("1"),
      instanceId: Buffer.from(instanceId),
      name: Buffer.from("测试宿主")
    }
  });
  assert.equal(candidates[0].serviceType, MARKERDECK_MDNS_SERVICE_TYPE);
  assert.equal(candidates[0].protocol, "tcp");
  assert.equal(candidates[0].instanceId, instanceId);
  assert.ok(validateMdnsCandidate(candidates[0]));
  assert.equal(
    validateMdnsCandidate({ ...candidates[0], protocol: "udp" }),
    null
  );
  assert.equal(
    validateMdnsCandidate({ ...candidates[0], address: "8.8.8.8" }),
    null
  );
});

test("derives and validates the full DNS-SD type from bonjour service metadata", () => {
  const [candidate] = normalizedServiceCandidates({
    type: "markerdeck",
    protocol: "tcp",
    host: "192.168.1.20",
    port: 8765,
    name: "MarkerDeck",
    txt: { service: "markerdeck", protocolVersion: "1", instanceId, name: "MarkerDeck" }
  });
  assert.equal(candidate.serviceType, MARKERDECK_MDNS_SERVICE_TYPE);
  assert.ok(validateMdnsCandidate(candidate));
  assert.equal(
    validateMdnsCandidate({ ...candidate, serviceType: "_other._tcp.local" }),
    null
  );
  const [wrongProtocol] = normalizedServiceCandidates({
    serviceType: MARKERDECK_MDNS_SERVICE_TYPE,
    protocol: "udp",
    host: "192.168.1.20",
    port: 8765,
    name: "MarkerDeck",
    txt: { service: "markerdeck", protocolVersion: "1", instanceId, name: "MarkerDeck" }
  });
  assert.equal(validateMdnsCandidate(wrongProtocol), null);
});

test("combines UDP and mDNS candidates and verifies the observed origin with one nonce", async () => {
  let scanNonce = "";
  const mdnsBrowser = {
    scan: async () => [{
      serviceType: MARKERDECK_MDNS_SERVICE_TYPE,
      protocol: "tcp",
      service: "markerdeck",
      protocolVersion: "1",
      instanceId,
      name: "mDNS 名称",
      port: 8765,
      address: "192.168.1.20"
    }]
  };
  const udpScanner = async ({ nonce }) => {
    scanNonce = nonce;
    return {
      nonce,
      candidates: [{
        candidate: {
          instanceId,
          name: "UDP 名称",
          serviceAddress: "http://192.168.1.20:8765",
          port: 8765
        },
        sourceAddress: "192.168.1.20",
        source: "udp"
      }]
    };
  };
  const fetchImpl = async (url) => {
    const nonce = new URL(url).searchParams.get("nonce");
    assert.equal(nonce, scanNonce);
    return {
      ok: true,
      status: 200,
      headers: { get: () => "220" },
      text: async () => JSON.stringify({
        service: "markerdeck",
        protocolVersion: 1,
        type: "response",
        nonce,
        name: "HTTP 验真名称",
        port: 8765,
        httpUrl: "http://192.168.1.21:8765",
        instanceId
      })
    };
  };
  const scanner = createMarkerDeckHostScanner({
    selfInstanceId: "different-self",
    discoveryPort: 8766,
    mdnsBrowser,
    udpScanner,
    fetchImpl
  });

  const hosts = await scanner.scan();
  assert.equal(hosts.length, 1);
  assert.deepEqual(hosts[0], {
    instanceId,
    name: "HTTP 验真名称",
    serviceAddress: "http://192.168.1.20:8765",
    controlUrl: "http://192.168.1.20:8765/markerdeck-screen.html?mode=control",
    port: 8765
  });
});

test("falls back to UDP when mDNS browsing fails and excludes the local instance", async () => {
  const udpInstance = "udp-fallback-instance-01";
  let fetchCount = 0;
  const scanner = createMarkerDeckHostScanner({
    selfInstanceId: "self-instance-01",
    mdnsBrowser: { scan: async () => { throw new Error("mDNS unavailable"); } },
    udpScanner: async ({ nonce }) => ({
      nonce,
      candidates: [
        {
          candidate: { instanceId: "self-instance-01", name: "self", serviceAddress: "http://192.168.1.2:8765", port: 8765 },
          sourceAddress: "192.168.1.2"
        },
        {
          candidate: { instanceId: udpInstance, name: "UDP", serviceAddress: "http://192.168.1.3:8765", port: 8765 },
          sourceAddress: "192.168.1.3"
        }
      ]
    }),
    fetchImpl: async (url) => {
      fetchCount += 1;
      const nonce = new URL(url).searchParams.get("nonce");
      return {
        ok: true,
        status: 200,
        headers: { get: () => "200" },
        text: async () => JSON.stringify({
          service: "markerdeck",
          protocolVersion: 1,
          type: "response",
          nonce,
          name: "UDP",
          port: 8765,
          httpUrl: "http://192.168.1.3:8765",
          instanceId: udpInstance
        })
      };
    }
  });

  const hosts = await scanner.scan();
  assert.equal(fetchCount, 1);
  assert.equal(hosts.length, 1);
  assert.equal(hosts[0].instanceId, udpInstance);
});
