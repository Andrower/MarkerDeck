package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostProtocolTest {
    @Test
    fun buildsHostInfoWithAndroidCapabilitiesAndLaunchUrl() {
        val info = buildHostInfo("192.168.1.8", 8765, " 摄影棚主控 ")

        assertEquals("192.168.1.8", info.ip)
        assertEquals("http://192.168.1.8:8765/markerdeck-launch.html", info.url)
        assertEquals("摄影棚主控", info.name)
        assertFalse(info.capabilities.videoExport)
        assertTrue(info.capabilities.sse)
    }

    @Test
    fun validatesVersionedDiscoveryNonceAndResponseShape() {
        val nonce = "nonce-123456"
        val request = """{"service":"markerdeck","protocolVersion":1,"type":"discover","nonce":"$nonce"}"""

        assertEquals(nonce, parseDiscoveryNonce(request))
        assertNull(parseDiscoveryNonce(request.replace("markerdeck", "other")))

        val response = buildDiscoveryResponse(nonce, "MarkerDeck", 8765, "192.168.1.8", "instance-1234")
        assertEquals("response", response.optString("type"))
        assertEquals(nonce, response.optString("nonce"))
        assertEquals("http://192.168.1.8:8765", response.optString("httpUrl"))
    }

    @Test
    fun normalizesPresetsWithoutAllowingUnknownStateKeys() {
        val preset = cleanHostPreset(
            HostPreset(
                id = "bad id!",
                name = " 自定义 ",
                state = mapOf("bgColor" to "#123456", "unexpected" to "ignored")
            ),
            "fallback"
        )

        assertNotNull(preset)
        assertEquals("badid", preset?.id)
        assertEquals("自定义", preset?.name)
        assertEquals("#123456", preset?.state?.get("bgColor"))
        assertFalse(preset?.state?.containsKey("unexpected") == true)
        assertEquals(8, defaultHostPresets().size)
    }
}
