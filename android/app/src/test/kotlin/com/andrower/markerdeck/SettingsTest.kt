package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    @Test
    fun normalizesSettingsFieldsAndKeepsOrdinaryModeExplicit() {
        val settings = normalizeSettings(
            serviceAddress = "  http://192.168.1.20:8765/  ",
            deviceName = "  Entrance Screen  "
        )

        assertEquals("http://192.168.1.20:8765/", settings.serviceAddress)
        assertEquals("Entrance Screen", settings.deviceName)
        assertEquals(DisplayMode.ORDINARY_DISPLAY, settings.mode)
    }

    @Test
    fun trimsDeviceNameToTheWebpageLimit() {
        assertEquals(
            MAX_DEVICE_NAME_LENGTH,
            normalizeDeviceName("x".repeat(MAX_DEVICE_NAME_LENGTH + 5)).length
        )
    }

    @Test
    fun unknownStoredModeFallsBackToOrdinaryDisplay() {
        assertEquals(DisplayMode.ORDINARY_DISPLAY, DisplayMode.fromStorage("kiosk"))
        assertEquals(DisplayMode.ORDINARY_DISPLAY, DisplayMode.fromStorage(null))
    }

    @Test
    fun delayedHydrationRestoresOnlyFieldsThatWereNotEdited() {
        val draft = updateSettingsDraft(
            updateSettingsDraft(SettingsDraft(), SettingsField.SERVICE_ADDRESS, "http://edited.local"),
            SettingsField.DEVICE_NAME,
            "Edited screen"
        )
        val saved = MarkerDeckSettings(
            serviceAddress = "http://saved.local",
            deviceName = "Saved screen",
            mode = DisplayMode.ORDINARY_DISPLAY
        )

        val hydrated = hydrateSettingsDraft(draft, saved)

        assertEquals("http://edited.local", hydrated.serviceAddress)
        assertEquals("Edited screen", hydrated.deviceName)
        assertEquals(DisplayMode.ORDINARY_DISPLAY, hydrated.mode)
        assertTrue(hydrated.hydrated)
    }

    @Test
    fun delayedHydrationFillsUneditedFieldsAndDoesNotRefreshAfterFirstSnapshot() {
        val draft = updateSettingsDraft(SettingsDraft(), SettingsField.SERVICE_ADDRESS, "http://edited.local")
        val firstSaved = MarkerDeckSettings(
            serviceAddress = "http://saved.local",
            deviceName = "Saved screen",
            mode = DisplayMode.ORDINARY_DISPLAY
        )
        val secondSaved = MarkerDeckSettings(
            serviceAddress = "http://newer.local",
            deviceName = "Newer screen",
            mode = DisplayMode.ORDINARY_DISPLAY
        )

        val hydrated = hydrateSettingsDraft(draft, firstSaved)
        val unchanged = hydrateSettingsDraft(hydrated, secondSaved)

        assertEquals("http://edited.local", unchanged.serviceAddress)
        assertEquals("Saved screen", unchanged.deviceName)
        assertEquals(firstSaved.mode, unchanged.mode)
        assertFalse(SettingsField.DEVICE_NAME in unchanged.editedFields)
    }

    @Test
    fun draftFromSavedKeepsModeAndValuesTogether() {
        val saved = MarkerDeckSettings(
            serviceAddress = "http://saved.local",
            deviceName = "Saved screen",
            mode = DisplayMode.ORDINARY_DISPLAY
        )

        val draft = settingsDraftFromSaved(saved)

        assertEquals(saved.serviceAddress, draft.serviceAddress)
        assertEquals(saved.deviceName, draft.deviceName)
        assertEquals(saved.mode, draft.mode)
        assertTrue(draft.hydrated)
    }
}
