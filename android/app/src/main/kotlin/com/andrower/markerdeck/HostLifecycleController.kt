package com.andrower.markerdeck

import android.content.Context
import android.net.wifi.WifiManager
import java.io.IOException

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
    private var multicastLock: WifiManager.MulticastLock? = null
    private var session: EmbeddedHostSession? = null

    @Synchronized
    fun hostName(): String = store.hostSettings().hostName

    @Synchronized
    fun start(mode: EmbeddedHostMode, requestedHostName: String = hostName()): EmbeddedHostSession {
        stopLocked()
        store.setHostName(requestedHostName)

        val bindAddress = if (mode == EmbeddedHostMode.LOCAL_PROJECTION) "127.0.0.1" else "0.0.0.0"
        val preferredPort = if (mode == EmbeddedHostMode.LOCAL_PROJECTION) 0 else MARKERDECK_HOST_DEFAULT_PORT
        val nextSseHub = MarkerDeckHostSseHub()
        var activeSseHub = nextSseHub
        val nextServer = createServer(mode, bindAddress, preferredPort, nextSseHub)
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
            val fallbackServer = createServer(mode, bindAddress, 0, fallbackHub)
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
            discoveryAvailable = if (mode == EmbeddedHostMode.LAN_HOST) startDiscovery(actualServer) else false
        )
        server = actualServer
        if (sseHub == null) sseHub = activeSseHub
        session = nextSession
        return nextSession
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
        nextSseHub: MarkerDeckHostSseHub
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

    private fun startDiscovery(nextServer: AndroidHostServer): Boolean {
        acquireMulticastLock()
        val responder = MarkerDeckHostUdpResponder(
            httpPort = { nextServer.getListeningPort() },
            hostIp = { MarkerDeckHostNetworkAddress.findLanIpv4() },
            hostName = { store.hostSettings().hostName }
        )
        if (!responder.start()) {
            releaseMulticastLock()
            return false
        }
        udpResponder = responder
        return true
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
        udpResponder?.stop()
        udpResponder = null
        releaseMulticastLock()
        sseHub?.close()
        sseHub = null
        server?.stop()
        server = null
        session = null
    }
}
