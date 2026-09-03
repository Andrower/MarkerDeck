package com.andrower.markerdeck

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL
import java.io.BufferedReader
import java.io.InputStreamReader

class AndroidHostServerTest {
    private lateinit var server: AndroidHostServer
    private lateinit var sseHub: MarkerDeckHostSseHub

    @Before
    fun setUp() {
        sseHub = MarkerDeckHostSseHub()
        server = AndroidHostServer(
            bindAddress = "127.0.0.1",
            requestedPort = 0,
            store = MarkerDeckHostStateStore(),
            sseHub = sseHub,
            assetReader = { name -> if (name == "markerdeck-screen.html") "<html>screen</html>".toByteArray() else null },
            ipProvider = { "127.0.0.1" }
        )
        server.start()
        for (attempt in 0 until 100) {
            if (server.getListeningPort() > 0) return
            Thread.sleep(10)
        }
        error("Timed out waiting for Android host server")
    }

    @After
    fun tearDown() {
        sseHub.close()
        server.stop()
    }

    @Test
    fun servesInfoAssetsRegistrationAndStateThroughHttp() {
        val info = request("/api/info")
        assertEquals(200, info.first)
        val capabilities = JSONObject(info.second).getJSONObject("capabilities")
        assertEquals(false, capabilities.getBoolean("videoExport"))
        assertEquals(false, capabilities.getBoolean("hostDiscovery"))

        val asset = request("/markerdeck-screen.html")
        assertEquals(200, asset.first)
        assertTrue(asset.second.contains("screen"))

        val registration = request(
            path = "/api/register",
            method = "POST",
            body = """{"id":"session-1","sessionId":"session-1","deviceId":"device-1","pageInstanceId":"page-1","name":"Test screen","role":"display"}"""
        )
        assertEquals(200, registration.first)
        assertEquals("session-1", JSONObject(registration.second).getString("sessionId"))

        val state = request(
            path = "/api/state?deviceId=session-1",
            method = "POST",
            body = """{"bgColor":"#abcdef"}"""
        )
        assertEquals(200, state.first)
        val fetched = request("/api/state?deviceId=session-1")
        assertEquals("#abcdef", JSONObject(fetched.second).getString("bgColor"))
    }

    @Test
    fun publishesDevicesEventToControlAfterTargetedStateWrite() {
        val registration = request(
            path = "/api/register",
            method = "POST",
            body = """{"id":"session-1","sessionId":"session-1","deviceId":"device-1","pageInstanceId":"page-1","name":"Test screen","role":"display"}"""
        )
        assertEquals(200, registration.first)

        val events = URL("http://127.0.0.1:${server.getListeningPort()}/api/events?role=control")
            .openConnection() as HttpURLConnection
        events.connectTimeout = 2_000
        events.readTimeout = 2_000
        assertEquals(200, events.responseCode)
        BufferedReader(InputStreamReader(events.inputStream, Charsets.UTF_8)).use { reader ->
            assertEquals("connected", readSseEvent(reader).first)

            val state = request(
                path = "/api/state?deviceId=session-1",
                method = "POST",
                body = """{"bgColor":"#abcdef"}"""
            )
            assertEquals(200, state.first)

            val event = readSseEvent(reader)
            assertEquals("devices", event.first)
            assertTrue(event.second.contains("changedAt"))
        }
        events.disconnect()
    }

    @Test
    fun exposesUnsupportedVideoCapabilityInsteadOfStartingAConverter() {
        val response = request("/api/video/start", method = "POST", body = "")
        assertEquals(NanoHTTPD.Response.Status.NOT_IMPLEMENTED.requestStatus, response.first)
        assertTrue(response.second.contains("video-export-unsupported"))
    }

    private fun request(path: String, method: String = "GET", body: String? = null): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:${server.getListeningPort()}$path").openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("content-type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        return status to response
    }

    private fun readSseEvent(reader: BufferedReader): Pair<String, String> {
        while (true) {
            var eventName = ""
            val data = StringBuilder()
            while (true) {
                val line = reader.readLine() ?: error("SSE stream ended unexpectedly")
                if (line.isEmpty()) break
                when {
                    line.startsWith("event: ") -> eventName = line.removePrefix("event: ")
                    line.startsWith("data: ") -> data.append(line.removePrefix("data: "))
                }
            }
            if (eventName.isNotEmpty()) return eventName to data.toString()
        }
    }
}
