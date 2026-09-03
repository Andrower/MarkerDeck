package com.andrower.markerdeck

data class SettingsContentPadding(
    val start: Int,
    val top: Int,
    val end: Int,
    val bottom: Int
)

fun settingsContentPaddingForTopInset(
    base: SettingsContentPadding,
    statusBarsTopInset: Int,
    displayCutoutTopInset: Int
): SettingsContentPadding = base.copy(
    top = base.top + maxOf(0, statusBarsTopInset, displayCutoutTopInset)
)
