package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LifecycleRecoveryTest {
    @Test
    fun doesNotReloadHealthyPageOnResumeOrScreenOn() {
        assertFalse(
            shouldReloadDisplayPage(
                DisplayPageState(
                    projectionActive = true,
                    pageHealthy = true,
                    mainFrameFailed = false,
                    loadInFlight = false
                )
            )
        )
    }

    @Test
    fun reloadsWhenPageIsMissingOrMainFrameFailed() {
        assertTrue(
            shouldReloadDisplayPage(
                DisplayPageState(
                    projectionActive = true,
                    pageHealthy = false,
                    mainFrameFailed = false,
                    loadInFlight = false
                )
            )
        )
        assertTrue(
            shouldReloadDisplayPage(
                DisplayPageState(
                    projectionActive = true,
                    pageHealthy = true,
                    mainFrameFailed = true,
                    loadInFlight = false
                )
            )
        )
    }

    @Test
    fun doesNotInterruptAnInFlightLoad() {
        assertFalse(
            shouldReloadDisplayPage(
                DisplayPageState(
                    projectionActive = true,
                    pageHealthy = false,
                    mainFrameFailed = false,
                    loadInFlight = true
                )
            )
        )
    }

    @Test
    fun doesNotReloadWhenProjectionIsInactive() {
        assertFalse(
            shouldReloadDisplayPage(
                DisplayPageState(
                    projectionActive = false,
                    pageHealthy = false,
                    mainFrameFailed = true,
                    loadInFlight = false
                )
            )
        )
    }

    @Test
    fun avoidsShowingSettingsOverAnActiveKeyguard() {
        assertTrue(shouldFinishBeforeShowingSettings(true, true))
        assertFalse(shouldFinishBeforeShowingSettings(true, false))
        assertFalse(shouldFinishBeforeShowingSettings(false, true))
    }

    @Test
    fun validatesAndNormalizesOnlyExplicitSavedProjection() {
        assertEquals(
            SavedProjection("https://markerdeck.local:443", "Entrance Screen"),
            validateSavedProjection(
                projectionActive = true,
                serviceAddress = " HTTPS://MarkerDeck.local:443/path ",
                deviceName = " Entrance Screen "
            )
        )
        assertNull(validateSavedProjection(false, "http://markerdeck.local", "Screen"))
        assertNull(validateSavedProjection(true, "not a URI", "Screen"))
        assertNull(validateSavedProjection(true, "http://markerdeck.local", "   "))
    }

    @Test
    fun healthyProjectionHidesEveryDiagnosticState() {
        ProjectionDiagnosticState.values().forEach { state ->
            assertEquals(
                ProjectionDiagnosticVisibility.HIDDEN,
                projectionDiagnosticVisibility(
                    projectionActive = true,
                    projectionState = state,
                    pageHealthy = true
                )
            )
        }
    }

    @Test
    fun projectionDiagnosticRequiresAnAllowedRecoveryStateAndUnhealthyPage() {
        assertTrue(
            shouldShowProjectionDiagnostic(
                projectionActive = true,
                projectionState = ProjectionDiagnosticState.SCREEN_ON_RECOVERY,
                pageHealthy = false
            )
        )
        assertTrue(
            shouldShowProjectionDiagnostic(
                projectionActive = true,
                projectionState = ProjectionDiagnosticState.RENDERER_RECOVERY,
                pageHealthy = false
            )
        )
        assertTrue(
            shouldShowProjectionDiagnostic(
                projectionActive = true,
                projectionState = ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE,
                pageHealthy = false
            )
        )
        assertFalse(
            shouldShowProjectionDiagnostic(
                projectionActive = true,
                projectionState = ProjectionDiagnosticState.PROJECTION_ACTIVE,
                pageHealthy = true
            )
        )
        assertFalse(
            shouldShowProjectionDiagnostic(
                projectionActive = true,
                projectionState = ProjectionDiagnosticState.PROJECTION_FAILURE,
                pageHealthy = false
            )
        )
    }

    @Test
    fun recoveryDiagnosticsAreTransientAndOnlyDegradedFailurePersists() {
        assertEquals(
            ProjectionDiagnosticVisibility.TRANSIENT,
            projectionDiagnosticVisibility(
                projectionActive = true,
                projectionState = ProjectionDiagnosticState.SCREEN_ON_RECOVERY,
                pageHealthy = false
            )
        )
        assertEquals(
            ProjectionDiagnosticVisibility.TRANSIENT,
            projectionDiagnosticVisibility(
                projectionActive = true,
                projectionState = ProjectionDiagnosticState.RENDERER_RECOVERY,
                pageHealthy = false
            )
        )
        assertEquals(
            ProjectionDiagnosticVisibility.HIDDEN,
            projectionDiagnosticVisibility(
                projectionActive = true,
                projectionState = ProjectionDiagnosticState.PROJECTION_FAILURE,
                pageHealthy = false
            )
        )
        assertEquals(
            ProjectionDiagnosticVisibility.PERSISTENT,
            projectionDiagnosticVisibility(
                projectionActive = true,
                projectionState = ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE,
                pageHealthy = false
            )
        )
    }

    @Test
    fun inactiveProjectionHidesDiagnosticState() {
        assertEquals(
            ProjectionDiagnosticVisibility.HIDDEN,
            projectionDiagnosticVisibility(
                projectionActive = false,
                projectionState = ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE,
                pageHealthy = false
            )
        )
    }

    @Test
    fun projectionWordingContainsOnlyProjectionRecoveryState() {
        val wording = projectionDiagnosticWording(ProjectionDiagnosticState.SCREEN_ON_RECOVERY)

        assertTrue(wording.contains("屏幕已点亮"))
        assertFalse(wording.contains("安全锁屏"))
        assertFalse(wording.contains("不能跳过"))
        assertFalse(wording.contains("Kiosk"))
    }

    @Test
    fun projectionFailureWordingIsDistinctFromRecoveryFailure() {
        assertEquals(
            "普通投放页面不可用。",
            projectionDiagnosticWording(ProjectionDiagnosticState.PROJECTION_FAILURE)
        )
        assertEquals(
            "普通投放恢复失败，当前处于降级状态。",
            projectionDiagnosticWording(ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE)
        )
    }

    @Test
    fun receiverRegistrationFailureIsSeparateAndNonfatal() {
        assertTrue(shouldShowScreenReceiverWarning(true, false))
        assertFalse(shouldShowScreenReceiverWarning(true, true))
        assertFalse(shouldShowScreenReceiverWarning(false, false))
        assertFalse(shouldReloadDisplayPage(DisplayPageState(true, true, false, false)))
    }

    @Test
    fun activeAndIdleStatesHaveNoProjectionWording() {
        assertEquals("", projectionDiagnosticWording(ProjectionDiagnosticState.IDLE_SETTINGS))
        assertEquals("", projectionDiagnosticWording(ProjectionDiagnosticState.PROJECTION_ACTIVE))
    }

    @Test
    fun cleanupClearsProjectionResourcesAndWindowState() {
        val cleared = clearProjectionRuntimeState(
            ProjectionRuntimeState(
                projectionActive = true,
                webViewHasPage = true,
                pageHealthy = true,
                mainFrameFailed = true,
                loadInFlight = true,
                windowStateApplied = true,
                screenReceiverRegistered = true,
                recoveryPending = true
            )
        )

        assertEquals(ProjectionRuntimeState(), cleared)
    }
}
