package com.andrower.markerdeck

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

const val MARKERDECK_HOST_DEFAULT_PORT = 8765
const val MARKERDECK_HOST_MAX_BODY_BYTES = 64 * 1024
const val MARKERDECK_HOST_OFFLINE_MS = 5_000L
const val MARKERDECK_HOST_DEFAULT_RETENTION_MS = 10 * 60 * 1000L
const val MARKERDECK_HOST_MAX_NAME_LENGTH = 40
const val MARKERDECK_HOST_MAX_ID_LENGTH = 80
const val MARKERDECK_HOST_LAUNCH_PAGE = "/markerdeck-launch.html"
const val MARKERDECK_HOST_SCREEN_PAGE = "/markerdeck-screen.html"

val DEFAULT_HOST_STATE: Map<String, String> = linkedMapOf(
    "bgColor" to "#00ff00",
    "bgBrightness" to "100",
    "overallBrightness" to "100",
    "crossColor" to "#0040d8",
    "crossBrightness" to "100",
    "crossSize" to "6",
    "crossThickness" to "1.4",
    "edgeRatio" to "10",
    "centerY" to "50",
    "hideCross" to "0",
    "randomPoints" to "0",
    "randomPointCount" to "12",
    "randomSeed" to "",
    "forceLock" to "0",
    "displayLocked" to "0",
    "lockCommand" to "none",
    "lockCommandId" to "0"
)

data class HostCapabilities(
    val videoExport: Boolean = false,
    val pngExport: Boolean = true,
    val sse: Boolean = true,
    val udpDiscovery: Boolean = false,
    val hostDiscovery: Boolean = false,
    val mdnsDiscovery: Boolean = false
) {
    fun toJson(): JSONObject = JSONObject()
        .put("videoExport", videoExport)
        .put("pngExport", pngExport)
        .put("sse", sse)
        .put("udpDiscovery", udpDiscovery)
        .put("hostDiscovery", hostDiscovery)
        .put("mdnsDiscovery", mdnsDiscovery)
}

data class HostInfo(
    val ip: String,
    val port: Int,
    val url: String,
    val discoveryPort: Int,
    val protocolVersion: Int,
    val name: String,
    val capabilities: HostCapabilities
) {
    fun toJson(): JSONObject = JSONObject()
        .put("ip", ip)
        .put("port", port)
        .put("url", url)
        .put("discoveryPort", discoveryPort)
        .put("protocolVersion", protocolVersion)
        .put("name", name)
        .put("capabilities", capabilities.toJson())
}

fun buildHostInfo(
    ip: String,
    port: Int,
    name: String = "MarkerDeck",
    capabilities: HostCapabilities = HostCapabilities()
): HostInfo {
    val cleanIp = ip.trim().ifEmpty { "127.0.0.1" }
    val cleanName = normalizeHostName(name)
    return HostInfo(
        ip = cleanIp,
        port = port,
        url = "http://$cleanIp:$port$MARKERDECK_HOST_LAUNCH_PAGE",
        discoveryPort = MARKERDECK_DISCOVERY_PORT,
        protocolVersion = MARKERDECK_DISCOVERY_PROTOCOL_VERSION,
        name = cleanName,
        capabilities = capabilities
    )
}

fun normalizeHostName(value: String): String =
    value.trim().take(MARKERDECK_HOST_MAX_NAME_LENGTH).ifEmpty { "MarkerDeck" }

fun normalizeDeviceRetentionMs(value: Long): Long = when {
    value == 0L -> 0L
    value < 0L -> MARKERDECK_HOST_DEFAULT_RETENTION_MS
    else -> value.coerceIn(30_000L, 7 * 24 * 60 * 60 * 1000L)
}

private fun brightnessPercent(value: Any?, fallback: Double = 100.0): Double {
    val fallbackValue = fallback.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: 100.0
    val numeric = value?.toString()?.trim()?.toDoubleOrNull() ?: return fallbackValue
    return numeric.takeIf { it.isFinite() }?.coerceIn(0.0, 100.0) ?: fallbackValue
}

fun normalizeOverallBrightness(value: Any?): String {
    return brightnessPercent(value).roundToInt().toString()
}

private fun scaleHexColor(hex: Any?, brightness: Any?): String {
    val source = hex?.toString()?.trim().orEmpty()
    val match = Regex("^#([0-9a-f]{6})$", RegexOption.IGNORE_CASE).matchEntire(source)
        ?: return source
    val level = brightnessPercent(brightness)
    if (level == 100.0) return source
    val color = match.groupValues[1]
    val values = listOf(0, 2, 4).map { offset ->
        color.substring(offset, offset + 2).toInt(16)
    }
    return "#" + values.joinToString("") { value ->
        "%02x".format(Locale.ROOT, (value * level / 100.0).roundToInt())
    }
}

fun normalizeHostState(next: Map<String, *>?): Map<String, String> {
    val normalized = LinkedHashMap(DEFAULT_HOST_STATE)
    next.orEmpty().forEach { (key, value) ->
        if (key !in DEFAULT_HOST_STATE || value == null || value === JSONObject.NULL) return@forEach
        normalized[key] = when (value) {
            is Boolean -> if (value) "1" else "0"
            else -> value.toString()
        }
    }
    normalized["bgColor"] = scaleHexColor(normalized["bgColor"], normalized["bgBrightness"])
    normalized["crossColor"] = scaleHexColor(normalized["crossColor"], normalized["crossBrightness"])
    normalized["bgBrightness"] = "100"
    normalized["crossBrightness"] = "100"
    normalized["overallBrightness"] = normalizeOverallBrightness(normalized["overallBrightness"])
    return normalized
}

fun jsonObjectToValueMap(value: JSONObject?): Map<String, Any?> {
    if (value == null) return emptyMap()
    val result = LinkedHashMap<String, Any?>()
    val keys = value.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        result[key] = value.opt(key)
    }
    return result
}

fun hostStateToJson(state: Map<String, String>): JSONObject {
    val json = JSONObject()
    normalizeHostState(state).forEach { (key, value) -> json.put(key, value) }
    return json
}

data class HostPreset(
    val id: String,
    val name: String,
    val state: Map<String, String>
)

fun cleanHostPreset(preset: HostPreset, fallbackId: String): HostPreset? {
    val name = preset.name.trim().take(MARKERDECK_HOST_MAX_NAME_LENGTH)
    if (name.isEmpty()) return null
    val id = preset.id
        .ifBlank { fallbackId }
        .replace(Regex("[^a-zA-Z0-9_-]"), "")
        .take(MARKERDECK_HOST_MAX_ID_LENGTH)
        .ifEmpty { fallbackId }
    return HostPreset(id = id, name = name, state = normalizeHostState(preset.state))
}

fun hostPresetToJson(preset: HostPreset): JSONObject = JSONObject()
    .put("id", preset.id)
    .put("name", preset.name)
    .put("state", hostStateToJson(preset.state))

fun hostPresetFromJson(value: JSONObject, fallbackId: String): HostPreset? {
    val rawState = value.optJSONObject("state")
    return cleanHostPreset(
        HostPreset(
            id = value.optString("id", fallbackId),
            name = value.optString("name", ""),
            state = normalizeHostState(jsonObjectToValueMap(rawState))
        ),
        fallbackId
    )
}

fun defaultHostPresets(): List<HostPreset> {
    val definitions = listOf(
        listOf("绿底蓝十字", "#00ff00", "100", "#0040d8", "100"),
        listOf("60%绿底蓝十字", "#009900", "100", "#0040d8", "100"),
        listOf("30%绿底蓝十字", "#004d00", "100", "#0040d8", "100"),
        listOf("蓝底绿十字", "#0040d8", "100", "#00ff00", "100"),
        listOf("60%蓝底绿十字", "#002682", "100", "#00ff00", "100"),
        listOf("30%蓝底绿十字", "#001341", "100", "#00ff00", "100"),
        listOf("浅灰底蓝十字", "#d8d8d8", "100", "#0040d8", "100"),
        listOf("浅灰底绿十字", "#d8d8d8", "100", "#00ff00", "100")
    )
    return definitions.mapIndexed { index, definition ->
        val state = LinkedHashMap(DEFAULT_HOST_STATE).apply {
            put("bgColor", definition[1])
            put("bgBrightness", definition[2])
            put("crossColor", definition[3])
            put("crossBrightness", definition[4])
        }
        HostPreset("default-${index + 1}", definition[0], normalizeHostState(state))
    }
}

data class HostSettings(
    val hostName: String = "MarkerDeck",
    val deviceRetentionMs: Long = MARKERDECK_HOST_DEFAULT_RETENTION_MS
)

fun normalizeHostSettings(settings: HostSettings): HostSettings = HostSettings(
    hostName = normalizeHostName(settings.hostName),
    deviceRetentionMs = normalizeDeviceRetentionMs(settings.deviceRetentionMs)
)

data class HostRegistrationRequest(
    val legacyId: String,
    val sessionId: String,
    val deviceId: String,
    val pageInstanceId: String,
    val name: String,
    val updateName: Boolean,
    val role: String,
    val width: Int,
    val height: Int,
    val dpr: Double,
    val userAgent: String,
    val state: Map<String, *>
)

data class HostRegistrationResult(
    val ok: Boolean,
    val error: String = "",
    val sessionId: String = "",
    val name: String = "",
    val state: Map<String, String> = DEFAULT_HOST_STATE,
    val globalLockCommandId: String = "0",
    val globalLockCommand: String = "none",
    val deviceListChanged: Boolean = false
)

data class HostDeviceSnapshot(
    val id: String,
    val deviceId: String,
    val sessionId: String,
    val pageInstanceId: String,
    val name: String,
    val group: String,
    val role: String,
    val width: Int,
    val height: Int,
    val dpr: Double,
    val userAgent: String,
    val lastSeen: Long,
    val order: Long,
    val online: Boolean,
    val state: Map<String, String>
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("deviceId", deviceId)
        .put("sessionId", sessionId)
        .put("name", name)
        .put("group", group)
        .put("role", role)
        .put("width", width)
        .put("height", height)
        .put("dpr", dpr)
        .put("userAgent", userAgent)
        .put("lastSeen", lastSeen)
        .put("order", order)
        .put("online", online)
        .put("state", hostStateToJson(state))
}

fun parseDiscoveryNonce(payload: String): String? {
    if (payload.toByteArray(Charsets.UTF_8).size > 4096) return null
    return try {
        val request = JSONObject(payload)
        val nonce = request.optString("nonce", "").trim()
        if (request.optString("service", "") != MARKERDECK_DISCOVERY_SERVICE ||
            request.optInt("protocolVersion", -1) != MARKERDECK_DISCOVERY_PROTOCOL_VERSION ||
            request.optString("type", "") != MARKERDECK_DISCOVERY_REQUEST_TYPE ||
            !nonce.matches(Regex("^[A-Za-z0-9_-]{8,80}$"))
        ) {
            null
        } else {
            nonce
        }
    } catch (_: Exception) {
        null
    }
}

fun buildDiscoveryResponse(
    nonce: String,
    name: String,
    port: Int,
    ip: String,
    instanceId: String
): JSONObject = JSONObject()
    .put("service", MARKERDECK_DISCOVERY_SERVICE)
    .put("protocolVersion", MARKERDECK_DISCOVERY_PROTOCOL_VERSION)
    .put("type", MARKERDECK_DISCOVERY_RESPONSE_TYPE)
    .put("nonce", nonce)
    .put("name", normalizeHostName(name))
    .put("port", port)
    .put("httpUrl", "http://${ip.trim()}:$port")
    .put("instanceId", instanceId)

fun safeHostText(value: String, maxLength: Int): String = value
    .trim()
    .take(maxLength)
    .filterNot(Char::isISOControl)

fun normalizedHostRole(value: String): String = value.trim().take(20).ifEmpty { "display" }

fun hostIdSuffix(value: String): String = value.takeLast(4).ifEmpty { "屏幕" }

fun canonicalHostIp(value: String): String = value.trim().lowercase(Locale.ROOT).ifEmpty { "127.0.0.1" }
