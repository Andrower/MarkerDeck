package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectionEmergencyControlsTest {
    @Test
    fun initialUnlockedProjectionCanKeepExitControlVisibleWithoutTimeout() {
        val shown = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.SHOW_REQUESTED_PERSISTENT,
            projectionActive = true
        )

        assertTrue(shown.state.visible)
        assertEquals(
            ProjectionEmergencyControlsTimeoutBehavior.NONE,
            shown.state.timeoutBehavior
        )
        assertFalse(shown.shouldRelockProjection)
    }

    @Test
    fun sharedControlsShowForAnActiveProjectionAndStayHiddenOtherwise() {
        val shown = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.SHOW_REQUESTED_HIDE_ONLY,
            projectionActive = true
        )
        val inactive = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.SHOW_REQUESTED_HIDE_ONLY,
            projectionActive = false
        )

        assertTrue(shown.state.visible)
        assertEquals(
            ProjectionEmergencyControlsTimeoutBehavior.HIDE_ONLY,
            shown.state.timeoutBehavior
        )
        assertFalse(shown.shouldRelockProjection)
        assertFalse(inactive.state.visible)
    }

    @Test
    fun showRequestCanReopenControlsAfterThePageHidesThem() {
        val hidden = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(visible = true),
            event = ProjectionEmergencyControlEvent.HIDE_REQUESTED,
            projectionActive = true
        )
        val shownAgain = reduceProjectionEmergencyControls(
            current = hidden.state,
            event = ProjectionEmergencyControlEvent.SHOW_REQUESTED_HIDE_ONLY,
            projectionActive = true
        )

        assertFalse(hidden.state.visible)
        assertTrue(shownAgain.state.visible)
    }

    @Test
    fun controlsOpenedAfterUnlockingAPreviouslyLockedProjectionRelockOnTimeout() {
        val shown = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.SHOW_REQUESTED_WITH_RELOCK,
            projectionActive = true
        )
        val timedOut = reduceProjectionEmergencyControls(
            current = shown.state,
            event = ProjectionEmergencyControlEvent.TIMEOUT,
            projectionActive = true
        )

        assertTrue(shown.state.visible)
        assertEquals(
            ProjectionEmergencyControlsTimeoutBehavior.RELOCK_PROJECTION,
            shown.state.timeoutBehavior
        )
        assertFalse(timedOut.state.visible)
        assertEquals(
            ProjectionEmergencyControlsTimeoutBehavior.HIDE_ONLY,
            timedOut.state.timeoutBehavior
        )
        assertTrue(timedOut.shouldRelockProjection)
    }

    @Test
    fun controlsOpenedWhileProjectionWasUnlockedOnlyHideOnTimeout() {
        val shown = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.SHOW_REQUESTED_HIDE_ONLY,
            projectionActive = true
        )
        val timedOut = reduceProjectionEmergencyControls(
            current = shown.state,
            event = ProjectionEmergencyControlEvent.TIMEOUT,
            projectionActive = true
        )

        assertTrue(shown.state.visible)
        assertEquals(
            ProjectionEmergencyControlsTimeoutBehavior.HIDE_ONLY,
            shown.state.timeoutBehavior
        )
        assertFalse(timedOut.state.visible)
        assertFalse(timedOut.shouldRelockProjection)
    }

    @Test
    fun controlInteractionPausesTimeoutAndResumesAfterItEnds() {
        val shown = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.SHOW_REQUESTED_WITH_RELOCK,
            projectionActive = true
        )
        val started = reduceProjectionEmergencyControls(
            current = shown.state,
            event = ProjectionEmergencyControlEvent.CONTROL_INTERACTION_STARTED,
            projectionActive = true
        )
        val timedOutDuringInteraction = reduceProjectionEmergencyControls(
            current = started.state,
            event = ProjectionEmergencyControlEvent.TIMEOUT,
            projectionActive = true
        )
        val ended = reduceProjectionEmergencyControls(
            current = started.state,
            event = ProjectionEmergencyControlEvent.CONTROL_INTERACTION_ENDED,
            projectionActive = true
        )

        assertTrue(started.state.visible)
        assertTrue(started.state.interactionActive)
        assertFalse(started.state.hasTimeout())
        assertEquals(started.state, timedOutDuringInteraction.state)
        assertFalse(timedOutDuringInteraction.shouldRelockProjection)
        assertFalse(ended.state.interactionActive)
        assertTrue(ended.state.hasTimeout())
    }

    @Test
    fun interactionEventsDoNotShowOrKeepTimeoutForHiddenControls() {
        val started = reduceProjectionEmergencyControls(
            current = ProjectionEmergencyControlsState(),
            event = ProjectionEmergencyControlEvent.CONTROL_INTERACTION_STARTED,
            projectionActive = true
        )

        assertFalse(started.state.visible)
        assertFalse(started.state.interactionActive)
        assertFalse(started.state.hasTimeout())
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
        assertEquals(
            ProjectionEmergencyControlsTimeoutBehavior.HIDE_ONLY,
            hidden.state.timeoutBehavior
        )
        assertFalse(hidden.shouldRelockProjection)
        assertFalse(stopped.state.visible)
        assertFalse(stopped.shouldRelockProjection)
    }
}
