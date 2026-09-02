package com.andrower.markerdeck

import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

/**
 * Builds the first display URL without opening a connection or requiring Android UI state.
 */
fun buildDisplayUrl(serviceAddress: String): String {
    val normalizedAddress = normalizeServiceAddress(serviceAddress)
    return "$normalizedAddress/markerdeck-screen.html?mode=display"
}

/**
 * Keeps only the HTTP(S) origin used by the future display client.
 */
fun normalizeServiceAddress(serviceAddress: String): String {
    val input = serviceAddress.trim()
    require(input.isNotEmpty()) { "Service address must not be empty." }

    val uri = try {
        URI(input)
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
