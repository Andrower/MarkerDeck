package com.andrower.markerdeck

/** Events that can change the native projection exit control surface. */
enum class ProjectionEmergencyControlEvent {
    SHOW_REQUESTED_PERSISTENT,
    SHOW_REQUESTED_HIDE_ONLY,
    SHOW_REQUESTED_WITH_RELOCK,
    HIDE_REQUESTED,
    TIMEOUT,
    PROJECTION_STOPPED
}

enum class ProjectionEmergencyControlsTimeoutBehavior {
    NONE,
    HIDE_ONLY,
    RELOCK_PROJECTION
}

data class ProjectionEmergencyControlsState(
    val visible: Boolean = false,
    val timeoutBehavior: ProjectionEmergencyControlsTimeoutBehavior =
        ProjectionEmergencyControlsTimeoutBehavior.HIDE_ONLY
)

data class ProjectionEmergencyControlsDecision(
    val state: ProjectionEmergencyControlsState,
    val shouldRelockProjection: Boolean = false
)

/**
 * Keeps emergency controls local to an active projection and makes timeout behavior explicit.
 * The Activity owns scheduling; this reducer only decides visibility and whether to relock.
 */
fun reduceProjectionEmergencyControls(
    current: ProjectionEmergencyControlsState,
    event: ProjectionEmergencyControlEvent,
    projectionActive: Boolean
): ProjectionEmergencyControlsDecision = when (event) {
    ProjectionEmergencyControlEvent.SHOW_REQUESTED_PERSISTENT -> showProjectionEmergencyControls(
        current = current,
        projectionActive = projectionActive,
        timeoutBehavior = ProjectionEmergencyControlsTimeoutBehavior.NONE
    )

    ProjectionEmergencyControlEvent.SHOW_REQUESTED_HIDE_ONLY -> showProjectionEmergencyControls(
        current = current,
        projectionActive = projectionActive,
        timeoutBehavior = ProjectionEmergencyControlsTimeoutBehavior.HIDE_ONLY
    )

    ProjectionEmergencyControlEvent.SHOW_REQUESTED_WITH_RELOCK -> showProjectionEmergencyControls(
        current = current,
        projectionActive = projectionActive,
        timeoutBehavior = ProjectionEmergencyControlsTimeoutBehavior.RELOCK_PROJECTION
    )

    ProjectionEmergencyControlEvent.HIDE_REQUESTED,
    ProjectionEmergencyControlEvent.PROJECTION_STOPPED -> ProjectionEmergencyControlsDecision(
        state = hiddenProjectionEmergencyControls(current)
    )

    ProjectionEmergencyControlEvent.TIMEOUT -> ProjectionEmergencyControlsDecision(
        state = hiddenProjectionEmergencyControls(current),
        shouldRelockProjection = projectionActive && current.visible &&
            current.timeoutBehavior == ProjectionEmergencyControlsTimeoutBehavior.RELOCK_PROJECTION
    )
}

private fun showProjectionEmergencyControls(
    current: ProjectionEmergencyControlsState,
    projectionActive: Boolean,
    timeoutBehavior: ProjectionEmergencyControlsTimeoutBehavior
): ProjectionEmergencyControlsDecision = ProjectionEmergencyControlsDecision(
    state = if (projectionActive) {
        current.copy(visible = true, timeoutBehavior = timeoutBehavior)
    } else {
        hiddenProjectionEmergencyControls(current)
    }
)

private fun hiddenProjectionEmergencyControls(
    current: ProjectionEmergencyControlsState
): ProjectionEmergencyControlsState = current.copy(
    visible = false,
    timeoutBehavior = ProjectionEmergencyControlsTimeoutBehavior.HIDE_ONLY
)
