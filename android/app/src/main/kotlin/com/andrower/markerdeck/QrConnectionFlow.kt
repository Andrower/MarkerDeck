package com.andrower.markerdeck

enum class QrConnectionStep {
    IDLE,
    HOST_CONFIRMATION,
    DEVICE_NAME_CONFIRMATION,
    CONNECTING
}

/**
 * Ephemeral state for the two-step host connection flow. The same state machine is also used by
 * automatic LAN discovery so QR and mDNS do not create separate connection behavior.
 */
data class QrConnectionFlowState(
    val sessionId: Long = 0L,
    val step: QrConnectionStep = QrConnectionStep.IDLE,
    val normalizedServiceAddress: String? = null,
    val hostName: String? = null,
    val initialDeviceName: String = "",
    val confirmedDeviceName: String? = null
)

fun beginQrConnectionFlow(
    current: QrConnectionFlowState,
    normalizedServiceAddress: String,
    initialDeviceName: String
): QrConnectionFlowState? {
    if (current.step != QrConnectionStep.IDLE) return null
    require(normalizedServiceAddress.isNotBlank()) {
        "QR connection address must not be empty."
    }
    return QrConnectionFlowState(
        sessionId = current.sessionId + 1,
        step = QrConnectionStep.HOST_CONFIRMATION,
        normalizedServiceAddress = normalizedServiceAddress,
        initialDeviceName = normalizeDeviceName(initialDeviceName)
    )
}

fun updateQrConnectionHostName(
    current: QrConnectionFlowState,
    sessionId: Long,
    hostName: String
): QrConnectionFlowState {
    if (current.sessionId != sessionId || current.step != QrConnectionStep.HOST_CONFIRMATION) {
        return current
    }
    return current.copy(hostName = hostName)
}

fun confirmQrConnectionHost(
    current: QrConnectionFlowState,
    sessionId: Long
): QrConnectionFlowState? {
    if (current.sessionId != sessionId || current.step != QrConnectionStep.HOST_CONFIRMATION) {
        return null
    }
    if (current.normalizedServiceAddress.isNullOrBlank()) return null
    return current.copy(step = QrConnectionStep.DEVICE_NAME_CONFIRMATION)
}

fun confirmQrConnectionDeviceName(
    current: QrConnectionFlowState,
    sessionId: Long,
    rawDeviceName: String
): QrConnectionFlowState? {
    if (current.sessionId != sessionId || current.step != QrConnectionStep.DEVICE_NAME_CONFIRMATION) {
        return null
    }
    val normalizedName = normalizeDeviceName(rawDeviceName)
    if (normalizedName.isEmpty()) return null
    return current.copy(
        step = QrConnectionStep.CONNECTING,
        confirmedDeviceName = normalizedName
    )
}

fun cancelQrConnectionFlow(
    current: QrConnectionFlowState,
    sessionId: Long
): QrConnectionFlowState {
    if (current.sessionId != sessionId) return current
    return current.copy(
        step = QrConnectionStep.IDLE,
        normalizedServiceAddress = null,
        hostName = null,
        initialDeviceName = "",
        confirmedDeviceName = null
    )
}
