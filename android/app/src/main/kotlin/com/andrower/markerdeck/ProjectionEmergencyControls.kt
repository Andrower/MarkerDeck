package com.andrower.markerdeck

/** Events that can change the short-lived native emergency control surface. */
enum class ProjectionEmergencyControlEvent {
    SHOW_REQUESTED,
    HIDE_REQUESTED,
    TIMEOUT,
    PROJECTION_STOPPED
}

data class ProjectionEmergencyControlsState(
    val visible: Boolean = false
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
    ProjectionEmergencyControlEvent.SHOW_REQUESTED -> ProjectionEmergencyControlsDecision(
        state = current.copy(visible = projectionActive)
    )

    ProjectionEmergencyControlEvent.HIDE_REQUESTED,
    ProjectionEmergencyControlEvent.PROJECTION_STOPPED -> ProjectionEmergencyControlsDecision(
        state = current.copy(visible = false)
    )

    ProjectionEmergencyControlEvent.TIMEOUT -> ProjectionEmergencyControlsDecision(
        state = current.copy(visible = false),
        shouldRelockProjection = projectionActive && current.visible
    )
}
