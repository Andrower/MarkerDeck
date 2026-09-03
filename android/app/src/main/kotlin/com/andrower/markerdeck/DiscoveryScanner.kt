package com.andrower.markerdeck

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom

private const val DISCOVERY_SCAN_TIMEOUT_MS = 1_800L
private const val DISCOVERY_RECEIVE_TIMEOUT_MS = 250
private const val DISCOVERY_CONNECT_TIMEOUT_MS = 600
private const val DISCOVERY_READ_TIMEOUT_MS = 800
private const val DISCOVERY_HTTP_BODY_LIMIT = 16 * 1024

class MarkerDeckDiscoveryScanner(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener
) {
    interface Listener {
        fun onScanStarted()
        fun onHostDiscovered(host: DiscoveryHost)
        fun onScanFinished(status: DiscoveryScanStatus, message: String = "")
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private var scanJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var started = false
    private var refreshQueued = false

    fun start() {
        if (started) return
        started = true
        registerNetworkCallback()
        requestScan()
    }

    fun refresh() {
        if (!started) return
        requestScan()
    }

    fun stop() {
        started = false
        refreshQueued = false
        scanJob?.cancel()
        scanJob = null
        networkCallback?.let { callback ->
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (_: IllegalArgumentException) {
                // The callback may already have been removed by the framework.
            }
        }
        networkCallback = null
    }

    private fun registerNetworkCallback() {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = requestScanSoon()

            override fun onLost(network: Network) = requestScanSoon()

            override fun onLinkPropertiesChanged(
                network: Network,
                linkProperties: android.net.LinkProperties
            ) = requestScanSoon()
        }
        try {
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (_: RuntimeException) {
            listener.onScanFinished(
                DiscoveryScanStatus.UNAVAILABLE,
                "无法监听网络变化，仍可手动刷新或输入地址。"
            )
        }
    }

    private fun requestScanSoon() {
        if (!started || refreshQueued) return
        refreshQueued = true
        scope.launch {
            delay(250)
            refreshQueued = false
            if (started) requestScan()
        }
    }

    private fun requestScan() {
        scanJob?.cancel()
        scanJob = scope.launch {
            listener.onScanStarted()
            val networkState = activeLanNetworkState()
            if (!networkState.available) {
                listener.onScanFinished(networkState.status, networkState.message)
                return@launch
            }
            val discovered = withContext(Dispatchers.IO) {
                scanOnce(networkState.network, networkState.usesWifi)
            }
            if (!isActive || !started) return@launch
            discovered.forEach(listener::onHostDiscovered)
            listener.onScanFinished(
                if (discovered.isEmpty()) DiscoveryScanStatus.EMPTY else DiscoveryScanStatus.FOUND
            )
        }
    }

    private fun activeLanNetworkState(): ActiveLanNetworkState {
        val candidates = buildList {
            connectivityManager.activeNetwork?.let(::add)
            connectivityManager.allNetworks.forEach { network ->
                if (!contains(network)) add(network)
            }
        }
        val networkAndCapabilities = candidates.asSequence()
            .mapNotNull { network ->
                connectivityManager.getNetworkCapabilities(network)?.let { network to it }
            }
            .firstOrNull { (_, capabilities) ->
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            }
        val network = networkAndCapabilities?.first
        val capabilities = networkAndCapabilities?.second
        val usesWifi = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        return when {
            network == null || capabilities == null -> ActiveLanNetworkState(
                available = false,
                network = null,
                usesWifi = false,
                status = DiscoveryScanStatus.NO_NETWORK,
                message = "未检测到 Wi-Fi 或有线局域网连接。"
            )

            else -> ActiveLanNetworkState(available = true, network = network, usesWifi = usesWifi)
        }
    }

    private fun scanOnce(network: Network?, usesWifi: Boolean): List<DiscoveryHost> {
        val nonce = newNonce()
        val payload = JSONObject()
            .put("service", MARKERDECK_DISCOVERY_SERVICE)
            .put("protocolVersion", MARKERDECK_DISCOVERY_PROTOCOL_VERSION)
            .put("type", MARKERDECK_DISCOVERY_REQUEST_TYPE)
            .put("nonce", nonce)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val hosts = mutableListOf<DiscoveryHost>()
        val seenResponses = mutableSetOf<String>()
        val multicastLock = acquireMulticastLock(usesWifi)
        try {
            DatagramSocket().use { socket ->
                network?.bindSocket(socket)
                socket.broadcast = true
                socket.soTimeout = DISCOVERY_RECEIVE_TIMEOUT_MS
                val destinations = discoveryDestinations()
                destinations.forEach { destination ->
                    socket.send(
                        DatagramPacket(
                            payload,
                            payload.size,
                            destination,
                            MARKERDECK_DISCOVERY_PORT
                        )
                    )
                }
                val deadline = System.currentTimeMillis() + DISCOVERY_SCAN_TIMEOUT_MS
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline && !Thread.currentThread().isInterrupted) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: java.net.SocketTimeoutException) {
                        continue
                    }
                    val sourceAddress = packet.address?.hostAddress ?: continue
                    val response = parseDiscoveryAdvertisement(
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            .toString(Charsets.UTF_8)
                    ) ?: continue
                    val candidate = validateDiscoveryResponse(response, nonce, sourceAddress) ?: continue
                    val responseKey = "${response.instanceId}|$sourceAddress|${response.port}"
                    if (!seenResponses.add(responseKey)) continue
                    val verifiedResponse = fetchAndParseHandshake(candidate, nonce) ?: continue
                    if (!isDiscoveryResponseForCandidate(verifiedResponse, candidate)) continue
                    val verifiedHost = validateDiscoveryResponse(
                        verifiedResponse,
                        expectedNonce = nonce,
                        sourceAddress = sourceAddress
                    ) ?: continue
                    hosts.removeAll { it.identity == verifiedHost.identity }
                    hosts += verifiedHost
                }
            }
        } catch (_: java.net.SocketException) {
            return emptyList()
        } catch (_: java.io.IOException) {
            return emptyList()
        } finally {
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
        }
        return mergeDiscoveredHosts(emptyList(), hosts)
    }

    private fun acquireMulticastLock(usesWifi: Boolean): WifiManager.MulticastLock? {
        if (!usesWifi || wifiManager == null) return null
        return try {
            wifiManager.createMulticastLock("MarkerDeckDiscovery").apply {
                setReferenceCounted(false)
                acquire()
            }
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun discoveryDestinations(): Set<InetAddress> {
        val destinations = LinkedHashSet<InetAddress>()
        try {
            destinations += InetAddress.getByName("255.255.255.255")
            destinations += InetAddress.getByName(MARKERDECK_DISCOVERY_MULTICAST_ADDRESS)
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (!networkInterface.isUp || networkInterface.isLoopback || networkInterface.isVirtual) continue
                networkInterface.interfaceAddresses.forEach { interfaceAddress ->
                    val broadcast = interfaceAddress.broadcast
                    if (broadcast is Inet4Address) destinations += broadcast
                }
            }
        } catch (_: Exception) {
            // The global broadcast and multicast destinations are still useful fallbacks.
        }
        return destinations
    }

    private fun fetchAndParseHandshake(
        candidate: DiscoveryHost,
        nonce: String
    ): DiscoveryAdvertisement? {
        val encodedNonce = URLEncoder.encode(nonce, Charsets.UTF_8.name())
        val connection = try {
            URL("${candidate.serviceAddress}/api/discovery?nonce=$encodedNonce")
                .openConnection() as? HttpURLConnection
        } catch (_: Exception) {
            null
        } ?: return null
        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = DISCOVERY_CONNECT_TIMEOUT_MS
                readTimeout = DISCOVERY_READ_TIMEOUT_MS
                useCaches = false
                instanceFollowRedirects = false
                doInput = true
            }
            if (connection.responseCode !in 200..299) return null
            if (connection.contentLengthLong > DISCOVERY_HTTP_BODY_LIMIT) return null
            val body = readLimitedBody(connection.inputStream, DISCOVERY_HTTP_BODY_LIMIT) ?: return null
            parseDiscoveryAdvertisement(body.toString(Charsets.UTF_8))
        } catch (_: Exception) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private fun readLimitedBody(input: java.io.InputStream, limit: Int): ByteArray? {
        input.use { stream ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            while (true) {
                val count = stream.read(buffer)
                if (count < 0) break
                if (output.size() + count > limit) return null
                output.write(buffer, 0, count)
            }
            return output.toByteArray()
        }
    }

    private fun newNonce(): String {
        val bytes = ByteArray(18)
        SecureRandom().nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    private data class ActiveLanNetworkState(
        val available: Boolean,
        val usesWifi: Boolean,
        val network: Network? = null,
        val status: DiscoveryScanStatus = DiscoveryScanStatus.SCANNING,
        val message: String = ""
    )
}
