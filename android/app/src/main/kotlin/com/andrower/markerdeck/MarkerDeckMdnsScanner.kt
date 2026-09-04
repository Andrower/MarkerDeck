package com.andrower.markerdeck

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.nio.charset.Charset
import java.util.ArrayDeque

/**
 * Owns one bounded NSD browse/resolve session. Resolved records remain untrusted until the
 * caller applies [validateMdnsCandidate] and performs the nonce HTTP handshake.
 */
class MarkerDeckMdnsScanner(context: Context) {
    interface Listener {
        fun onRecord(record: MdnsDiscoveryRecord)
        fun onFinished()
    }

    companion object {
        private const val MAX_CONCURRENT_RESOLVES = 3
        private const val MAX_TXT_VALUE_BYTES = 512
    }

    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleLock = Any()
    private val resolveQueue = ArrayDeque<NsdServiceInfo>()
    private val resolvedServiceKeys = mutableSetOf<String>()
    private var discoveryListener: NsdManager.DiscoveryListener? = null
    private var listener: Listener? = null
    private var activeResolveCount = 0
    private var generation = 0L
    private var timeoutCallback: Runnable? = null

    fun start(timeoutMs: Long, nextListener: Listener): Boolean {
        stop()
        val manager = nsdManager ?: return false
        val token = synchronized(lifecycleLock) {
            generation += 1
            resolveQueue.clear()
            resolvedServiceKeys.clear()
            activeResolveCount = 0
            listener = nextListener
            generation
        }
        val nextDiscoveryListener = createDiscoveryListener(token)
        synchronized(lifecycleLock) {
            if (generation != token) return false
            discoveryListener = nextDiscoveryListener
        }
        return try {
            manager.discoverServices(
                MARKERDECK_MDNS_NSD_SERVICE_TYPE,
                NsdManager.PROTOCOL_DNS_SD,
                nextDiscoveryListener
            )
            val callback = Runnable { finish(token) }
            synchronized(lifecycleLock) {
                if (generation == token) timeoutCallback = callback
            }
            mainHandler.postDelayed(callback, timeoutMs.coerceAtLeast(1L))
            true
        } catch (_: RuntimeException) {
            finish(token)
            false
        }
    }

    fun stop() {
        val stopData = synchronized(lifecycleLock) {
            generation += 1
            val oldListener = discoveryListener
            val oldFinish = listener
            discoveryListener = null
            listener = null
            resolveQueue.clear()
            activeResolveCount = 0
            timeoutCallback?.let(mainHandler::removeCallbacks)
            timeoutCallback = null
            Triple(oldListener, oldFinish, nsdManager)
        }
        val oldDiscoveryListener = stopData.first
        if (oldDiscoveryListener != null) {
            runCatching { stopData.third?.stopServiceDiscovery(oldDiscoveryListener) }
        }
        stopData.second?.onFinished()
    }

    private fun createDiscoveryListener(token: Long): NsdManager.DiscoveryListener =
        object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!isCurrent(token) || normalizeMdnsServiceType(serviceInfo.serviceType.orEmpty()) !=
                    MARKERDECK_MDNS_SERVICE_TYPE
                ) return
                val key = "${serviceInfo.serviceType}|${serviceInfo.serviceName}"
                synchronized(lifecycleLock) {
                    if (generation != token || !resolvedServiceKeys.add(key)) return
                    resolveQueue.addLast(serviceInfo)
                }
                drainResolveQueue(token)
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) = Unit

            override fun onDiscoveryStopped(serviceType: String) {
                finish(token)
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                finish(token)
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                finish(token)
            }
        }

    private fun drainResolveQueue(token: Long) {
        val manager = nsdManager ?: return
        while (true) {
            val serviceInfo = synchronized(lifecycleLock) {
                if (generation != token || activeResolveCount >= MAX_CONCURRENT_RESOLVES ||
                    resolveQueue.isEmpty()
                ) {
                    null
                } else {
                    activeResolveCount += 1
                    resolveQueue.removeFirst()
                }
            } ?: return
            val resolveListener = createResolveListener(token)
            try {
                manager.resolveService(serviceInfo, resolveListener)
            } catch (_: RuntimeException) {
                resolveFinished(token)
            }
        }
    }

    private fun createResolveListener(token: Long): NsdManager.ResolveListener =
        object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                resolveFinished(token)
            }

            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (isCurrent(token)) toRecord(serviceInfo)?.let { listener?.onRecord(it) }
                resolveFinished(token)
            }
        }

    private fun resolveFinished(token: Long) {
        synchronized(lifecycleLock) {
            if (generation != token) return
            activeResolveCount = (activeResolveCount - 1).coerceAtLeast(0)
        }
        drainResolveQueue(token)
    }

    private fun toRecord(serviceInfo: NsdServiceInfo): MdnsDiscoveryRecord? {
        val address = serviceInfo.host?.hostAddress?.trim().orEmpty()
        if (address.isEmpty()) return null
        val txt = serviceInfo.attributes.orEmpty().mapNotNull { (key, bytes) ->
            if (key.isNullOrBlank() || bytes.size > MAX_TXT_VALUE_BYTES) return@mapNotNull null
            key to bytes.toString(Charset.forName("UTF-8"))
        }.toMap()
        return MdnsDiscoveryRecord(
            serviceType = serviceInfo.serviceType.orEmpty(),
            serviceName = serviceInfo.serviceName.orEmpty(),
            port = serviceInfo.port,
            sourceAddress = address,
            txt = txt
        )
    }

    private fun isCurrent(token: Long): Boolean = synchronized(lifecycleLock) {
        generation == token && listener != null
    }

    private fun finish(token: Long) {
        val finishData = synchronized(lifecycleLock) {
            if (generation != token) return
            generation += 1
            val oldDiscoveryListener = discoveryListener
            val oldListener = listener
            discoveryListener = null
            listener = null
            resolveQueue.clear()
            activeResolveCount = 0
            timeoutCallback?.let(mainHandler::removeCallbacks)
            timeoutCallback = null
            Triple(oldDiscoveryListener, oldListener, nsdManager)
        }
        val oldDiscoveryListener = finishData.first
        if (oldDiscoveryListener != null) {
            runCatching { finishData.third?.stopServiceDiscovery(oldDiscoveryListener) }
        }
        finishData.second?.onFinished()
    }
}
