package com.andrower.markerdeck

import java.util.Locale

enum class PermissionSettingsRoute {
    MIUI_PERMISSION_EDITOR,
    APPLICATION_DETAILS
}

enum class LockScreenPermissionStatus {
    GRANTED,
    DENIED,
    UNKNOWN,
    UNSUPPORTED
}

data class PermissionSettingsIntentSpec(
    val route: PermissionSettingsRoute,
    val action: String,
    val packageName: String,
    val dataUri: String? = null,
    val componentPackage: String? = null,
    val componentClass: String? = null,
    val packageExtraName: String? = null
)

const val MIUI_PERMISSION_EDITOR_ACTION = "miui.intent.action.APP_PERM_EDITOR"
const val MIUI_PERMISSION_EDITOR_PACKAGE = "com.miui.securitycenter"
const val MIUI_PERMISSION_EDITOR_ACTIVITY =
    "com.miui.permcenter.permissions.PermissionsEditorActivity"
const val MIUI_PERMISSION_EDITOR_PACKAGE_EXTRA = "extra_pkgname"
const val APPLICATION_DETAILS_SETTINGS_ACTION = "android.settings.APPLICATION_DETAILS_SETTINGS"

/**
 * The Android SDK does not expose the OEM "show on lock screen" app-op used by MIUI.
 * Keep that state explicitly unknown instead of treating a failed probe as granted or denied.
 */
fun lockScreenPermissionStatusForDevice(
    manufacturer: String?,
    brand: String?,
    apiLevel: Int
): LockScreenPermissionStatus = when {
    apiLevel < 27 -> LockScreenPermissionStatus.UNSUPPORTED
    isXiaomiFamilyDevice(manufacturer, brand) -> LockScreenPermissionStatus.UNKNOWN
    else -> LockScreenPermissionStatus.UNSUPPORTED
}

fun shouldShowLockScreenPermissionGuide(
    settingsVisible: Boolean,
    permissionStatus: LockScreenPermissionStatus,
    guideHandled: Boolean,
    shownInCurrentActivity: Boolean
): Boolean = settingsVisible &&
    !guideHandled &&
    !shownInCurrentActivity &&
    permissionStatus != LockScreenPermissionStatus.GRANTED

fun isXiaomiFamilyDevice(manufacturer: String?, brand: String?): Boolean {
    return listOf(manufacturer, brand)
        .filterNotNull()
        .map { it.trim().lowercase(Locale.ROOT) }
        .any { it == "xiaomi" || it == "redmi" || it == "poco" }
}

fun miuiPermissionSettingsIntentSpec(packageName: String): PermissionSettingsIntentSpec =
    PermissionSettingsIntentSpec(
        route = PermissionSettingsRoute.MIUI_PERMISSION_EDITOR,
        action = MIUI_PERMISSION_EDITOR_ACTION,
        packageName = packageName,
        componentPackage = MIUI_PERMISSION_EDITOR_PACKAGE,
        componentClass = MIUI_PERMISSION_EDITOR_ACTIVITY,
        packageExtraName = MIUI_PERMISSION_EDITOR_PACKAGE_EXTRA
    )

fun applicationDetailsIntentSpec(packageName: String): PermissionSettingsIntentSpec =
    PermissionSettingsIntentSpec(
        route = PermissionSettingsRoute.APPLICATION_DETAILS,
        action = APPLICATION_DETAILS_SETTINGS_ACTION,
        packageName = packageName,
        dataUri = "package:$packageName"
    )

fun selectPermissionSettingsIntent(
    manufacturer: String?,
    brand: String?,
    miuiEditorAvailable: Boolean,
    packageName: String
): PermissionSettingsIntentSpec {
    return if (isXiaomiFamilyDevice(manufacturer, brand) && miuiEditorAvailable) {
        miuiPermissionSettingsIntentSpec(packageName)
    } else {
        applicationDetailsIntentSpec(packageName)
    }
}
