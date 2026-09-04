package com.andrower.markerdeck

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.util.concurrent.atomic.AtomicBoolean

/** Publishes the embedded LAN host through Android's system DNS-SD implementation. */
class MarkerDeckMdnsPublisher(
    context: Context,
    private val hostName: () -> String,
    private val httpPort: () -> Int,
    private val instanceId: String,
    private val onAvailabilityChanged: (Boolean) -> Unit = {}
) {
    private val nsdManager = context.applicationContext
        .getSystemService(Context.NSD_SERVICE) as? NsdManager
    private val lifecycleLock = Any()
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registrationGeneration = 0L
    private val available = AtomicBoolean(false)

    fun start(): Boolean {
        val registration = synchronized(lifecycleLock) {
            if (registrationListener != null) return@synchronized null
            val manager = nsdManager ?: return@synchronized RegistrationAttempt(false, null, null)
            val port = httpPort()
            if (port !in 1..65535 || !instanceId.matches(DISCOVERY_INSTANCE_PATTERN)) {
                return@synchronized RegistrationAttempt(false, null, null)
            }
            val displayName = safeMdnsName(hostName())
            val serviceInfo = NsdServiceInfo().apply {
                serviceName = "$displayName-${instanceId.takeLast(8)}"
                serviceType = MARKERDECK_MDNS_NSD_SERVICE_TYPE
                this.port = port
                setAttribute("service", MARKERDECK_MDNS_SERVICE)
                setAttribute("protocolVersion", MARKERDECK_DISCOVERY_PROTOCOL_VERSION.toString())
                setAttribute("instanceId", instanceId)
                setAttribute("name", displayName)
            }
            val generation = ++registrationGeneration
            val listener = createRegistrationListener(generation)
            registrationListener = listener
            RegistrationAttempt(true, manager, listener to serviceInfo)
        }
        if (registration == null) return synchronized(lifecycleLock) { available.get() }
        if (!registration.accepted || registration.manager == null || registration.listenerInfo == null) {
            return false
        }
        val (listener, serviceInfo) = registration.listenerInfo
        return try {
            registration.manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            true
        } catch (_: RuntimeException) {
            synchronized(lifecycleLock) {
                if (registrationListener === listener) registrationListener = null
                available.set(false)
            }
            onAvailabilityChanged(false)
            false
        }
    }

    fun stop() {
        val listener = synchronized(lifecycleLock) {
            val current = registrationListener
            registrationListener = null
            registrationGeneration += 1
            available.set(false)
            current
        }
        onAvailabilityChanged(false)
        if (listener != null) runCatching { nsdManager?.unregisterService(listener) }
    }

    fun isAvailable(): Boolean = available.get()

    private fun createRegistrationListener(generation: Long): NsdManager.RegistrationListener =
        object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {
                updateAvailability(this, generation, true)
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                updateAvailability(this, generation, false, clearRegistration = true)
            }

            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {
                updateAvailability(this, generation, false, clearRegistration = true)
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                updateAvailability(this, generation, false, clearRegistration = true)
            }
        }

    private fun updateAvailability(
        listener: NsdManager.RegistrationListener,
        generation: Long,
        value: Boolean,
        clearRegistration: Boolean = false
    ) {
        val accepted = synchronized(lifecycleLock) {
            if (!isCurrentMdnsRegistration(
                    activeGeneration = registrationGeneration,
                    callbackGeneration = generation,
                    listenerRegistered = registrationListener === listener
                )
            ) {
                false
            } else {
                if (clearRegistration) registrationListener = null
                available.set(value)
                true
            }
        }
        if (accepted) onAvailabilityChanged(value)
    }

    private fun safeMdnsName(value: String): String = value
        .filterNot(Char::isISOControl)
        .trim()
        .take(MARKERDECK_HOST_MAX_NAME_LENGTH)
        .ifEmpty { "MarkerDeck" }

    private data class RegistrationAttempt(
        val accepted: Boolean,
        val manager: NsdManager?,
        val listenerInfo: Pair<NsdManager.RegistrationListener, NsdServiceInfo>?
    )
}

internal fun isCurrentMdnsRegistration(
    activeGeneration: Long,
    callbackGeneration: Long,
    listenerRegistered: Boolean
): Boolean = listenerRegistered && activeGeneration == callbackGeneration
