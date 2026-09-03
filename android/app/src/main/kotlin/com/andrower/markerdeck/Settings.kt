package com.andrower.markerdeck

const val MAX_DEVICE_NAME_LENGTH = 40

data class MarkerDeckSettings(
    val serviceAddress: String = "",
    val deviceName: String = ""
)

enum class SettingsField {
    SERVICE_ADDRESS,
    DEVICE_NAME
}

data class SettingsDraft(
    val serviceAddress: String = "",
    val deviceName: String = "",
    val editedFields: Set<SettingsField> = emptySet(),
    val hydrated: Boolean = false
)

fun updateSettingsDraft(
    draft: SettingsDraft,
    field: SettingsField,
    value: String
): SettingsDraft = when (field) {
    SettingsField.SERVICE_ADDRESS -> draft.copy(
        serviceAddress = value,
        editedFields = draft.editedFields + field
    )

    SettingsField.DEVICE_NAME -> draft.copy(
        deviceName = value,
        editedFields = draft.editedFields + field
    )
}

/**
 * Applies the first persisted snapshot without replacing fields that are already a user draft.
 */
fun hydrateSettingsDraft(
    draft: SettingsDraft,
    saved: MarkerDeckSettings
): SettingsDraft {
    if (draft.hydrated) return draft
    return draft.copy(
        serviceAddress = if (SettingsField.SERVICE_ADDRESS in draft.editedFields) {
            draft.serviceAddress
        } else {
            saved.serviceAddress
        },
        deviceName = if (SettingsField.DEVICE_NAME in draft.editedFields) {
            draft.deviceName
        } else {
            saved.deviceName
        },
        hydrated = true
    )
}

fun settingsDraftFromSaved(saved: MarkerDeckSettings): SettingsDraft = SettingsDraft(
    serviceAddress = saved.serviceAddress,
    deviceName = saved.deviceName,
    hydrated = true
)

fun normalizeDeviceName(value: String): String =
    value.trim().take(MAX_DEVICE_NAME_LENGTH)

fun normalizeSettings(
    serviceAddress: String,
    deviceName: String
): MarkerDeckSettings = MarkerDeckSettings(
    serviceAddress = serviceAddress.trim(),
    deviceName = normalizeDeviceName(deviceName)
)
