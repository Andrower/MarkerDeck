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

enum class EmbeddedHostServiceState {
    STOPPED,
    RUNNING
}

data class EmbeddedHostStatus(
    val state: EmbeddedHostServiceState,
    val session: EmbeddedHostSession? = null
) {
    val isRunning: Boolean
        get() = state == EmbeddedHostServiceState.RUNNING
}

fun embeddedHostStatus(session: EmbeddedHostSession?): EmbeddedHostStatus =
    if (session == null) {
        EmbeddedHostStatus(EmbeddedHostServiceState.STOPPED)
    } else {
        EmbeddedHostStatus(EmbeddedHostServiceState.RUNNING, session)
    }

fun shouldEnableStopEmbeddedHost(status: EmbeddedHostStatus): Boolean = status.isRunning
