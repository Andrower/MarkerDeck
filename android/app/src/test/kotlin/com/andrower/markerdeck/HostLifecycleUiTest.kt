package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HostLifecycleUiTest {
    @Test
    fun stoppedHostDisablesTheStopAction() {
        val status = embeddedHostStatus(null)

        assertEquals(EmbeddedHostServiceState.STOPPED, status.state)
        assertFalse(status.isRunning)
        assertFalse(shouldEnableStopEmbeddedHost(status))
    }

    @Test
    fun runningHostEnablesTheStopActionAndKeepsSessionDetails() {
        val session = EmbeddedHostSession(
            mode = EmbeddedHostMode.LAN_HOST,
            origin = "http://127.0.0.1:8765",
            url = "http://127.0.0.1:8765/host",
            port = 8765,
            lanAddress = "192.168.1.20",
            discoveryAvailable = true,
            instanceId = "instance-shared-1234",
            udpDiscoveryAvailable = true,
            mdnsDiscoveryAvailable = true
        )
        val status = embeddedHostStatus(session)

        assertEquals(EmbeddedHostServiceState.RUNNING, status.state)
        assertTrue(status.isRunning)
        assertEquals(session, status.session)
        assertEquals("instance-shared-1234", status.session?.instanceId)
        assertTrue(status.session?.udpDiscoveryAvailable == true)
        assertTrue(status.session?.mdnsDiscoveryAvailable == true)
        assertTrue(shouldEnableStopEmbeddedHost(status))
    }
}
