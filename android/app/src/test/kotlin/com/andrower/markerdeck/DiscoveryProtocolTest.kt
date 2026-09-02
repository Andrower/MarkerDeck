package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryProtocolTest {
    private val nonce = "0123456789abcdef"

    @Test
    fun validatesVersionNoncePeerAndUsesTheObservedLanAddress() {
        val response = advertisement()

        val host = validateDiscoveryResponse(response, nonce, "192.168.1.10")

        assertNotNull(host)
        assertEquals("http://192.168.1.10:8765", host?.serviceAddress)
        assertEquals("http://192.168.1.20:8765", host?.advertisedHttpUrl)
    }

    @Test
    fun rejectsUntrustedOrMalformedDiscoveryResponses() {
        val invalidResponses = listOf(
            advertisement(nonce = "other-nonce"),
            advertisement(protocolVersion = 2),
            advertisement(port = 0),
            advertisement(httpUrl = "https://192.168.1.20:8765"),
            advertisement(httpUrl = "http://192.168.1.20:8766"),
            advertisement(httpUrl = "http://public.example:8765"),
            advertisement(name = "\nnot safe")
        )

        invalidResponses.forEach { response ->
            assertNull(validateDiscoveryResponse(response, nonce, "192.168.1.10"))
        }
        assertNull(validateDiscoveryResponse(advertisement(), nonce, "8.8.8.8"))
    }

    @Test
    fun keepsHttpHandshakeBoundToTheUdpCandidate() {
        val candidate = validateDiscoveryResponse(advertisement(), nonce, "192.168.1.10")
        assertNotNull(candidate)
        assertTrue(isDiscoveryResponseForCandidate(advertisement(), candidate!!))
        assertFalse(
            isDiscoveryResponseForCandidate(
                advertisement().copy(instanceId = "different-instance"),
                candidate
            )
        )
        assertFalse(
            isDiscoveryResponseForCandidate(
                advertisement(port = 8766, httpUrl = "http://192.168.1.20:8766"),
                candidate
            )
        )
    }

    @Test
    fun mergesDuplicateResponsesByInstanceOrEndpointAndKeepsMultipleHosts() {
        val first = host("instance-a", "192.168.1.10", "一号宿主")
        val updated = host("instance-a", "192.168.1.11", "一号宿主新地址")
        val second = host("instance-b", "192.168.1.12", "二号宿主")

        val merged = mergeDiscoveredHosts(
            existing = listOf(first),
            incoming = listOf(updated, second, updated)
        )

        assertEquals(2, merged.size)
        assertEquals("http://192.168.1.11:8765", merged.first { it.instanceId == "instance-a" }.serviceAddress)
        assertTrue(merged.any { it.instanceId == "instance-b" })
    }

    @Test
    fun mergesFoundStatusWithoutDroppingVerifiedHosts() {
        val host = host("instance-a", "192.168.1.10", "一号宿主")
        val current = DiscoveryUiState(
            status = DiscoveryScanStatus.SCANNING,
            hosts = listOf(host)
        )

        val merged = mergeDiscoveryUiState(
            current = current,
            status = DiscoveryScanStatus.EMPTY
        )

        assertEquals(DiscoveryScanStatus.FOUND, merged.status)
        assertEquals(listOf(host), merged.hosts)
        assertFalse(merged.message.isNotEmpty())
    }

    private fun advertisement(
        protocolVersion: Int = MARKERDECK_DISCOVERY_PROTOCOL_VERSION,
        nonce: String = this.nonce,
        name: String = "MarkerDeck",
        port: Int = 8765,
        httpUrl: String = "http://192.168.1.20:8765"
    ) = DiscoveryAdvertisement(
        service = MARKERDECK_DISCOVERY_SERVICE,
        protocolVersion = protocolVersion,
        type = MARKERDECK_DISCOVERY_RESPONSE_TYPE,
        nonce = nonce,
        name = name,
        port = port,
        httpUrl = httpUrl,
        instanceId = "instance-1234"
    )

    private fun host(instanceId: String, address: String, name: String) = DiscoveryHost(
        instanceId = instanceId,
        name = name,
        serviceAddress = "http://$address:8765",
        port = 8765,
        advertisedHttpUrl = "http://$address:8765"
    )
}
