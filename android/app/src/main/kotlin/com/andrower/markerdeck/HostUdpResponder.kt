package com.andrower.markerdeck

import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.util.UUID

class MarkerDeckHostUdpResponder(
    private val httpPort: () -> Int,
    private val hostIp: () -> String,
    private val hostName: () -> String,
    private val instanceId: String = UUID.randomUUID().toString().replace("-", "")
) {
    private val lifecycleLock = Any()
    private var socket: MulticastSocket? = null
    private var worker: Thread? = null

    fun start(): Boolean = synchronized(lifecycleLock) {
        if (worker?.isAlive == true) return@synchronized true
        val nextSocket = try {
            MulticastSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress("0.0.0.0", MARKERDECK_DISCOVERY_PORT))
                soTimeout = 500
                joinGroup(InetAddress.getByName(MARKERDECK_DISCOVERY_MULTICAST_ADDRESS))
            }
        } catch (_: Exception) {
            return@synchronized false
        }
        socket = nextSocket
        worker = Thread({ receiveLoop(nextSocket) }, "markerdeck-host-discovery").apply {
            isDaemon = true
            start()
        }
        true
    }

    fun stop() {
        val nextSocket: MulticastSocket?
        synchronized(lifecycleLock) {
            nextSocket = socket
            socket = null
            worker?.interrupt()
            worker = null
        }
        runCatching {
            nextSocket?.leaveGroup(InetAddress.getByName(MARKERDECK_DISCOVERY_MULTICAST_ADDRESS))
        }
        nextSocket?.close()
    }

    private fun receiveLoop(nextSocket: MulticastSocket) {
        val buffer = ByteArray(4096)
        while (!nextSocket.isClosed && !Thread.currentThread().isInterrupted) {
            val packet = DatagramPacket(buffer, buffer.size)
            try {
                nextSocket.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: SocketException) {
                break
            } catch (_: Exception) {
                continue
            }
            if (packet.address !is Inet4Address) continue
            val payload = String(packet.data, packet.offset, packet.length, Charsets.UTF_8)
            val nonce = parseDiscoveryNonce(payload) ?: continue
            val port = httpPort()
            if (port !in 1..65535) continue
            val response = buildDiscoveryResponse(
                nonce = nonce,
                name = hostName(),
                port = port,
                ip = hostIp(),
                instanceId = instanceId
            ).toString().toByteArray(Charsets.UTF_8)
            runCatching {
                nextSocket.send(DatagramPacket(response, response.size, packet.address, packet.port))
            }
        }
    }
}
