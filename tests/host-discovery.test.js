const assert = require("node:assert/strict");
const { test } = require("node:test");
const {
  isAllowedDiscoveryIpv4,
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
