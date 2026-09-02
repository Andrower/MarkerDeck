package com.andrower.markerdeck

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionEmergencyControlsTest {
    @Test
    fun localEmergencyShowOnlyAppearsDuringActiveProjection() {
        val shown = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.SHOW_REQUESTED,
            projectionActive = true
        )
        val inactive = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.SHOW_REQUESTED,
            projectionActive = false
        )

        assertTrue(shown.state.visible)
        assertFalse(shown.shouldRelockProjection)
        assertFalse(inactive.state.visible)
    }

    @Test
    fun timeoutHidesControlsAndRequestsRelockOnlyWhenShown() {
        val timedOut = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(visible = true),
            event = ProjectionEmergencyControlEvent.TIMEOUT,
            projectionActive = true
        )
        val alreadyHidden = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.TIMEOUT,
            projectionActive = true
        )

        assertFalse(timedOut.state.visible)
        assertTrue(timedOut.shouldRelockProjection)
        assertFalse(alreadyHidden.state.visible)
        assertFalse(alreadyHidden.shouldRelockProjection)
    }

    @Test
    fun hideAndProjectionStopCancelVisibleControlsWithoutRelock() {
        val hidden = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(visible = true),
            event = ProjectionEmergencyControlEvent.HIDE_REQUESTED,
            projectionActive = true
        )
        val stopped = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(visible = true),
            event = ProjectionEmergencyControlEvent.PROJECTION_STOPPED,
            projectionActive = false
        )

        assertFalse(hidden.state.visible)
        assertFalse(hidden.shouldRelockProjection)
        assertFalse(stopped.state.visible)
        assertFalse(stopped.shouldRelockProjection)
    }
}
