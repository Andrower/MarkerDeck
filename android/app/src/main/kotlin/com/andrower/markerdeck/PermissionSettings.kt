package com.andrower.markerdeck

import java.util.Locale

enum class PermissionSettingsRoute {
    MIUI_PERMISSION_EDITOR,
    APPLICATION_DETAILS
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
