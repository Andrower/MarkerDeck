package com.andrower.markerdeck

import org.json.JSONObject
import java.net.URI
import java.util.Locale

const val MARKERDECK_DISCOVERY_PROTOCOL_VERSION = 1
const val MARKERDECK_DISCOVERY_SERVICE = "markerdeck"
const val MARKERDECK_DISCOVERY_REQUEST_TYPE = "discover"
const val MARKERDECK_DISCOVERY_RESPONSE_TYPE = "response"
const val MARKERDECK_DISCOVERY_PORT = 8766
const val MARKERDECK_DISCOVERY_MULTICAST_ADDRESS = "239.255.77.77"
const val MARKERDECK_DISCOVERY_MAX_NAME_LENGTH = 40
// Android NSD omits the implicit .local suffix; the full DNS-SD type is kept for validation.
const val MARKERDECK_MDNS_SERVICE_TYPE = "_markerdeck._tcp.local"
const val MARKERDECK_MDNS_NSD_SERVICE_TYPE = "_markerdeck._tcp"
const val MARKERDECK_MDNS_PROTOCOL = "tcp"
const val MARKERDECK_MDNS_SERVICE = "markerdeck"

data class DiscoveryAdvertisement(
    val service: String,
    val protocolVersion: Int,
    val type: String,
    val nonce: String,
    val name: String,
    val port: Int,
    val httpUrl: String,
    val instanceId: String
)

data class DiscoveryHost(
    val instanceId: String,
    val name: String,
    val serviceAddress: String,
    val port: Int,
    val advertisedHttpUrl: String
) {
    val identity: String
        get() = if (instanceId.isNotBlank()) {
            "instance:$instanceId"
        } else {
            "address:$serviceAddress"
        }
}

enum class DiscoveryScanStatus {
    IDLE,
    SCANNING,
    FOUND,
    EMPTY,
    NO_NETWORK,
    UNAVAILABLE
}

data class DiscoveryUiState(
    val status: DiscoveryScanStatus = DiscoveryScanStatus.IDLE,
    val hosts: List<DiscoveryHost> = emptyList(),
    val message: String = ""
)

/** Validates untrusted NSD TXT data and builds an origin from the resolved peer address. */
fun validateMdnsCandidate(
    record: MdnsDiscoveryRecord,
    selfInstanceId: String = ""
): DiscoveryHost? {
    val normalizedType = normalizeMdnsServiceType(record.serviceType)
    val service = record.txt["service"]?.trim()
    val protocolVersion = record.txt["protocolVersion"]?.trim()
    val instanceId = record.txt["instanceId"]?.trim()
    val name = (record.txt["name"] ?: record.serviceName).trim()
    val sourceAddress = record.sourceAddress.trim().replaceFirst(
        Regex("^::ffff:", RegexOption.IGNORE_CASE),
        ""
    )
    if (normalizedType != MARKERDECK_MDNS_SERVICE_TYPE ||
        service != MARKERDECK_MDNS_SERVICE ||
        protocolVersion != MARKERDECK_DISCOVERY_PROTOCOL_VERSION.toString() ||
        instanceId.isNullOrEmpty() ||
        !instanceId.matches(DISCOVERY_INSTANCE_PATTERN) ||
        instanceId == selfInstanceId ||
        !isSafeDiscoveryText(name, MARKERDECK_DISCOVERY_MAX_NAME_LENGTH) ||
        record.port !in 1..65535 ||
        !isLanIpv4Address(sourceAddress)
    ) return null

    val serviceAddress = "http://$sourceAddress:${record.port}"
    return DiscoveryHost(
        instanceId = instanceId,
        name = name,
        serviceAddress = serviceAddress,
        port = record.port,
        advertisedHttpUrl = serviceAddress
    )
}

/** Parses only the fields used by the versioned discovery protocol. */
fun parseDiscoveryAdvertisement(payload: String): DiscoveryAdvertisement? = try {
    val json = JSONObject(payload)
    DiscoveryAdvertisement(
        service = json.getString("service"),
        protocolVersion = json.getInt("protocolVersion"),
        type = json.getString("type"),
        nonce = json.getString("nonce"),
        name = json.getString("name"),
        port = json.getInt("port"),
        httpUrl = json.getString("httpUrl"),
        instanceId = json.getString("instanceId")
    )
} catch (_: Exception) {
    null
}

/**
 * Validates a response against the request nonce and UDP peer, then builds an address from the
 * verified peer. The advertised URL is informational until the HTTP handshake succeeds.
 */
fun validateDiscoveryResponse(
    response: DiscoveryAdvertisement,
    expectedNonce: String,
    sourceAddress: String,
    selfInstanceId: String = ""
): DiscoveryHost? {
    if (!expectedNonce.matches(DISCOVERY_NONCE_PATTERN)) return null
    if (response.service != MARKERDECK_DISCOVERY_SERVICE ||
        response.protocolVersion != MARKERDECK_DISCOVERY_PROTOCOL_VERSION ||
        response.type != MARKERDECK_DISCOVERY_RESPONSE_TYPE ||
        response.nonce != expectedNonce
    ) return null
    if (!isSafeDiscoveryText(response.name, MARKERDECK_DISCOVERY_MAX_NAME_LENGTH) ||
        !response.instanceId.matches(DISCOVERY_INSTANCE_PATTERN) ||
        response.instanceId == selfInstanceId ||
        response.port !in 1..65535
    ) return null
    if (!isLanIpv4Address(sourceAddress)) return null

    val advertisedUri = try {
        URI(response.httpUrl.trim())
    } catch (_: Exception) {
        return null
    }
    if (advertisedUri.scheme?.lowercase(Locale.ROOT) != "http" ||
        advertisedUri.userInfo != null ||
        advertisedUri.rawQuery != null ||
        advertisedUri.rawFragment != null ||
        (advertisedUri.rawPath?.isNotEmpty() == true && advertisedUri.rawPath != "/")
    ) return null
    val advertisedHost = advertisedUri.host ?: return null
    if (!isLanIpv4Address(advertisedHost)) return null
    val advertisedPort = if (advertisedUri.port == -1) 80 else advertisedUri.port
    if (advertisedPort != response.port) return null

    val sourceUri = try {
        URI("http://${sourceAddress.trim()}:${response.port}")
    } catch (_: Exception) {
        return null
    }
    val normalizedSource = try {
        normalizeServiceAddress(sourceUri.toString())
    } catch (_: IllegalArgumentException) {
        return null
    }
    return DiscoveryHost(
        instanceId = response.instanceId,
        name = response.name,
        serviceAddress = normalizedSource,
        port = response.port,
        advertisedHttpUrl = try {
            normalizeServiceAddress(response.httpUrl)
        } catch (_: IllegalArgumentException) {
            return null
        }
    )
}

fun isDiscoveryResponseForCandidate(
    response: DiscoveryAdvertisement,
    candidate: DiscoveryHost
): Boolean = response.instanceId == candidate.instanceId && response.port == candidate.port

/** Keeps one current row per server instance and handles a server changing its LAN address. */
fun mergeDiscoveredHosts(
    existing: List<DiscoveryHost>,
    incoming: List<DiscoveryHost>
): List<DiscoveryHost> {
    val merged = LinkedHashMap<String, DiscoveryHost>()
    (existing + incoming).forEach { host ->
        val sameAddress = merged.values.firstOrNull { it.serviceAddress == host.serviceAddress }
        val key = if (sameAddress != null) sameAddress.identity else host.identity
        merged[key] = host
    }
    return merged.values.sortedWith(
        compareBy<DiscoveryHost> { it.name.lowercase(Locale.ROOT) }
            .thenBy { it.serviceAddress }
    )
}

fun mergeDiscoveryUiState(
    current: DiscoveryUiState,
    status: DiscoveryScanStatus,
    incoming: List<DiscoveryHost> = emptyList(),
    replaceHosts: Boolean = false,
    message: String = ""
): DiscoveryUiState {
    val hosts = if (replaceHosts) {
        mergeDiscoveredHosts(emptyList(), incoming)
    } else {
        mergeDiscoveredHosts(current.hosts, incoming)
    }
    val effectiveStatus = if (status == DiscoveryScanStatus.EMPTY && hosts.isNotEmpty()) {
        DiscoveryScanStatus.FOUND
    } else {
        status
    }
    return current.copy(status = effectiveStatus, hosts = hosts, message = message)
}

private val DISCOVERY_NONCE_PATTERN = Regex("^[A-Za-z0-9_-]{8,80}$")
internal val DISCOVERY_INSTANCE_PATTERN = Regex("^[A-Za-z0-9_-]{8,80}$")

fun normalizeMdnsServiceType(value: String): String {
    val serviceType = value.trim().trimEnd('.')
    return if (serviceType.endsWith(".local")) serviceType else "$serviceType.local"
}

private fun isSafeDiscoveryText(value: String, maxLength: Int): Boolean =
    value.isNotBlank() && value.length <= maxLength && value.none { it.isISOControl() }

private fun isLanIpv4Address(value: String): Boolean {
    val octets = value.trim().split('.')
    if (octets.size != 4 || octets.any { it.isEmpty() || it.length > 3 || !it.all(Char::isDigit) }) {
        return false
    }
    val numbers = octets.map { it.toIntOrNull() ?: return false }
    if (numbers.any { it !in 0..255 }) return false
    return when {
        numbers[0] == 127 -> true
        numbers[0] == 10 -> true
        numbers[0] == 172 && numbers[1] in 16..31 -> true
        numbers[0] == 192 && numbers[1] == 168 -> true
        numbers[0] == 169 && numbers[1] == 254 -> true
        else -> false
    }
}

data class MdnsDiscoveryRecord(
    val serviceType: String,
    val serviceName: String,
    val port: Int,
    val sourceAddress: String,
    val txt: Map<String, String>
)
