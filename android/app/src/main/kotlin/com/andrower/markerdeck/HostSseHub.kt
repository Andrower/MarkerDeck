package com.andrower.markerdeck

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.InputStream
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A bounded, blocking input stream for one SSE response. NanoHTTPD owns the
 * reader side, while the host only offers complete payloads without waiting
 * for the network writer.
 */
internal class MarkerDeckHostSseClientBuffer(
    private val maxEntries: Int = MARKERDECK_HOST_SSE_QUEUE_CAPACITY,
    private val maxBytes: Int = MARKERDECK_HOST_SSE_QUEUE_MAX_BYTES,
    private val onClosed: () -> Unit = {}
) : InputStream() {
    private val lock = Any()
    private val chunks = ArrayBlockingQueue<ByteArray>(maxEntries)
    private val closeMarker = ByteArray(0)
    private var closed = false
    private var queuedBytes = 0
    private var current: ByteArray? = null
    private var currentOffset = 0

    init {
        require(maxEntries > 0) { "maxEntries must be positive" }
        require(maxBytes > 0) { "maxBytes must be positive" }
    }

    fun offer(payload: ByteArray): Boolean {
        if (payload.isEmpty()) return true
        var notifyClosed = false
        synchronized(lock) {
            if (closed) return false
            val exceedsLimit = payload.size > maxBytes || queuedBytes > maxBytes - payload.size
            if (exceedsLimit || !chunks.offer(payload)) {
                closeLocked()
                notifyClosed = true
            } else {
                queuedBytes += payload.size
            }
        }
        if (notifyClosed) onClosed()
        return !notifyClosed
    }

    override fun read(): Int {
        val singleByte = ByteArray(1)
        return if (read(singleByte, 0, 1) < 0) -1 else singleByte[0].toInt() and 0xff
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (offset < 0 || length < 0 || offset > buffer.size - length) {
            throw IndexOutOfBoundsException()
        }
        if (length == 0) return 0

        while (true) {
            synchronized(lock) {
                if (closed) return -1
                val availableChunk = current
                if (availableChunk != null) {
                    val count = minOf(length, availableChunk.size - currentOffset)
                    availableChunk.copyInto(buffer, offset, currentOffset, currentOffset + count)
                    currentOffset += count
                    if (currentOffset >= availableChunk.size) {
                        current = null
                        currentOffset = 0
                    }
                    return count
                }
            }

            val next = try {
                chunks.take()
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                close()
                return -1
            }
            synchronized(lock) {
                if (closed || next === closeMarker) return -1
                queuedBytes = (queuedBytes - next.size).coerceAtLeast(0)
                current = next
                currentOffset = 0
            }
        }
    }

    override fun available(): Int = synchronized(lock) {
        if (closed) return@synchronized 0
        val currentBytes = current?.let { it.size - currentOffset } ?: 0
        (currentBytes + queuedBytes).coerceAtMost(Int.MAX_VALUE)
    }

    override fun close() {
        var notifyClosed = false
        synchronized(lock) {
            if (!closed) {
                closeLocked()
                notifyClosed = true
            }
        }
        if (notifyClosed) onClosed()
    }

    fun disconnect() {
        synchronized(lock) {
            if (!closed) closeLocked()
        }
    }

    fun isClosedForTest(): Boolean = synchronized(lock) { closed }

    private fun closeLocked() {
        closed = true
        chunks.clear()
        queuedBytes = 0
        current = null
        currentOffset = 0
        chunks.offer(closeMarker)
    }
}

class MarkerDeckHostSseHub(
    private val clientQueueCapacity: Int = MARKERDECK_HOST_SSE_QUEUE_CAPACITY,
    private val clientQueueMaxBytes: Int = MARKERDECK_HOST_SSE_QUEUE_MAX_BYTES
) {
    private class Client(
        val role: String,
        val sessionId: String,
        val pageInstanceId: String,
        val input: MarkerDeckHostSseClientBuffer
    ) {
        private val closed = AtomicBoolean(false)

        fun enqueue(payload: String): Boolean =
            if (closed.get()) false else input.offer(payload.toByteArray(Charsets.UTF_8))

        fun close() {
            if (closed.compareAndSet(false, true)) input.disconnect()
        }
    }

    private val clients = Collections.synchronizedSet(LinkedHashSet<Client>())
    private val eventId = AtomicLong(1L)
    private val eventLock = Any()
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "markerdeck-host-sse-heartbeat").apply { isDaemon = true }
    }
    @Volatile
    private var closed = false

    init {
        heartbeatExecutor.scheduleAtFixedRate(
            { publishRaw(": heartbeat\n\n") },
            15,
            15,
            TimeUnit.SECONDS
        )
    }

    fun connect(
        role: String,
        sessionId: String,
        pageInstanceId: String
    ): NanoHTTPD.Response {
        var registeredClient: Client? = null
        val input = MarkerDeckHostSseClientBuffer(
            maxEntries = clientQueueCapacity,
            maxBytes = clientQueueMaxBytes,
            onClosed = { registeredClient?.let(::remove) }
        )
        val client = Client(
            role = if (role == "display") "display" else "control",
            sessionId = sessionId.take(MARKERDECK_HOST_MAX_ID_LENGTH),
            pageInstanceId = pageInstanceId.take(MARKERDECK_HOST_MAX_ID_LENGTH),
            input = input
        )
        registeredClient = client
        val response = NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "text/event-stream; charset=utf-8",
            input
        )
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("X-Accel-Buffering", "no")
        synchronized(eventLock) {
            if (closed) {
                client.close()
            } else {
                clients += client
                if (!client.enqueue("retry: 1000\n\n") || !client.enqueueEvent("connected", JSONObject()
                        .put("role", client.role)
                        .put("sessionId", client.sessionId))) {
                    remove(client)
                }
            }
        }
        return response
    }

    fun publish(
        event: String,
        data: JSONObject,
        role: String? = null,
        targetSessionIds: Collection<String>? = null
    ) {
        val targets = targetSessionIds?.map { it.take(MARKERDECK_HOST_MAX_ID_LENGTH) }?.toSet()
        synchronized(eventLock) {
            val snapshot = synchronized(clients) { clients.toList() }
            snapshot.forEach { client ->
                if (role != null && client.role != role) return@forEach
                if (targets != null && client.sessionId !in targets) return@forEach
                if (!client.enqueueEvent(event, data)) remove(client)
            }
        }
    }

    fun hasDisplaySession(sessionId: String, pageInstanceId: String): Boolean {
        val cleanSessionId = sessionId.take(MARKERDECK_HOST_MAX_ID_LENGTH)
        val cleanPageInstanceId = pageInstanceId.take(MARKERDECK_HOST_MAX_ID_LENGTH)
        return synchronized(clients) {
            clients.any {
                it.role == "display" &&
                    it.sessionId == cleanSessionId &&
                    it.pageInstanceId.isNotEmpty() &&
                    it.pageInstanceId != cleanPageInstanceId
            }
        }
    }

    fun clientCount(): Int = synchronized(clients) { clients.size }

    fun close() {
        heartbeatExecutor.shutdownNow()
        val snapshot = synchronized(eventLock) {
            closed = true
            val current = synchronized(clients) { clients.toList() }
            clients.clear()
            current
        }
        snapshot.forEach(Client::close)
    }

    private fun remove(client: Client) {
        if (clients.remove(client)) client.close()
    }

    private fun publishRaw(payload: String) {
        synchronized(eventLock) {
            val snapshot = synchronized(clients) { clients.toList() }
            snapshot.forEach { client ->
                if (!client.enqueue(payload)) remove(client)
            }
        }
    }

    private fun Client.enqueueEvent(event: String, data: JSONObject): Boolean =
        enqueue("id: ${eventId.getAndIncrement()}\nevent: $event\ndata: ${data}\n\n")
}

internal class MarkerDeckHostDeviceChangeDebouncer(
    private val delayMs: Long = MARKERDECK_HOST_DEVICE_EVENT_DEBOUNCE_MS,
    private val emit: () -> Unit
) : AutoCloseable {
    private val lock = Any()
    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "markerdeck-host-device-events").apply { isDaemon = true }
    }
    private var pending: ScheduledFuture<*>? = null
    private var generation = 0L
    private var closed = false

    fun schedule() {
        synchronized(lock) {
            if (closed || pending != null) return
            val token = ++generation
            pending = executor.schedule({
                synchronized(lock) {
                    if (closed || token != generation) return@synchronized
                    pending = null
                    emit()
                }
            }, delayMs, TimeUnit.MILLISECONDS)
        }
    }

    fun emitNow() {
        synchronized(lock) {
            if (closed) return
            generation += 1
            pending?.cancel(false)
            pending = null
            emit()
        }
    }

    override fun close() {
        synchronized(lock) {
            if (closed) return
            closed = true
            generation += 1
            pending?.cancel(false)
            pending = null
        }
        executor.shutdownNow()
    }
}
