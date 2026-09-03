package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsInsetsTest {
    private val basePadding = SettingsContentPadding(
        start = 24,
        top = 28,
        end = 24,
        bottom = 24
    )

    @Test
    fun noSystemInsetKeepsTheLayoutPadding() {
        assertEquals(
            basePadding,
            settingsContentPaddingForTopInset(
                base = basePadding,
                statusBarsTopInset = 0,
                displayCutoutTopInset = 0
            )
        )
    }

    @Test
    fun statusBarInsetIsAddedToTheBaseTopPadding() {
        assertEquals(
            76,
            settingsContentPaddingForTopInset(
                base = basePadding,
                statusBarsTopInset = 48,
                displayCutoutTopInset = 0
            ).top
        )
    }

    @Test
    fun largerCutoutInsetWinsWithoutDroppingTheBasePadding() {
        assertEquals(
            108,
            settingsContentPaddingForTopInset(
                base = basePadding,
                statusBarsTopInset = 48,
                displayCutoutTopInset = 80
            ).top
        )
    }

    @Test
    fun recalculatingFromBasePaddingDoesNotAccumulateInsets() {
        val applied = settingsContentPaddingForTopInset(
            base = basePadding,
            statusBarsTopInset = 48,
            displayCutoutTopInset = 80
        )

        assertEquals(
            applied,
            settingsContentPaddingForTopInset(
                base = basePadding,
                statusBarsTopInset = 48,
                displayCutoutTopInset = 80
            )
        )
    }
}
