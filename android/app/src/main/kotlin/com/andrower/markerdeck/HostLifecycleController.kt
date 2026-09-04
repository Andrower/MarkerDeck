package com.andrower.markerdeck

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException
import java.util.UUID

class HostLifecycleController(
    context: Context,
    private val onShutdownRequested: () -> Unit = {}
) {
    private val appContext = context.applicationContext
    private val persistence = AndroidHostStatePersistence(appContext)
    private val store = MarkerDeckHostStateStore(persistence)
    private var server: AndroidHostServer? = null
    private var sseHub: MarkerDeckHostSseHub? = null
    private var udpResponder: MarkerDeckHostUdpResponder? = null
    private var mdnsPublisher: MarkerDeckMdnsPublisher? = null
    private var udpDiscoveryAvailable = false
    private var mdnsDiscoveryAvailable = false
    private var multicastLock: WifiManager.MulticastLock? = null
    private var session: EmbeddedHostSession? = null

    @Synchronized
    fun hostName(): String = store.hostSettings().hostName

    @Synchronized
    fun start(mode: EmbeddedHostMode, requestedHostName: String = hostName()): EmbeddedHostSession {
        stopLocked()
        store.setHostName(requestedHostName)
        val instanceId = UUID.randomUUID().toString().replace("-", "")

        val bindAddress = if (mode == EmbeddedHostMode.LOCAL_PROJECTION) "127.0.0.1" else "0.0.0.0"
        val preferredPort = if (mode == EmbeddedHostMode.LOCAL_PROJECTION) 0 else MARKERDECK_HOST_DEFAULT_PORT
        val nextSseHub = MarkerDeckHostSseHub()
        var activeSseHub = nextSseHub
        val nextServer = createServer(mode, bindAddress, preferredPort, nextSseHub, instanceId)
        val actualServer = try {
            startAndWait(nextServer)
            nextServer
        } catch (error: IOException) {
            nextServer.stop()
            if (mode != EmbeddedHostMode.LAN_HOST || preferredPort == 0) {
                nextSseHub.close()
                throw error
            }
            val fallbackHub = MarkerDeckHostSseHub()
            val fallbackServer = createServer(mode, bindAddress, 0, fallbackHub, instanceId)
            try {
                startAndWait(fallbackServer)
                nextSseHub.close()
                activeSseHub = fallbackHub
                sseHub = fallbackHub
                fallbackServer
            } catch (fallbackError: IOException) {
                fallbackServer.stop()
                fallbackHub.close()
                nextSseHub.close()
                throw fallbackError
            }
        }

        val port = actualServer.getListeningPort()
        if (port !in 1..65535) {
            actualServer.stop()
            activeSseHub.close()
            throw IOException("Embedded host did not bind a port")
        }
        val hostIp = if (mode == EmbeddedHostMode.LOCAL_PROJECTION) {
            "127.0.0.1"
        } else {
            MarkerDeckHostNetworkAddress.findLanIpv4()
        }
        val origin = normalizeServiceAddress("http://127.0.0.1:$port")
        val availability = if (mode == EmbeddedHostMode.LAN_HOST) {
            startDiscovery(actualServer, instanceId)
        } else {
            DiscoveryAvailability()
        }
        val nextSession = EmbeddedHostSession(
            mode = mode,
            origin = origin,
            url = if (mode == EmbeddedHostMode.LOCAL_PROJECTION) {
                buildLocalProjectionUrl(origin)
            } else {
                buildHostControlUrl(origin)
            },
            port = port,
            lanAddress = hostIp,
            discoveryAvailable = availability.udp || availability.mdns,
            instanceId = instanceId,
            udpDiscoveryAvailable = availability.udp,
            mdnsDiscoveryAvailable = availability.mdns
        )
        server = actualServer
        if (sseHub == null) sseHub = activeSseHub
        session = nextSession
        updateSessionDiscoveryAvailability()
        return session ?: nextSession
    }

    @Synchronized
    fun stop() {
        stopLocked()
    }

    @Synchronized
    fun isRunning(): Boolean = server != null && session != null

    @Synchronized
    fun currentSession(): EmbeddedHostSession? = session

    private fun createServer(
        mode: EmbeddedHostMode,
        bindAddress: String,
        port: Int,
        nextSseHub: MarkerDeckHostSseHub,
        instanceId: String
    ): AndroidHostServer = AndroidHostServer(
        bindAddress = bindAddress,
        requestedPort = port,
        store = store,
        sseHub = nextSseHub,
        assetReader = { assetName ->
            runCatching { appContext.assets.open(assetName).use { it.readBytes() } }.getOrNull()
        },
        ipProvider = {
            if (mode == EmbeddedHostMode.LOCAL_PROJECTION) "127.0.0.1"
            else MarkerDeckHostNetworkAddress.findLanIpv4()
        },
        capabilitiesProvider = {
            HostCapabilities(
                udpDiscovery = udpDiscoveryAvailable,
                mdnsDiscovery = mdnsDiscoveryAvailable,
                hostDiscovery = false
            )
        },
        instanceId = instanceId,
        onShutdownRequested = {
            stop()
            onShutdownRequested()
        }
    )

    private fun startAndWait(nextServer: AndroidHostServer) {
        nextServer.start()
        for (attempt in 0 until 100) {
            if (nextServer.getListeningPort() in 1..65535) return
            try {
                Thread.sleep(10)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw IOException("Interrupted while starting embedded host")
            }
        }
        throw IOException("Embedded host did not start")
    }

    private fun startDiscovery(
        nextServer: AndroidHostServer,
        instanceId: String
    ): DiscoveryAvailability {
        acquireMulticastLock()
        val responder = MarkerDeckHostUdpResponder(
            httpPort = { nextServer.getListeningPort() },
            hostIp = { MarkerDeckHostNetworkAddress.findLanIpv4() },
            hostName = { store.hostSettings().hostName },
            instanceId = instanceId
        )
        if (responder.start()) {
            udpResponder = responder
            udpDiscoveryAvailable = true
        }

        lateinit var nextPublisher: MarkerDeckMdnsPublisher
        nextPublisher = MarkerDeckMdnsPublisher(
            context = appContext,
            hostName = { store.hostSettings().hostName },
            httpPort = { nextServer.getListeningPort() },
            instanceId = instanceId,
            onAvailabilityChanged = { available ->
                synchronized(this) {
                    if (mdnsPublisher === nextPublisher) {
                        mdnsDiscoveryAvailable = available
                        updateSessionDiscoveryAvailability()
                        if (shouldReleaseMulticastLock(
                                udpDiscoveryAvailable = udpDiscoveryAvailable,
                                mdnsDiscoveryAvailable = mdnsDiscoveryAvailable,
                                mdnsRegistrationAccepted = available
                            )
                        ) {
                            releaseMulticastLock()
                        }
                    }
                }
            }
        )
        mdnsPublisher = nextPublisher
        val mdnsStarted = nextPublisher.start()
        if (shouldReleaseMulticastLock(
                udpDiscoveryAvailable = udpDiscoveryAvailable,
                mdnsDiscoveryAvailable = mdnsDiscoveryAvailable,
                mdnsRegistrationAccepted = mdnsStarted
            )
        ) {
            releaseMulticastLock()
        }
        if (!mdnsStarted && !udpDiscoveryAvailable) {
            nextPublisher.stop()
            mdnsPublisher = null
        }
        return DiscoveryAvailability(
            udp = udpDiscoveryAvailable,
            mdns = mdnsDiscoveryAvailable
        )
    }

    private fun acquireMulticastLock() {
        val wifiManager = appContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager ?: return
        runCatching {
            multicastLock = wifiManager.createMulticastLock("MarkerDeckHost").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseMulticastLock() {
        runCatching { multicastLock?.let { if (it.isHeld) it.release() } }
        multicastLock = null
    }

    private fun stopLocked() {
        mdnsPublisher?.stop()
        mdnsPublisher = null
        mdnsDiscoveryAvailable = false
        udpResponder?.stop()
        udpResponder = null
        udpDiscoveryAvailable = false
        releaseMulticastLock()
        sseHub?.close()
        sseHub = null
        server?.stop()
        server = null
        session = null
    }

    private fun updateSessionDiscoveryAvailability() {
        val current = session ?: return
        session = current.copy(
            discoveryAvailable = udpDiscoveryAvailable || mdnsDiscoveryAvailable,
            udpDiscoveryAvailable = udpDiscoveryAvailable,
            mdnsDiscoveryAvailable = mdnsDiscoveryAvailable
        )
    }

    private data class DiscoveryAvailability(
        val udp: Boolean = false,
        val mdns: Boolean = false
    )
}

internal fun shouldReleaseMulticastLock(
    udpDiscoveryAvailable: Boolean,
    mdnsDiscoveryAvailable: Boolean,
    mdnsRegistrationAccepted: Boolean
): Boolean = !udpDiscoveryAvailable && !mdnsDiscoveryAvailable && !mdnsRegistrationAccepted
