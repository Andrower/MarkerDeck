package com.andrower.markerdeck

import java.net.URI
import java.net.URISyntaxException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale

const val WEBVIEW_CLEANUP_URL = "about:blank"

/**
 * Builds the display URL without opening a connection or requiring Android UI state.
 */
fun buildDisplayUrl(serviceAddress: String, deviceName: String = ""): String {
    return buildWebModeUrl(serviceAddress, AndroidWebMode.REMOTE_DISPLAY, deviceName)
}

fun buildLocalProjectionUrl(serviceAddress: String): String =
    buildWebModeUrl(serviceAddress, AndroidWebMode.LOCAL_PROJECTION)

fun buildHostControlUrl(serviceAddress: String): String =
    buildWebModeUrl(serviceAddress, AndroidWebMode.HOST_CONTROL)

fun buildWebModeUrl(
    serviceAddress: String,
    mode: AndroidWebMode,
    deviceName: String = ""
): String {
    require(mode == AndroidWebMode.REMOTE_DISPLAY ||
        mode == AndroidWebMode.LOCAL_PROJECTION || mode == AndroidWebMode.HOST_CONTROL
    ) { "WebView mode must be a display, local, or control mode." }
    val normalizedAddress = normalizeServiceAddress(serviceAddress)
    val normalizedName = normalizeDeviceName(deviceName)
    val nameSuffix = if (normalizedName.isEmpty()) {
        ""
    } else {
        "&androidDeviceName=${encodeQueryValue(normalizedName)}"
    }
    val queryMode = when (mode) {
        AndroidWebMode.LOCAL_PROJECTION -> "local"
        AndroidWebMode.HOST_CONTROL -> "control"
        else -> "display"
    }
    return "$normalizedAddress/markerdeck-screen.html?mode=$queryMode$nameSuffix"
}

/**
 * Keeps only the HTTP(S) origin used by the display client.
 */
fun normalizeServiceAddress(serviceAddress: String): String {
    val input = serviceAddress.trim()
    require(input.isNotEmpty()) { "Service address must not be empty." }

    val uriInput = when {
        input.matches(Regex("^[A-Za-z][A-Za-z0-9+.-]*://.*")) -> input
        input.contains("://") -> input
        else -> "http://$input"
    }
    val uri = try {
        URI(uriInput)
    } catch (error: URISyntaxException) {
        throw IllegalArgumentException("Service address is not a valid URI.", error)
    }

    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") {
        "Service address must use http or https."
    }
    val rawHost = uri.host ?: throw IllegalArgumentException("Service address must include a host.")
    require(rawHost.isNotBlank()) { "Service address must include a host." }
    require(uri.userInfo == null) { "Service address must not include user information." }
    require(uri.rawAuthority?.endsWith(":") != true) {
        "Service address port must be between 1 and 65535."
    }

    val port = uri.port
    require(port == -1 || port in 1..65535) {
        "Service address port must be between 1 and 65535."
    }

    val host = rawHost.lowercase(Locale.ROOT).let { value ->
        if (value.contains(":") && !value.startsWith("[")) "[$value]" else value
    }
    val portSuffix = if (port == -1) "" else ":$port"
    return "$scheme://$host$portSuffix"
}

private fun encodeQueryValue(value: String): String =
    URLEncoder.encode(value, StandardCharsets.UTF_8.name()).replace("+", "%20")

/**
 * Checks a top-level WebView URL against the configured HTTP(S) origin.
 * Same-origin paths remain valid; subresource requests are handled by WebView itself.
 */
fun isAllowedTopLevelNavigation(
    url: String,
    allowedOrigin: String,
    allowCleanup: Boolean = false
): Boolean {
    val candidate = url.trim()
    if (candidate == WEBVIEW_CLEANUP_URL) return allowCleanup
    return try {
        canonicalOrigin(candidate) == canonicalOrigin(allowedOrigin)
    } catch (_: IllegalArgumentException) {
        false
    }
}

private fun canonicalOrigin(serviceAddress: String): String {
    val normalized = normalizeServiceAddress(serviceAddress)
    val uri = URI(normalized)
    val scheme = uri.scheme.lowercase(Locale.ROOT)
    val rawHost = uri.host ?: throw IllegalArgumentException("Service address must include a host.")
    val host = rawHost.lowercase(Locale.ROOT).let { value ->
        if (value.contains(":") && !value.startsWith("[")) "[$value]" else value
    }
    val port = if (uri.port == -1) {
        if (scheme == "http") 80 else 443
    } else {
        uri.port
    }
    return "$scheme://$host:$port"
}
