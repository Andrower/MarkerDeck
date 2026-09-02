package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
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
    fun rejectsUnsupportedSchemeMissingHostAndInvalidPort() {
        val invalidAddresses = listOf(
            "",
            "ftp://192.168.1.20:8765",
            "http:///markerdeck-screen.html",
            "http://:8765",
            "http://192.168.1.20:0",
            "http://192.168.1.20:65536"
        )

        invalidAddresses.forEach { address ->
            assertThrows(IllegalArgumentException::class.java) {
                buildDisplayUrl(address)
            }
        }
    }
}
