package com.andrower.markerdeck

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val QR_HOST_INFO_CONNECT_TIMEOUT_MS = 700
private const val QR_HOST_INFO_READ_TIMEOUT_MS = 900
private const val QR_HOST_INFO_BODY_LIMIT = 16 * 1024

/** Builds the optional same-origin host metadata request used by QR and LAN confirmation. */
fun buildQrHostInfoUrl(serviceAddress: String): String {
    val normalizedAddress = normalizeServiceAddress(serviceAddress)
    return "$normalizedAddress/api/info"
}

/** Extracts a displayable host name only from compliant JSON text. */
fun parseQrHostInfoName(payload: String): String? {
    return try {
        val name = JSONObject(payload).opt("name") as? String ?: return null
        val trimmed = name.trim()
        trimmed.takeIf {
            it.isNotEmpty() &&
                it.length <= MARKERDECK_HOST_MAX_NAME_LENGTH &&
                it.none(Char::isISOControl)
        }
    } catch (_: Exception) {
        null
    }
}

/** Best-effort metadata lookup; connection flow still works when the endpoint is unavailable. */
fun fetchQrHostInfoName(serviceAddress: String): String? {
    val normalizedAddress = try {
        normalizeServiceAddress(serviceAddress)
    } catch (_: IllegalArgumentException) {
        return null
    }
    val connection = try {
        URL(buildQrHostInfoUrl(normalizedAddress)).openConnection() as? HttpURLConnection
    } catch (_: Exception) {
        null
    } ?: return null

    return try {
        connection.apply {
            requestMethod = "GET"
            connectTimeout = QR_HOST_INFO_CONNECT_TIMEOUT_MS
            readTimeout = QR_HOST_INFO_READ_TIMEOUT_MS
            useCaches = false
            instanceFollowRedirects = false
            doInput = true
            setRequestProperty("Accept", "application/json")
        }
        val responseCode = connection.responseCode
        if (!isAllowedTopLevelNavigation(connection.url.toString(), normalizedAddress)) return null
        if (responseCode !in 200..299) return null
        if (connection.contentLengthLong > QR_HOST_INFO_BODY_LIMIT) return null
        val body = readQrHostInfoBody(connection.inputStream) ?: return null
        parseQrHostInfoName(body.toString(Charsets.UTF_8))
    } catch (_: Exception) {
        null
    } finally {
        connection.disconnect()
    }
}

private fun readQrHostInfoBody(input: java.io.InputStream): ByteArray? {
    input.use { stream ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(1024)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) break
            if (output.size() + count > QR_HOST_INFO_BODY_LIMIT) return null
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }
}
