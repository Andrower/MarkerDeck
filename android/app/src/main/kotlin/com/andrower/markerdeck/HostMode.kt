package com.andrower.markerdeck

enum class AndroidWebMode(
    val isProjectionSurface: Boolean
) {
    NONE(false),
    REMOTE_DISPLAY(true),
    LOCAL_PROJECTION(true),
    HOST_CONTROL(false)
}

enum class EmbeddedHostMode {
    LOCAL_PROJECTION,
    LAN_HOST
}

data class EmbeddedHostSession(
    val mode: EmbeddedHostMode,
    val origin: String,
    val url: String,
    val port: Int,
    val lanAddress: String,
    val discoveryAvailable: Boolean
)
