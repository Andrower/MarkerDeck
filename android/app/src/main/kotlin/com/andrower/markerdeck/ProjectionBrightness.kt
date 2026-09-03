package com.andrower.markerdeck

const val PROJECTION_SCREEN_BRIGHTNESS = 1.0f
const val PROJECTION_SCREEN_BRIGHTNESS_NONE = -1.0f

/** Returns the per-window brightness override without touching global system settings. */
fun projectionWindowScreenBrightness(
    projectionActive: Boolean,
    webMode: AndroidWebMode
): Float = if (projectionActive && webMode.isProjectionSurface) {
    PROJECTION_SCREEN_BRIGHTNESS
} else {
    PROJECTION_SCREEN_BRIGHTNESS_NONE
}
