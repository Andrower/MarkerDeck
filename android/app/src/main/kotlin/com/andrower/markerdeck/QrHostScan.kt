package com.andrower.markerdeck

enum class QrHostScanResultKind {
    ACCEPTED,
    CANCELLED,
    EMPTY,
    INVALID
}

data class QrHostScanResult(
    val kind: QrHostScanResultKind,
    val normalizedServiceAddress: String? = null
)

enum class QrScanUiStatus {
    REQUESTING_PERMISSION,
    SCANNING,
    SUCCESS,
    CANCELLED,
    EMPTY,
    INVALID,
    FAILED,
    PERMISSION_DENIED,
    NO_CAMERA
}

/**
 * Converts a scanner payload into the same origin format used by manual input.
 * No network request or navigation is performed here.
 */
fun evaluateQrHostScan(rawValue: String?): QrHostScanResult {
    if (rawValue == null) return QrHostScanResult(QrHostScanResultKind.CANCELLED)
    if (rawValue.trim().isEmpty()) return QrHostScanResult(QrHostScanResultKind.EMPTY)

    return try {
        QrHostScanResult(
            kind = QrHostScanResultKind.ACCEPTED,
            normalizedServiceAddress = normalizeServiceAddress(rawValue)
        )
    } catch (_: IllegalArgumentException) {
        QrHostScanResult(QrHostScanResultKind.INVALID)
    }
}

fun qrScanUiStatusForPermission(granted: Boolean): QrScanUiStatus =
    if (granted) QrScanUiStatus.SCANNING else QrScanUiStatus.PERMISSION_DENIED

fun qrScanUiStatusForResult(result: QrHostScanResult): QrScanUiStatus = when (result.kind) {
    QrHostScanResultKind.ACCEPTED -> QrScanUiStatus.SUCCESS
    QrHostScanResultKind.CANCELLED -> QrScanUiStatus.CANCELLED
    QrHostScanResultKind.EMPTY -> QrScanUiStatus.EMPTY
    QrHostScanResultKind.INVALID -> QrScanUiStatus.INVALID
}
