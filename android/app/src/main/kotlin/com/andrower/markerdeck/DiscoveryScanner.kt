package com.andrower.markerdeck

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean

private const val DISCOVERY_SCAN_TIMEOUT_MS = 1_800L
private const val DISCOVERY_MDNS_SCAN_TIMEOUT_MS = 1_400L
private const val DISCOVERY_RECEIVE_TIMEOUT_MS = 250
private const val DISCOVERY_UDP_RETRY_DELAY_MS = 300L
private const val DISCOVERY_CONNECT_TIMEOUT_MS = 600
private const val DISCOVERY_READ_TIMEOUT_MS = 800
private const val DISCOVERY_HTTP_BODY_LIMIT = 16 * 1024
private const val DISCOVERY_MAX_CANDIDATES = 8
private const val DISCOVERY_MAX_HTTP_VERIFICATIONS = 4

class MarkerDeckDiscoveryScanner(
    context: Context,
    private val scope: CoroutineScope,
    private val listener: Listener,
    private val selfInstanceIdProvider: () -> String = { "" }
) {
    interface Listener {
        fun onScanStarted()
        fun onHostDiscovered(host: DiscoveryHost)
        fun onScanFinished(status: DiscoveryScanStatus, message: String = "")

        /** Keeps source compatibility for callers that do not need the scan trigger. */
        fun onScanStartedWithTrigger(trigger: DiscoveryScanTrigger) = onScanStarted()

        /** Delivers verified hosts as soon as their HTTP handshake completes. */
        fun onHostDiscoveredWithTrigger(host: DiscoveryHost, trigger: DiscoveryScanTrigger) =
            onHostDiscovered(host)

        fun onScanFinishedWithTrigger(
            status: DiscoveryScanStatus,
            message: String,
            trigger: DiscoveryScanTrigger
        ) = onScanFinished(status, message)
    }

    private val appContext = context.applicationContext
    private val connectivityManager =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val mdnsScanner = MarkerDeckMdnsScanner(appContext)
    private var scanJob: Job? = null
    private var networkRefreshJob: Job? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var started = false
    @Volatile private var refreshQueued = false
    @Volatile private var scanGeneration = 0L
    private var activeScanTrigger: DiscoveryScanTrigger? = null
    private var pendingTrigger: DiscoveryScanTrigger? = null
    private var pendingScanGeneration = 0L
    private val stateLock = Any()

    fun start() {
        synchronized(stateLock) {
            if (started) return
            started = true
            pendingTrigger = DiscoveryScanTrigger.STARTUP
        }
        requestScan()
        // Register after requestScan so an immediate onAvailable callback cannot replace STARTUP.
        registerNetworkCallback()
    }

    fun refresh() {
        if (!started) return
        synchronized(stateLock) {
            pendingTrigger = mergeDiscoveryScanTrigger(
                pendingTrigger,
                DiscoveryScanTrigger.USER_REFRESH
            )
        }
        val activeTrigger = synchronized(stateLock) { activeScanTrigger }
        if (activeTrigger != DiscoveryScanTrigger.STARTUP) {
            requestScan()
        }
    }

    fun stop() {
        val jobs = synchronized(stateLock) {
            started = false
            refreshQueued = false
            scanGeneration += 1
            activeScanTrigger = null
            pendingTrigger = null
            pendingScanGeneration += 1
            val currentScanJob = scanJob
            val currentRefreshJob = networkRefreshJob
            scanJob = null
            networkRefreshJob = null
            currentScanJob to currentRefreshJob
        }
        jobs.second?.cancel()
        mdnsScanner.stop()
        jobs.first?.cancel()
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
            listener.onScanFinishedWithTrigger(
                DiscoveryScanStatus.UNAVAILABLE,
                "无法监听网络变化，仍可手动刷新或输入地址。",
                DiscoveryScanTrigger.NETWORK
            )
        }
    }

    private fun requestScanSoon() {
        synchronized(stateLock) {
            if (!started) return
            pendingTrigger = mergeDiscoveryScanTrigger(
                pendingTrigger,
                DiscoveryScanTrigger.NETWORK
            )
            if (refreshQueued || activeScanTrigger != null || scanJob?.isActive == true) return
            refreshQueued = true
        }
        schedulePendingScan()
    }

    private fun requestScan() {
        val trigger: DiscoveryScanTrigger
        val token: Long
        val previousScan: Job?
        synchronized(stateLock) {
            if (!started) return
            trigger = pendingTrigger ?: DiscoveryScanTrigger.NETWORK
            pendingTrigger = null
            scanGeneration += 1
            token = scanGeneration
            activeScanTrigger = trigger
            previousScan = scanJob
            scanJob = null
        }
        previousScan?.cancel()
        // Invalidate the previous scan before stopping mDNS so its finally block cannot stop
        // the next generation after it has started.
        mdnsScanner.stop()
        val job = scope.launch {
            try {
                listener.onScanStartedWithTrigger(trigger)
                val networkState = activeLanNetworkState()
                if (!networkState.available) {
                    listener.onScanFinishedWithTrigger(networkState.status, networkState.message, trigger)
                    return@launch
                }
                val discovered = withContext(Dispatchers.IO) {
                    scanOnce(networkState.network, networkState.usesWifi, token) { host ->
                        withContext(Dispatchers.Main.immediate) {
                            if (isCurrentScan(token)) {
                                listener.onHostDiscoveredWithTrigger(host, trigger)
                            }
                        }
                    }
                }
                if (!isActive || !isCurrentScan(token)) return@launch
                listener.onScanFinishedWithTrigger(
                    if (discovered.isEmpty()) DiscoveryScanStatus.EMPTY else DiscoveryScanStatus.FOUND,
                    "",
                    trigger
                )
            } catch (error: CancellationException) {
                throw error
            } catch (_: Exception) {
                if (isCurrentScan(token)) {
                    listener.onScanFinishedWithTrigger(
                        DiscoveryScanStatus.UNAVAILABLE,
                        "发现扫描失败，请稍后重试。",
                        trigger
                    )
                }
            } finally {
                finishScan(token, trigger)
            }
        }
        synchronized(stateLock) {
            if (started && scanGeneration == token && activeScanTrigger == trigger) {
                scanJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun schedulePendingScan() {
        val scheduleToken = synchronized(stateLock) {
            if (!started || networkRefreshJob?.isActive == true) return
            pendingScanGeneration += 1
            pendingScanGeneration
        }
        val job = scope.launch {
            try {
                delay(250)
                val shouldStart = synchronized(stateLock) {
                    if (pendingScanGeneration == scheduleToken) refreshQueued = false
                    started && pendingScanGeneration == scheduleToken &&
                        activeScanTrigger == null && scanJob?.isActive != true &&
                        pendingTrigger != null
                }
                if (shouldStart) requestScan()
            } finally {
                synchronized(stateLock) {
                    if (pendingScanGeneration == scheduleToken) networkRefreshJob = null
                }
            }
        }
        synchronized(stateLock) {
            if (started && pendingScanGeneration == scheduleToken && job.isActive) {
                networkRefreshJob = job
            } else {
                job.cancel()
            }
        }
    }

    private fun finishScan(token: Long, trigger: DiscoveryScanTrigger) {
        val shouldSchedule = synchronized(stateLock) {
            if (!started || scanGeneration != token || activeScanTrigger != trigger) return
            activeScanTrigger = null
            scanJob = null
            pendingTrigger != null
        }
        if (shouldSchedule) schedulePendingScan()
    }

    private fun isCurrentScan(token: Long): Boolean = synchronized(stateLock) {
        started && scanGeneration == token && activeScanTrigger != null
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

    private suspend fun scanOnce(
        network: Network?,
        usesWifi: Boolean,
        token: Long,
        onHostVerified: suspend (DiscoveryHost) -> Unit
    ): List<DiscoveryHost> = coroutineScope {
        val nonce = newNonce()
        val selfInstanceId = selfInstanceIdProvider().trim()
        val events = Channel<ScanEvent>(Channel.UNLIMITED)
        val stopSignal = AtomicBoolean(false)
        val multicastLock = acquireMulticastLock(usesWifi)
        var mdnsStarted = false
        var udpJob: Job? = null
        val verifiedHosts = LinkedHashMap<String, DiscoveryHost>()
        val seenCandidates = mutableSetOf<String>()
        val queuedCandidates = ArrayDeque<Pair<String, Candidate>>()
        val verificationJobs = mutableMapOf<String, Job>()
        var udpComplete = false
        var mdnsComplete = false
        var pendingVerifications = 0
        var firstVerifiedAtMs: Long? = null

        fun startQueuedVerifications() {
            while (verificationJobs.size < DISCOVERY_MAX_HTTP_VERIFICATIONS &&
                queuedCandidates.isNotEmpty()
            ) {
                val (key, candidate) = queuedCandidates.removeFirst()
                val verificationJob = launch(Dispatchers.IO) {
                    val host = runInterruptible(Dispatchers.IO) {
                        val handshake = fetchAndParseHandshake(candidate.host, nonce)
                            ?: return@runInterruptible null
                        if (!isDiscoveryResponseForCandidate(handshake, candidate.host)) {
                            return@runInterruptible null
                        }
                        validateDiscoveryResponse(
                            response = handshake,
                            expectedNonce = nonce,
                            sourceAddress = candidate.sourceAddress,
                            selfInstanceId = selfInstanceId
                        )
                    }
                    events.trySend(ScanEvent.VerificationFinished(key, host))
                }
                verificationJobs[key] = verificationJob
            }
        }

        try {
            mdnsStarted = mdnsScanner.start(
                timeoutMs = DISCOVERY_MDNS_SCAN_TIMEOUT_MS,
                nextListener = object : MarkerDeckMdnsScanner.Listener {
                    override fun onRecord(record: MdnsDiscoveryRecord) {
                        val host = validateMdnsCandidate(record, selfInstanceId) ?: return
                        events.trySend(
                            ScanEvent.CandidateFound(
                                Candidate(host, record.sourceAddress)
                            )
                        )
                    }

                    override fun onFinished() {
                        events.trySend(ScanEvent.SourceFinished(DiscoveryPath.MDNS))
                    }
                }
            )
            mdnsComplete = !mdnsStarted
            udpJob = launch(Dispatchers.IO) {
                try {
                    runInterruptible(Dispatchers.IO) {
                        scanUdpCandidates(
                            network = network,
                            nonce = nonce,
                            selfInstanceId = selfInstanceId,
                            shouldStop = stopSignal::get
                        ) { candidate ->
                            events.trySend(ScanEvent.CandidateFound(candidate))
                        }
                    }
                } finally {
                    events.trySend(ScanEvent.SourceFinished(DiscoveryPath.UDP))
                }
            }

            while (isActive) {
                if (shouldFinishDiscoveryScan(
                        udpComplete = udpComplete,
                        mdnsComplete = mdnsComplete,
                        pendingVerifications = pendingVerifications,
                        firstVerifiedAtMs = firstVerifiedAtMs,
                        nowMs = SystemClock.elapsedRealtime()
                    )
                ) break
                val waitMs = if (firstVerifiedAtMs == null) {
                    DISCOVERY_RECEIVE_TIMEOUT_MS.toLong()
                } else {
                    (firstVerifiedAtMs + DISCOVERY_MULTI_HOST_GRACE_MS -
                        SystemClock.elapsedRealtime()).coerceAtLeast(1L)
                }
                val event = withTimeoutOrNull(waitMs) { events.receive() } ?: continue
                when (event) {
                    is ScanEvent.CandidateFound -> {
                        val candidate = event.candidate
                        val key = "${candidate.host.instanceId}|${candidate.sourceAddress}|${candidate.host.port}"
                        if (seenCandidates.contains(key) ||
                            seenCandidates.size >= DISCOVERY_MAX_CANDIDATES
                        ) {
                            continue
                        }
                        seenCandidates.add(key)
                        pendingVerifications += 1
                        queuedCandidates.addLast(key to candidate)
                        startQueuedVerifications()
                    }

                    is ScanEvent.VerificationFinished -> {
                        if (verificationJobs.remove(event.key) == null) continue
                        pendingVerifications -= 1
                        startQueuedVerifications()
                        val host = event.host ?: continue
                        val identity = host.identity
                        val previous = verifiedHosts[identity]
                        verifiedHosts[identity] = host
                        if (previous?.serviceAddress != host.serviceAddress) {
                            if (firstVerifiedAtMs == null) {
                                firstVerifiedAtMs = SystemClock.elapsedRealtime()
                            }
                            onHostVerified(host)
                        }
                    }

                    is ScanEvent.SourceFinished -> when (event.path) {
                        DiscoveryPath.UDP -> udpComplete = true
                        DiscoveryPath.MDNS -> mdnsComplete = true
                    }
                }
            }
            return@coroutineScope mergeDiscoveredHosts(emptyList(), verifiedHosts.values.toList())
        } finally {
            stopSignal.set(true)
            verificationJobs.values.forEach { it.cancel() }
            udpJob?.cancel()
            if (mdnsStarted && isCurrentScan(token)) mdnsScanner.stop()
            multicastLock?.let {
                if (it.isHeld) it.release()
            }
            events.close()
        }
    }

    private fun scanUdpCandidates(
        network: Network?,
        nonce: String,
        selfInstanceId: String,
        shouldStop: () -> Boolean = { false },
        onCandidate: (Candidate) -> Unit = {}
    ): List<Candidate> {
        val payload = JSONObject()
            .put("service", MARKERDECK_DISCOVERY_SERVICE)
            .put("protocolVersion", MARKERDECK_DISCOVERY_PROTOCOL_VERSION)
            .put("type", MARKERDECK_DISCOVERY_REQUEST_TYPE)
            .put("nonce", nonce)
            .toString()
            .toByteArray(Charsets.UTF_8)
        val candidates = LinkedHashMap<String, Candidate>()
        try {
            DatagramSocket().use { socket ->
                network?.bindSocket(socket)
                socket.broadcast = true
                val deadline = SystemClock.elapsedRealtime() + DISCOVERY_SCAN_TIMEOUT_MS
                val destinations = discoveryDestinations()
                fun sendToDestinations() {
                    if (SystemClock.elapsedRealtime() >= deadline ||
                        Thread.currentThread().isInterrupted || shouldStop()
                    ) return
                    destinations.forEach { destination ->
                        if (SystemClock.elapsedRealtime() >= deadline ||
                            Thread.currentThread().isInterrupted || shouldStop()
                        ) return@forEach
                        runCatching {
                            socket.send(
                                DatagramPacket(
                                    payload,
                                    payload.size,
                                    destination,
                                    MARKERDECK_DISCOVERY_PORT
                                )
                            )
                        }
                    }
                }
                sendToDestinations()
                val retryAt = SystemClock.elapsedRealtime() + DISCOVERY_UDP_RETRY_DELAY_MS
                var retrySent = false
                val buffer = ByteArray(4096)
                while (SystemClock.elapsedRealtime() < deadline &&
                    !Thread.currentThread().isInterrupted && !shouldStop()
                ) {
                    val now = SystemClock.elapsedRealtime()
                    if (shouldSendDiscoveryUdpRetry(
                            retrySent = retrySent,
                            stopped = Thread.currentThread().isInterrupted || shouldStop(),
                            nowMs = now,
                            retryAtMs = retryAt,
                            deadlineMs = deadline
                        )
                    ) {
                        sendToDestinations()
                        retrySent = true
                    }
                    val nextWake = if (retrySent) deadline else minOf(deadline, retryAt)
                    socket.soTimeout = (nextWake - now)
                        .coerceAtLeast(1L)
                        .coerceAtMost(DISCOVERY_RECEIVE_TIMEOUT_MS.toLong())
                        .toInt()
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val sourceAddress = packet.address?.hostAddress ?: continue
                    val response = parseDiscoveryAdvertisement(
                        packet.data.copyOfRange(packet.offset, packet.offset + packet.length)
                            .toString(Charsets.UTF_8)
                    ) ?: continue
                    val candidate = validateDiscoveryResponse(
                        response,
                        nonce,
                        sourceAddress,
                        selfInstanceId
                    ) ?: continue
                    val responseKey = "${response.instanceId}|$sourceAddress|${response.port}"
                    val entry = Candidate(candidate, sourceAddress)
                    if (candidates.putIfAbsent(responseKey, entry) == null) onCandidate(entry)
                }
            }
        } catch (_: SocketException) {
            return emptyList()
        } catch (_: java.io.IOException) {
            return emptyList()
        }
        return candidates.values.toList()
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

    private data class Candidate(
        val host: DiscoveryHost,
        val sourceAddress: String
    )

    private enum class DiscoveryPath {
        UDP,
        MDNS
    }

    private sealed interface ScanEvent {
        data class CandidateFound(val candidate: Candidate) : ScanEvent
        data class VerificationFinished(val key: String, val host: DiscoveryHost?) : ScanEvent
        data class SourceFinished(val path: DiscoveryPath) : ScanEvent
    }

    private data class ActiveLanNetworkState(
        val available: Boolean,
        val usesWifi: Boolean,
        val network: Network? = null,
        val status: DiscoveryScanStatus = DiscoveryScanStatus.SCANNING,
        val message: String = ""
    )
}
