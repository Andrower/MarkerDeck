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
        assertEquals("100", preset?.state?.get("bgBrightness"))
        assertEquals("100", preset?.state?.get("crossBrightness"))
        assertEquals("100", preset?.state?.get("overallBrightness"))
        assertEquals(8, defaultHostPresets().size)
        assertEquals("#009900", defaultHostPresets()[1].state["bgColor"])
        assertEquals("#004d00", defaultHostPresets()[2].state["bgColor"])
        assertEquals("#002682", defaultHostPresets()[4].state["bgColor"])
        assertEquals("#001341", defaultHostPresets()[5].state["bgColor"])
    }

    @Test
    fun canonicalizesLegacyBrightnessAndIsIdempotent() {
        val legacy = normalizeHostState(
            mapOf(
                "bgColor" to "#135790",
                "bgBrightness" to "60",
                "crossColor" to "#2468ac",
                "crossBrightness" to "30",
                "overallBrightness" to "50.4"
            )
        )

        assertEquals("#0b3456", legacy["bgColor"])
        assertEquals("#0b1f34", legacy["crossColor"])
        assertEquals("100", legacy["bgBrightness"])
        assertEquals("100", legacy["crossBrightness"])
        assertEquals("50", legacy["overallBrightness"])
        assertEquals(legacy, normalizeHostState(legacy))
    }

    @Test
    fun normalizesOverallBrightnessWithABackwardCompatibleDefault() {
        assertEquals("100", normalizeOverallBrightness(null))
        assertEquals("50", normalizeOverallBrightness("50.4"))
        assertEquals("0", normalizeOverallBrightness(-20))
        assertEquals("100", normalizeOverallBrightness(120))
        assertEquals("100", normalizeHostState(mapOf("bgColor" to "#123456"))["overallBrightness"])
    }
}
