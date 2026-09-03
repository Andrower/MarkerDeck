package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LockScreenPermissionGuideTest {
    @Test
    fun firstSettingsEntryShowsGuideWhenStatusIsUnknown() {
        assertTrue(
            shouldShowLockScreenPermissionGuide(
                settingsVisible = true,
                permissionStatus = LockScreenPermissionStatus.UNKNOWN,
                guideHandled = false,
                shownInCurrentActivity = false
            )
        )
    }

    @Test
    fun firstSettingsEntryShowsGentleGuideWhenStatusIsUnsupported() {
        assertTrue(
            shouldShowLockScreenPermissionGuide(
                settingsVisible = true,
                permissionStatus = LockScreenPermissionStatus.UNSUPPORTED,
                guideHandled = false,
                shownInCurrentActivity = false
            )
        )
    }

    @Test
    fun handledOrAlreadyShownGuideDoesNotRepeat() {
        assertFalse(
            shouldShowLockScreenPermissionGuide(
                settingsVisible = true,
                permissionStatus = LockScreenPermissionStatus.UNKNOWN,
                guideHandled = true,
                shownInCurrentActivity = false
            )
        )
        assertFalse(
            shouldShowLockScreenPermissionGuide(
                settingsVisible = true,
                permissionStatus = LockScreenPermissionStatus.UNKNOWN,
                guideHandled = false,
                shownInCurrentActivity = true
            )
        )
        assertFalse(
            shouldShowLockScreenPermissionGuide(
                settingsVisible = false,
                permissionStatus = LockScreenPermissionStatus.UNKNOWN,
                guideHandled = false,
                shownInCurrentActivity = false
            )
        )
    }

    @Test
    fun grantedStatusDoesNotShowGuide() {
        assertFalse(
            shouldShowLockScreenPermissionGuide(
                settingsVisible = true,
                permissionStatus = LockScreenPermissionStatus.GRANTED,
                guideHandled = false,
                shownInCurrentActivity = false
            )
        )
    }

    @Test
    fun deviceStatusUsesUnknownForXiaomiAndUnsupportedForOlderOrOtherSystems() {
        assertEquals(
            LockScreenPermissionStatus.UNKNOWN,
            lockScreenPermissionStatusForDevice("Xiaomi", "MIUI", 35)
        )
        assertEquals(
            LockScreenPermissionStatus.UNSUPPORTED,
            lockScreenPermissionStatusForDevice("Google", "Pixel", 35)
        )
        assertEquals(
            LockScreenPermissionStatus.UNSUPPORTED,
            lockScreenPermissionStatusForDevice("Xiaomi", "MIUI", 26)
        )
    }
}
