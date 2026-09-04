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
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
            assetReader = { name ->
                when (name) {
                    "markerdeck-screen.html" -> "<html>screen</html>".toByteArray()
                    "markerdeck-visual-state.js" -> "visual".toByteArray()
                    "markerdeck-lock-flow.js" -> "lock-flow".toByteArray()
                    else -> null
                }
            },
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
        assertEquals(false, capabilities.getBoolean("udpDiscovery"))
        assertEquals(false, capabilities.getBoolean("mdnsDiscovery"))

        val asset = request("/markerdeck-screen.html")
        assertEquals(200, asset.first)
        assertTrue(asset.second.contains("screen"))

        val visualAsset = request("/markerdeck-visual-state.js")
        assertEquals(200, visualAsset.first)
        assertEquals("visual", visualAsset.second)

        val registration = request(
            path = "/api/register",
            method = "POST",
            body = """{"id":"session-1","sessionId":"session-1","deviceId":"device-1","pageInstanceId":"page-1","name":"中文投放屏","role":"display"}"""
        )
        assertEquals(200, registration.first)
        val registrationJson = JSONObject(registration.second)
        assertEquals("session-1", registrationJson.getString("sessionId"))
        assertEquals("中文投放屏", registrationJson.getString("name"))
        assertEquals("100", registrationJson.getJSONObject("state").getString("overallBrightness"))
        assertEquals("none", registrationJson.getString("globalLockCommand"))

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
    fun preservesUtf8ChineseNameThroughDeviceNameHttpEndpoint() {
        val registration = request(
            path = "/api/register",
            method = "POST",
            body = """{"id":"name-session","sessionId":"name-session","deviceId":"name-device","name":"入口屏幕一号","role":"display"}"""
        )
        assertEquals(200, registration.first)
        assertEquals("入口屏幕一号", JSONObject(registration.second).getString("name"))

        val renamed = request(
            path = "/api/device-name",
            method = "POST",
            body = """{"id":"name-session","name":"入口屏幕二号"}"""
        )
        assertEquals(200, renamed.first)
        assertEquals("入口屏幕二号", JSONObject(renamed.second).getString("name"))

        val devices = JSONObject(request("/api/devices").second).getJSONArray("devices")
        assertEquals("入口屏幕二号", devices.getJSONObject(0).getString("name"))
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
    fun deliversConnectedAndConsecutiveTargetedLockEventsWithoutWaitingForHeartbeat() {
        val sessionId = "sse-lock-latency-session"
        val registration = request(
            path = "/api/register",
            method = "POST",
            body = """{"id":"$sessionId","sessionId":"$sessionId","deviceId":"sse-lock-latency-device","pageInstanceId":"sse-lock-latency-page","name":"SSE 延迟测试屏","role":"display"}"""
        )
        assertEquals(200, registration.first)

        val events = URL("http://127.0.0.1:${server.getListeningPort()}/api/events?role=display&sessionId=$sessionId&pageInstanceId=sse-lock-latency-page")
            .openConnection() as HttpURLConnection
        events.connectTimeout = 2_000
        events.readTimeout = 2_000
        assertEquals(200, events.responseCode)
        BufferedReader(InputStreamReader(events.inputStream, Charsets.UTF_8)).use { reader ->
            val connectedStartedAt = System.nanoTime()
            assertEquals("connected", readSseEvent(reader).first)
            assertTrue("connected event was not immediately readable", elapsedMillis(connectedStartedAt) < 500)

            val executor = Executors.newSingleThreadExecutor()
            try {
                val firstStartedAt = System.nanoTime()
                val firstPost = executor.submit<Pair<Int, String>> {
                    request(
                        path = "/api/lock-command",
                        method = "POST",
                        body = """{"ids":["$sessionId"],"enabled":true}"""
                    )
                }
                val firstEvent = readSseEvent(reader)
                assertTrue("first lock-command was delayed", elapsedMillis(firstStartedAt) < 500)
                assertEquals("lock-command", firstEvent.first)
                assertEquals(true, JSONObject(firstEvent.second).getBoolean("enabled"))
                assertEquals(200, firstPost.get(2, TimeUnit.SECONDS).first)

                val secondStartedAt = System.nanoTime()
                val secondPost = executor.submit<Pair<Int, String>> {
                    request(
                        path = "/api/lock-command",
                        method = "POST",
                        body = """{"ids":["$sessionId"],"enabled":false}"""
                    )
                }
                val secondEvent = readSseEvent(reader)
                assertTrue("consecutive lock-command was delayed", elapsedMillis(secondStartedAt) < 500)
                assertEquals("lock-command", secondEvent.first)
                assertEquals(false, JSONObject(secondEvent.second).getBoolean("enabled"))
                assertEquals(200, secondPost.get(2, TimeUnit.SECONDS).first)
            } finally {
                executor.shutdownNow()
            }
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
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val status = connection.responseCode
        val stream = if (status >= 400) connection.errorStream else connection.inputStream
        val response = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
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

    private fun elapsedMillis(startedAt: Long): Long =
        TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt)
}
