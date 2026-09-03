package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Test

class QrHostScanTest {
    @Test
    fun acceptsLaunchUrlAndKeepsOnlyTheServiceOrigin() {
        assertEquals(
            QrHostScanResult(
                kind = QrHostScanResultKind.ACCEPTED,
                normalizedServiceAddress = "http://192.168.1.20:8765"
            ),
            evaluateQrHostScan(
                "http://192.168.1.20:8765/markerdeck-launch.html"
            )
        )
    }

    @Test
    fun acceptsControlAndDisplayUrls() {
        assertEquals(
            "http://markerdeck.local:8765",
            evaluateQrHostScan(
                "http://MarkerDeck.local:8765/markerdeck-screen.html?mode=control"
            ).normalizedServiceAddress
        )
        assertEquals(
            "https://markerdeck.local",
            evaluateQrHostScan(
                "https://markerdeck.local/display?mode=display"
            ).normalizedServiceAddress
        )
    }

    @Test
    fun acceptsQueryUrlAndIgnoresPathQueryAndFragment() {
        assertEquals(
            "http://192.168.1.2:8765",
            evaluateQrHostScan(
                " http://192.168.1.2:8765/markerdeck-screen.html?mode=display&from=qr#top "
            ).normalizedServiceAddress
        )
    }

    @Test
    fun acceptsBareIpAndIpPort() {
        assertEquals(
            "http://192.168.1.2",
            evaluateQrHostScan("192.168.1.2").normalizedServiceAddress
        )
        assertEquals(
            "http://192.168.1.2:8765",
            evaluateQrHostScan("192.168.1.2:8765").normalizedServiceAddress
        )
    }

    @Test
    fun rejectsUnsupportedSchemeAndInvalidContent() {
        assertEquals(
            QrHostScanResultKind.INVALID,
            evaluateQrHostScan("javascript:alert(1)").kind
        )
        assertEquals(
            QrHostScanResultKind.INVALID,
            evaluateQrHostScan("file:///tmp/markerdeck-launch.html").kind
        )
        assertEquals(
            QrHostScanResultKind.INVALID,
            evaluateQrHostScan("ftp://192.168.1.2:8765").kind
        )
        assertEquals(
            QrHostScanResultKind.EMPTY,
            evaluateQrHostScan("  \n\t").kind
        )
    }

    @Test
    fun mapsCancellationAndPermissionStatesWithoutTouchingInput() {
        assertEquals(
            QrHostScanResultKind.CANCELLED,
            evaluateQrHostScan(null).kind
        )
        assertEquals(
            QrScanUiStatus.CANCELLED,
            qrScanUiStatusForResult(evaluateQrHostScan(null))
        )
        assertEquals(QrScanUiStatus.SCANNING, qrScanUiStatusForPermission(true))
        assertEquals(
            QrScanUiStatus.PERMISSION_DENIED,
            qrScanUiStatusForPermission(false)
        )
    }
}
