package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QrConnectionFlowTest {
    @Test
    fun startsOnlyWhenNoOtherConnectionFlowIsActive() {
        val started = beginQrConnectionFlow(
            current = QrConnectionFlowState(),
            normalizedServiceAddress = "http://192.168.1.10:8765",
            initialDeviceName = "  入口屏  "
        )

        assertEquals(QrConnectionStep.HOST_CONFIRMATION, started?.step)
        assertEquals("入口屏", started?.initialDeviceName)
        assertNull(
            beginQrConnectionFlow(
                current = started!!,
                normalizedServiceAddress = "http://192.168.1.11:8765",
                initialDeviceName = "其他"
            )
        )
    }

    @Test
    fun rejectsStaleCallbacksAndRequiresADeviceName() {
        val state = beginQrConnectionFlow(
            QrConnectionFlowState(),
            "http://192.168.1.10:8765",
            ""
        )!!
        assertEquals(state, updateQrConnectionHostName(state, state.sessionId - 1, "旧宿主"))
        val hostConfirmed = confirmQrConnectionHost(state.copy(hostName = "当前宿主"), state.sessionId)!!
        assertNull(confirmQrConnectionDeviceName(hostConfirmed, state.sessionId, " \t"))
        val connecting = confirmQrConnectionDeviceName(hostConfirmed, state.sessionId, "  中文投放屏  ")
        assertEquals(QrConnectionStep.CONNECTING, connecting?.step)
        assertEquals("中文投放屏", connecting?.confirmedDeviceName)
        assertTrue(connecting?.normalizedServiceAddress == "http://192.168.1.10:8765")
    }

    @Test
    fun cancellationKeepsTheCounterButInvalidatesTheFlow() {
        val state = beginQrConnectionFlow(QrConnectionFlowState(), "http://192.168.1.10", "")!!
        val cancelled = cancelQrConnectionFlow(state, state.sessionId)
        assertEquals(QrConnectionStep.IDLE, cancelled.step)
        assertEquals(state.sessionId, cancelled.sessionId)
        assertNull(cancelled.normalizedServiceAddress)
        assertEquals(cancelled, cancelQrConnectionFlow(cancelled, state.sessionId - 1))
    }
}
