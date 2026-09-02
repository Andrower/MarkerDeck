package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ServiceUrlTest {
    @Test
    fun buildsDisplayUrlFromNormalizedServiceOrigin() {
        assertEquals(
            "http://192.168.1.20:8765/markerdeck-screen.html?mode=display",
            buildDisplayUrl(" HTTP://192.168.1.20:8765/ ")
        )
    }

    @Test
    fun acceptsHighestValidPort() {
        assertEquals(
            "https://markerdeck.local:65535/markerdeck-screen.html?mode=display",
            buildDisplayUrl("https://MarkerDeck.local:65535")
        )
    }

    @Test
    fun addsHttpToBareIpHostAndLocalhostInputs() {
        assertEquals(
            "http://192.168.1.2/markerdeck-screen.html?mode=display",
            buildDisplayUrl("192.168.1.2")
        )
        assertEquals(
            "http://192.168.1.2:8765/markerdeck-screen.html?mode=display",
            buildDisplayUrl("192.168.1.2:8765")
        )
        assertEquals(
            "http://localhost:8765/markerdeck-screen.html?mode=display",
            buildDisplayUrl("localhost:8765")
        )
    }

    @Test
    fun preservesIpv6OriginAndEncodesAndroidDeviceName() {
        assertEquals(
            "http://[2001:db8::1]:8765/markerdeck-screen.html?mode=display&androidDeviceName=Front%20%26%20Side%2F%E5%B1%8F",
            buildDisplayUrl(" HTTP://[2001:DB8::1]:8765/path?old=query ", " Front & Side/屏 ")
        )
    }

    @Test
    fun omitsOptionalAndroidDeviceNameWhenEmpty() {
        assertEquals(
            "http://markerdeck.local/markerdeck-screen.html?mode=display",
            buildDisplayUrl("http://MarkerDeck.local", "   ")
        )
    }

    @Test
    fun rejectsUnsupportedSchemeMissingHostAndInvalidPort() {
        val invalidAddresses = listOf(
            "",
            "ftp://192.168.1.20:8765",
            "http:///markerdeck-screen.html",
            "http://:8765",
            "http://192.168.1.20:",
            "http://192.168.1.20:abc",
            "http://192.168.1.20:0",
            "http://192.168.1.20:65536"
        )

        invalidAddresses.forEach { address ->
            assertThrows(IllegalArgumentException::class.java) {
                buildDisplayUrl(address)
            }
        }
    }

    @Test
    fun allowsSameOriginPathsApiAndEventStreamWithEquivalentDefaultPorts() {
        assertTrue(
            isAllowedTopLevelNavigation(
                "http://MarkerDeck.local/markerdeck-screen.html?mode=display",
                "http://markerdeck.local:80"
            )
        )
        assertTrue(
            isAllowedTopLevelNavigation(
                "http://markerdeck.local/api/devices",
                "http://markerdeck.local"
            )
        )
        assertTrue(
            isAllowedTopLevelNavigation(
                "http://markerdeck.local/api/events?stream=sse",
                "http://markerdeck.local"
            )
        )
        assertTrue(
            isAllowedTopLevelNavigation(
                "https://markerdeck.local:443/markerdeck-screen.html",
                "https://markerdeck.local"
            )
        )
    }

    @Test
    fun rejectsCrossOriginAndDeceptiveHostSuffixes() {
        val allowedOrigin = "http://markerdeck.local:8765"

        assertFalse(isAllowedTopLevelNavigation("http://other.local:8765/path", allowedOrigin))
        assertFalse(isAllowedTopLevelNavigation("https://markerdeck.local:8765/path", allowedOrigin))
        assertFalse(isAllowedTopLevelNavigation("http://markerdeck.local:8766/path", allowedOrigin))
        assertFalse(isAllowedTopLevelNavigation("http://markerdeck.local.attacker:8765/path", allowedOrigin))
        assertFalse(isAllowedTopLevelNavigation("http://attacker-markerdeck.local:8765/path", allowedOrigin))
        assertFalse(isAllowedTopLevelNavigation("http://user:pass@markerdeck.local:8765/path", allowedOrigin))
    }

    @Test
    fun rejectsInvalidTopLevelUrls() {
        val invalidUrls = listOf(
            "",
            "not a url",
            "javascript:alert(1)",
            "file:///tmp/markerdeck-screen.html",
            "http:///path",
            "http://markerdeck.local:0/path",
            "http://markerdeck.local:65536/path",
            "http://markerdeck.local:bad/path"
        )

        invalidUrls.forEach { url ->
            assertFalse(isAllowedTopLevelNavigation(url, "http://markerdeck.local:8765"))
        }
    }

    @Test
    fun allowsAboutBlankOnlyWhenItIsExplicitCleanup() {
        val allowedOrigin = "http://markerdeck.local:8765"

        assertFalse(isAllowedTopLevelNavigation(WEBVIEW_CLEANUP_URL, allowedOrigin))
        assertTrue(isAllowedTopLevelNavigation(" about:blank ", allowedOrigin, allowCleanup = true))
        assertFalse(isAllowedTopLevelNavigation("about:blank#unexpected", allowedOrigin, allowCleanup = true))
    }
}
