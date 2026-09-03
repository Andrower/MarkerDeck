package com.andrower.markerdeck

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.Collections
import java.util.LinkedHashSet
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

class MarkerDeckHostSseHub {
    private class ClientInputStream(
        var onClosed: (() -> Unit)? = null
    ) : PipedInputStream(64 * 1024) {
        override fun close() {
            try {
                super.close()
            } finally {
                onClosed?.invoke()
            }
        }
    }

    private class Client(
        val role: String,
        val sessionId: String,
        val pageInstanceId: String,
        val input: ClientInputStream,
        val output: PipedOutputStream
    ) {
        private var closed = false

        @Synchronized
        fun write(payload: String): Boolean {
            if (closed) return false
            return try {
                output.write(payload.toByteArray(Charsets.UTF_8))
                output.flush()
                true
            } catch (_: IOException) {
                false
            }
        }

        @Synchronized
        fun close() {
            if (closed) return
            closed = true
            runCatching { output.close() }
            runCatching { input.close() }
        }
    }

    private val clients = Collections.synchronizedSet(LinkedHashSet<Client>())
    private val eventId = AtomicLong(1L)
    private val heartbeatExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "markerdeck-host-sse-heartbeat").apply { isDaemon = true }
    }

    init {
        heartbeatExecutor.scheduleAtFixedRate(
            { publishRaw(": heartbeat\n\n") },
            15,
            15,
            java.util.concurrent.TimeUnit.SECONDS
        )
    }

    fun connect(
        role: String,
        sessionId: String,
        pageInstanceId: String
    ): NanoHTTPD.Response {
        var registeredClient: Client? = null
        val input = ClientInputStream { registeredClient?.let(::remove) }
        val output = PipedOutputStream(input)
        val client = Client(
            role = if (role == "display") "display" else "control",
            sessionId = sessionId.take(MARKERDECK_HOST_MAX_ID_LENGTH),
            pageInstanceId = pageInstanceId.take(MARKERDECK_HOST_MAX_ID_LENGTH),
            input = input,
            output = output
        )
        registeredClient = client
        clients += client
        val response = NanoHTTPD.newChunkedResponse(
            NanoHTTPD.Response.Status.OK,
            "text/event-stream; charset=utf-8",
            input
        )
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Connection", "keep-alive")
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("X-Accel-Buffering", "no")
        if (!client.write("retry: 1000\n\n")) {
            remove(client)
        } else if (!client.writeEvent("connected", JSONObject()
                .put("role", client.role)
                .put("sessionId", client.sessionId))) {
            remove(client)
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
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { client ->
            if (role != null && client.role != role) return@forEach
            if (targets != null && client.sessionId !in targets) return@forEach
            if (!client.writeEvent(event, data)) remove(client)
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
        val snapshot = synchronized(clients) {
            val current = clients.toList()
            clients.clear()
            current
        }
        snapshot.forEach(Client::close)
    }

    private fun remove(client: Client) {
        if (clients.remove(client)) client.close()
    }

    private fun publishRaw(payload: String) {
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { client ->
            if (!client.write(payload)) remove(client)
        }
    }

    private fun Client.writeEvent(event: String, data: JSONObject): Boolean {
        val payload = "id: ${eventId.getAndIncrement()}\nevent: $event\ndata: ${data}\n\n"
        return write(payload)
    }
}
