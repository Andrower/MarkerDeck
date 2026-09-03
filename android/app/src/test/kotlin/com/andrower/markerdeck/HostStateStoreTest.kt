package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HostStateStoreTest {
    @Test
    fun registersDeviceKeepsSessionIdentityAndUpdatesState() {
        val store = MarkerDeckHostStateStore()
        val result = store.register(request())

        assertTrue(result.ok)
        assertEquals("session-1", result.sessionId)
        assertEquals("入口屏", result.name)
        assertEquals("#112233", result.state["bgColor"])
        assertEquals("100", result.state["overallBrightness"])
        assertEquals(1, store.getDevices().size)
        assertEquals("device-1", store.getDevices().single().deviceId)
    }

    @Test
    fun detectsASecondPageForTheSameDisplayAndPreservesDeviceNameAndGroup() {
        val store = MarkerDeckHostStateStore(idFactory = { "generated-session" })
        store.register(request())
        store.renameDevice("session-1", "入口屏新名")
        store.assignGroup(listOf("session-1"), "一号棚")

        val second = store.register(
            request(sessionId = "session-1", pageInstanceId = "page-2", name = "", updateName = false)
        ) { sessionId, pageInstanceId -> sessionId == "session-1" && pageInstanceId == "page-2" }

        assertTrue(second.ok)
        assertTrue(second.sessionId != "session-1")
        val devices = store.getDevices()
        assertEquals(2, devices.size)
        assertTrue(devices.all { it.name == "入口屏新名" })
        assertTrue(devices.all { it.group == "一号棚" })
    }

    @Test
    fun persistsCustomPresetsAndTracksLockAcknowledgements() {
        val store = MarkerDeckHostStateStore()
        store.register(request())
        val preset = store.savePreset("暗场", mapOf("bgBrightness" to "30"))

        assertNotNull(preset)
        assertEquals("#004d00", store.getPresets().last().state["bgColor"])
        assertEquals("100", store.getPresets().last().state["bgBrightness"])

        val delivery = store.createLockCommand(listOf("session-1"), enabled = true)
        assertEquals(1, delivery.status.pendingCount)
        val status = store.acknowledgeLock("${delivery.commandId}", "session-1", true, true, "")

        assertNotNull(status)
        assertEquals(1, status?.confirmedCount)
        assertEquals(0, status?.pendingCount)
        assertTrue(status?.complete == true)
        assertEquals("1", store.getState("session-1")["forceLock"])
    }

    @Test
    fun registrationHeartbeatReturnsPersistedLockCommandAndChineseName() {
        val store = MarkerDeckHostStateStore()
        store.register(request(name = "中文投放屏"))
        val delivery = store.createLockCommand(listOf("session-1"), enabled = true)

        val heartbeat = store.register(request(name = "", updateName = false))

        assertEquals("中文投放屏", heartbeat.name)
        assertEquals("lock", heartbeat.state["lockCommand"])
        assertEquals(delivery.commandId, heartbeat.state["lockCommandId"])
        assertEquals("100", heartbeat.state["overallBrightness"])
    }

    @Test
    fun removesOnlyOfflineDevicesAndSupportsGlobalLock() {
        var now = 100_000L
        val store = MarkerDeckHostStateStore(clock = { now })
        store.register(request())
        now += MARKERDECK_HOST_OFFLINE_MS + 1

        assertTrue(store.deleteDevices(listOf("session-1"), allOffline = false).contains("session-1"))
        val delivery = store.broadcastLock(enabled = false)

        assertEquals(0, delivery.status.targetCount)
        assertTrue(delivery.global)
        assertEquals(delivery.commandId, store.currentGlobalLockCommandId())
    }

    private fun request(
        sessionId: String = "session-1",
        pageInstanceId: String = "page-1",
        name: String = "入口屏",
        updateName: Boolean = true
    ) = HostRegistrationRequest(
        legacyId = sessionId,
        sessionId = sessionId,
        deviceId = "device-1",
        pageInstanceId = pageInstanceId,
        name = name,
        updateName = updateName,
        role = "display",
        width = 1080,
        height = 1920,
        dpr = 2.0,
        userAgent = "test-agent",
        state = mapOf("bgColor" to "#112233")
    )
}
