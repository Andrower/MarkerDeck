package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionSettingsTest {
    @Test
    fun selectsTheXiaomiPermissionEditorWhenItIsAvailable() {
        val spec = selectPermissionSettingsIntent(
            manufacturer = "Xiaomi",
            brand = "xiaomi",
            miuiEditorAvailable = true,
            packageName = "com.andrower.markerdeck"
        )

        assertEquals(PermissionSettingsRoute.MIUI_PERMISSION_EDITOR, spec.route)
        assertEquals(MIUI_PERMISSION_EDITOR_ACTION, spec.action)
        assertEquals(MIUI_PERMISSION_EDITOR_PACKAGE, spec.componentPackage)
        assertEquals(MIUI_PERMISSION_EDITOR_ACTIVITY, spec.componentClass)
        assertEquals(MIUI_PERMISSION_EDITOR_PACKAGE_EXTRA, spec.packageExtraName)
        assertNull(spec.dataUri)
    }

    @Test
    fun fallsBackWhenTheMiuiPermissionEditorCannotBeResolved() {
        val spec = selectPermissionSettingsIntent(
            manufacturer = "Xiaomi",
            brand = "POCO",
            miuiEditorAvailable = false,
            packageName = "com.andrower.markerdeck"
        )

        assertEquals(PermissionSettingsRoute.APPLICATION_DETAILS, spec.route)
        assertEquals(APPLICATION_DETAILS_SETTINGS_ACTION, spec.action)
        assertEquals("package:com.andrower.markerdeck", spec.dataUri)
        assertNull(spec.componentPackage)
    }

    @Test
    fun nonXiaomiDevicesAlwaysUseApplicationDetails() {
        val spec = selectPermissionSettingsIntent(
            manufacturer = "Google",
            brand = "Pixel",
            miuiEditorAvailable = true,
            packageName = "com.andrower.markerdeck"
        )

        assertEquals(PermissionSettingsRoute.APPLICATION_DETAILS, spec.route)
        assertTrue(spec.dataUri!!.startsWith("package:"))
    }
}
