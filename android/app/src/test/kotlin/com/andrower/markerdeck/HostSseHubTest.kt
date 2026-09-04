package com.andrower.markerdeck

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class HostSseHubTest {
    @Test
    fun bufferMakesConnectedAndConsecutiveEventsReadableAsSoonAsTheyAreOffered() {
        val buffer = MarkerDeckHostSseClientBuffer(maxEntries = 4, maxBytes = 1024)
        val started = CountDownLatch(1)
        val completed = CountDownLatch(1)
        val received = ByteArrayOutputStream()
        val reader = Thread {
            started.countDown()
            val bytes = ByteArray(256)
            while (received.size() < 32) {
                val count = buffer.read(bytes, 0, bytes.size)
                if (count < 0) break
                received.write(bytes, 0, count)
            }
            completed.countDown()
        }
        reader.start()
        assertTrue(started.await(1, TimeUnit.SECONDS))
        assertFalse(completed.await(20, TimeUnit.MILLISECONDS))

        val startedAt = System.nanoTime()
        assertTrue(buffer.offer("connected\n\n".toByteArray(Charsets.UTF_8)))
        assertTrue(buffer.offer("event: lock-command\n\n".toByteArray(Charsets.UTF_8)))
        assertTrue(completed.await(500, TimeUnit.MILLISECONDS))
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 500)
        assertEquals("connected\n\nevent: lock-command\n\n", received.toString(Charsets.UTF_8.name()))
        buffer.close()
    }

    @Test
    fun closesAndCleansAClientWhenItsBoundedQueueIsFull() {
        val closedCount = AtomicInteger(0)
        val buffer = MarkerDeckHostSseClientBuffer(
            maxEntries = 2,
            maxBytes = 1024,
            onClosed = { closedCount.incrementAndGet() }
        )

        assertTrue(buffer.offer("one".toByteArray(Charsets.UTF_8)))
        assertTrue(buffer.offer("two".toByteArray(Charsets.UTF_8)))
        assertFalse(buffer.offer("three".toByteArray(Charsets.UTF_8)))
        assertTrue(buffer.isClosedForTest())
        assertEquals(1, closedCount.get())
        assertFalse(buffer.offer("after-close".toByteArray(Charsets.UTF_8)))
        assertEquals(-1, buffer.read())
        buffer.close()
        assertEquals(1, closedCount.get())
    }

    @Test
    fun slowClientIsDisconnectedWithoutDelayingAnotherClient() {
        val hub = MarkerDeckHostSseHub(clientQueueCapacity = 2, clientQueueMaxBytes = 4096)
        try {
            val slow = hub.connect("display", "slow-session", "slow-page")
                .data as MarkerDeckHostSseClientBuffer
            val fast = hub.connect("display", "fast-session", "fast-page")
                .data as MarkerDeckHostSseClientBuffer
            drain(slow)
            drain(fast)

            val payload = JSONObject().put("payload", "x".repeat(128))
            val startedAt = System.nanoTime()
            val fastPayload = StringBuilder()
            repeat(3) {
                hub.publish(
                    event = "lock-command",
                    data = payload,
                    role = "display"
                )
                fastPayload.append(readAvailable(fast))
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)

            assertTrue("publish was blocked by a slow client", elapsedMs < 500)
            assertTrue(slow.isClosedForTest())
            assertEquals("unexpected client count", 1, hub.clientCount())
            assertEquals(3, Regex("event: lock-command").findAll(fastPayload).count())
        } finally {
            hub.close()
        }
    }

    @Test
    fun coalescesRepeatedDeviceChangeSchedulesAndKeepsImmediateFlush() {
        val emissions = AtomicInteger(0)
        val emitted = CountDownLatch(1)
        val debouncer = MarkerDeckHostDeviceChangeDebouncer(delayMs = 40) {
            emissions.incrementAndGet()
            emitted.countDown()
        }
        try {
            repeat(20) { debouncer.schedule() }
            assertTrue(emitted.await(1, TimeUnit.SECONDS))
            assertEquals(1, emissions.get())

            debouncer.schedule()
            debouncer.emitNow()
            assertEquals(2, emissions.get())
            Thread.sleep(80)
            assertEquals(2, emissions.get())
        } finally {
            debouncer.close()
        }
    }

    private fun drain(input: MarkerDeckHostSseClientBuffer) {
        while (input.available() > 0) {
            val bytes = ByteArray(minOf(input.available(), 1024))
            if (input.read(bytes) < 0) return
        }
    }

    private fun readAvailable(input: MarkerDeckHostSseClientBuffer): String {
        val output = ByteArrayOutputStream()
        val bytes = ByteArray(1024)
        while (input.available() > 0) {
            val count = input.read(bytes)
            if (count < 0) break
            output.write(bytes, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }
}
