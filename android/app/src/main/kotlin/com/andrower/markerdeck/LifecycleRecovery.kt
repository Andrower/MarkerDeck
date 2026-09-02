package com.andrower.markerdeck

/**
 * State used by the Activity to decide whether a projection page needs a reload.
 */
data class DisplayPageState(
    val projectionActive: Boolean,
    val pageHealthy: Boolean,
    val mainFrameFailed: Boolean,
    val loadInFlight: Boolean
)

fun shouldReloadDisplayPage(state: DisplayPageState): Boolean =
    state.projectionActive &&
        !state.loadInFlight &&
        (!state.pageHealthy || state.mainFrameFailed)

fun shouldFinishBeforeShowingSettings(
    projectionActive: Boolean,
    isKeyguardLocked: Boolean
): Boolean = projectionActive && isKeyguardLocked

enum class BackNavigationDecision {
    CONSUME,
    FINISH
}

fun backNavigationDecision(projectionActive: Boolean): BackNavigationDecision =
    if (projectionActive) BackNavigationDecision.CONSUME else BackNavigationDecision.FINISH

/** Window flags and immersive mode are valid only for the active projection surface. */
fun shouldApplyDisplayWindowState(projectionActive: Boolean): Boolean = projectionActive

data class SavedProjection(
    val serviceAddress: String,
    val deviceName: String
)

/**
 * Validates only an explicitly saved active projection. An absent or malformed snapshot
 * falls back to settings and never becomes a cold-start connection.
 */
fun validateSavedProjection(
    projectionActive: Boolean,
    serviceAddress: String?,
    deviceName: String?
): SavedProjection? {
    if (!projectionActive) return null

    val normalizedAddress = try {
        normalizeServiceAddress(serviceAddress.orEmpty())
    } catch (_: IllegalArgumentException) {
        return null
    }
    val normalizedName = normalizeDeviceName(deviceName.orEmpty())
    if (normalizedName.isEmpty()) return null

    return SavedProjection(
        serviceAddress = normalizedAddress,
        deviceName = normalizedName
    )
}

enum class ProjectionDiagnosticState {
    IDLE_SETTINGS,
    PROJECTION_ACTIVE,
    SCREEN_ON_RECOVERY,
    RENDERER_RECOVERY,
    PROJECTION_FAILURE,
    DEGRADED_RECOVERY_FAILURE
}

enum class ProjectionDiagnosticVisibility {
    HIDDEN,
    TRANSIENT,
    PERSISTENT
}

fun shouldShowProjectionDiagnostic(
    projectionActive: Boolean,
    projectionState: ProjectionDiagnosticState,
    pageHealthy: Boolean
): Boolean {
    if (!projectionActive || pageHealthy) return false

    return when (projectionState) {
        ProjectionDiagnosticState.SCREEN_ON_RECOVERY,
        ProjectionDiagnosticState.RENDERER_RECOVERY,
        ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE -> true

        ProjectionDiagnosticState.IDLE_SETTINGS,
        ProjectionDiagnosticState.PROJECTION_ACTIVE,
        ProjectionDiagnosticState.PROJECTION_FAILURE -> false
    }
}

fun projectionDiagnosticVisibility(
    projectionActive: Boolean,
    projectionState: ProjectionDiagnosticState,
    pageHealthy: Boolean
): ProjectionDiagnosticVisibility {
    if (!shouldShowProjectionDiagnostic(projectionActive, projectionState, pageHealthy)) {
        return ProjectionDiagnosticVisibility.HIDDEN
    }

    return when (projectionState) {
        ProjectionDiagnosticState.SCREEN_ON_RECOVERY,
        ProjectionDiagnosticState.RENDERER_RECOVERY -> ProjectionDiagnosticVisibility.TRANSIENT

        ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE ->
            ProjectionDiagnosticVisibility.PERSISTENT

        ProjectionDiagnosticState.IDLE_SETTINGS,
        ProjectionDiagnosticState.PROJECTION_ACTIVE,
        ProjectionDiagnosticState.PROJECTION_FAILURE -> ProjectionDiagnosticVisibility.HIDDEN
    }
}

fun shouldShowScreenReceiverWarning(
    projectionActive: Boolean,
    screenReceiverRegistered: Boolean
): Boolean = projectionActive && !screenReceiverRegistered

fun projectionDiagnosticWording(state: ProjectionDiagnosticState): String = when (state) {
        ProjectionDiagnosticState.IDLE_SETTINGS,
        ProjectionDiagnosticState.PROJECTION_ACTIVE -> ""

        ProjectionDiagnosticState.SCREEN_ON_RECOVERY -> "屏幕已点亮，正在恢复普通投放。"
        ProjectionDiagnosticState.RENDERER_RECOVERY -> "WebView 渲染进程已退出，正在重建投放页面。"
        ProjectionDiagnosticState.PROJECTION_FAILURE -> "普通投放页面不可用。"
        ProjectionDiagnosticState.DEGRADED_RECOVERY_FAILURE -> "普通投放恢复失败，当前处于降级状态。"
}

data class ProjectionRuntimeState(
    val projectionActive: Boolean = false,
    val webViewHasPage: Boolean = false,
    val pageHealthy: Boolean = false,
    val mainFrameFailed: Boolean = false,
    val loadInFlight: Boolean = false,
    val windowStateApplied: Boolean = false,
    val screenReceiverRegistered: Boolean = false,
    val recoveryPending: Boolean = false
)

/**
 * The settings and destroy paths use the same cleanup decision so no projection resource
 * remains logically active after the user leaves the display.
 */
fun clearProjectionRuntimeState(state: ProjectionRuntimeState): ProjectionRuntimeState =
    state.copy(
        projectionActive = false,
        webViewHasPage = false,
        pageHealthy = false,
        mainFrameFailed = false,
        loadInFlight = false,
        windowStateApplied = false,
        screenReceiverRegistered = false,
        recoveryPending = false
    )
