package com.andrower.markerdeck

import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.nio.charset.StandardCharsets

class AndroidHostServer(
    bindAddress: String,
    requestedPort: Int,
    private val store: MarkerDeckHostStateStore,
    private val sseHub: MarkerDeckHostSseHub,
    private val assetReader: (String) -> ByteArray?,
    private val ipProvider: () -> String,
    private val capabilitiesProvider: () -> HostCapabilities = { HostCapabilities() },
    private val onShutdownRequested: () -> Unit = {}
) : NanoHTTPD(bindAddress, requestedPort) {
    private class BadRequest(message: String) : RuntimeException(message)

    private val staticAssets = mapOf(
        "/markerdeck-screen.html" to Asset("markerdeck-screen.html", "text/html; charset=utf-8"),
        "/markerdeck-launch.html" to Asset("markerdeck-launch.html", "text/html; charset=utf-8"),
        "/markerdeck-base.css" to Asset("markerdeck-base.css", "text/css; charset=utf-8"),
        "/markerdeck-control.css" to Asset("markerdeck-control.css", "text/css; charset=utf-8"),
        "/markerdeck-mobile.css" to Asset("markerdeck-mobile.css", "text/css; charset=utf-8"),
        "/markerdeck-core.js" to Asset("markerdeck-core.js", "text/javascript; charset=utf-8"),
        "/markerdeck-api.js" to Asset("markerdeck-api.js", "text/javascript; charset=utf-8"),
        "/markerdeck-canvas.js" to Asset("markerdeck-canvas.js", "text/javascript; charset=utf-8"),
        "/markerdeck-export.js" to Asset("markerdeck-export.js", "text/javascript; charset=utf-8"),
        "/markerdeck-presets.js" to Asset("markerdeck-presets.js", "text/javascript; charset=utf-8"),
        "/markerdeck-devices.js" to Asset("markerdeck-devices.js", "text/javascript; charset=utf-8"),
        "/markerdeck-projection.js" to Asset("markerdeck-projection.js", "text/javascript; charset=utf-8"),
        "/markerdeck-settings.js" to Asset("markerdeck-settings.js", "text/javascript; charset=utf-8"),
        "/markerdeck-launcher.js" to Asset("markerdeck-launcher.js", "text/javascript; charset=utf-8"),
        "/markerdeck-bootstrap.js" to Asset("markerdeck-bootstrap.js", "text/javascript; charset=utf-8")
    )

    private data class Asset(val fileName: String, val contentType: String)

    override fun serve(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response = try {
        route(session)
    } catch (error: BadRequest) {
        jsonResponse(
            NanoHTTPD.Response.Status.BAD_REQUEST,
            JSONObject().put("ok", false).put("error", error.message.orEmpty())
        )
    } catch (_: Exception) {
        jsonResponse(
            NanoHTTPD.Response.Status.INTERNAL_ERROR,
            JSONObject().put("ok", false).put("error", "Internal host error")
        )
    }

    private fun route(session: NanoHTTPD.IHTTPSession): NanoHTTPD.Response {
        val method = session.method
        val path = session.uri
        if (method == NanoHTTPD.Method.OPTIONS) return emptyResponse(NanoHTTPD.Response.Status.NO_CONTENT)

        if (path == "/") return redirect(MARKERDECK_HOST_LAUNCH_PAGE)
        if (path == "/display" || path == "/control") {
            val mode = if (path == "/display") "display" else "control"
            return redirect("$MARKERDECK_HOST_SCREEN_PAGE?mode=$mode")
        }

        if (path == "/api/info" && method == NanoHTTPD.Method.GET) {
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                buildHostInfo(
                    ip = ipProvider(),
                    port = listeningPort(),
                    name = store.hostSettings().hostName,
                    capabilities = capabilitiesProvider()
                ).toJson()
            )
        }

        if (path == "/api/discovery" && method == NanoHTTPD.Method.GET) {
            val nonce = query(session, "nonce").trim()
            if (!nonce.matches(Regex("^[A-Za-z0-9_-]{8,80}$"))) {
                throw BadRequest("Invalid discovery nonce")
            }
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                buildDiscoveryResponse(
                    nonce = nonce,
                    name = store.hostSettings().hostName,
                    port = listeningPort(),
                    ip = ipProvider(),
                    instanceId = instanceId
                )
            )
        }

        if (path == "/api/events" && method == NanoHTTPD.Method.GET) {
            val role = if (query(session, "role") == "display") "display" else "control"
            return sseHub.connect(
                role = role,
                sessionId = query(session, "sessionId").take(MARKERDECK_HOST_MAX_ID_LENGTH),
                pageInstanceId = query(session, "pageInstanceId").take(MARKERDECK_HOST_MAX_ID_LENGTH)
            )
        }

        if (path == "/api/shutdown" && method == NanoHTTPD.Method.POST) {
            val response = jsonResponse(NanoHTTPD.Response.Status.OK, JSONObject().put("ok", true))
            Thread({
                try {
                    Thread.sleep(80)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
                onShutdownRequested()
            }, "markerdeck-host-shutdown").apply {
                isDaemon = true
                start()
            }
            return response
        }

        if (path == "/api/video" || path == "/api/video/start" ||
            path == "/api/video/status" || path == "/api/video/result"
        ) {
            return jsonResponse(
                NanoHTTPD.Response.Status.NOT_IMPLEMENTED,
                JSONObject()
                    .put("ok", false)
                    .put("error", "unsupported")
                    .put("code", "video-export-unsupported")
            )
        }

        if (path == "/api/register" && method == NanoHTTPD.Method.POST) {
            val body = readJsonBody(session)
            val result = store.register(
                HostRegistrationRequest(
                    legacyId = cleanId(body.optString("id")),
                    sessionId = cleanId(body.optString("sessionId")),
                    deviceId = cleanId(body.optString("deviceId")),
                    pageInstanceId = cleanId(body.optString("pageInstanceId")),
                    name = body.optString("name"),
                    updateName = body.optBoolean("updateName", false),
                    role = body.optString("role", "display"),
                    width = body.optInt("width", 0),
                    height = body.optInt("height", 0),
                    dpr = body.optDouble("dpr", 1.0),
                    userAgent = body.optString("userAgent", header(session, "user-agent")),
                    state = jsonObjectToValueMap(body.optJSONObject("state"))
                )
            ) { sessionId, pageInstanceId -> sseHub.hasDisplaySession(sessionId, pageInstanceId) }
            if (!result.ok) throw BadRequest(result.error)
            if (result.deviceListChanged) {
                publishDevicesChanged()
            }
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("sessionId", result.sessionId)
                    .put("name", result.name)
                    .put("state", hostStateToJson(result.state))
                    .put("globalLockCommandId", result.globalLockCommandId)
            )
        }

        if (path == "/api/devices" && method == NanoHTTPD.Method.GET) {
            if (store.pruneExpiredDevices().isNotEmpty()) publishDevicesChanged()
            val devices = JSONArray()
            store.getDevices().forEach { devices.put(it.toJson()) }
            return jsonResponse(NanoHTTPD.Response.Status.OK, JSONObject().put("devices", devices))
        }

        if (path == "/api/device-settings") {
            if (method == NanoHTTPD.Method.GET) {
                val settings = store.deviceSettings()
                return jsonResponse(
                    NanoHTTPD.Response.Status.OK,
                    JSONObject()
                        .put("deviceRetentionMs", settings.deviceRetentionMs)
                        .put("deviceOfflineMs", MARKERDECK_HOST_OFFLINE_MS)
                )
            }
            if (method == NanoHTTPD.Method.POST) {
                val settings = store.deviceSettings()
                val body = readJsonBody(session)
                val deletedIds = store.updateDeviceRetention(
                    body.optLong("deviceRetentionMs", settings.deviceRetentionMs)
                )
                if (deletedIds.isNotEmpty()) publishDevicesChanged()
                val updated = store.deviceSettings()
                return jsonResponse(
                    NanoHTTPD.Response.Status.OK,
                    JSONObject()
                        .put("ok", true)
                        .put("deviceRetentionMs", updated.deviceRetentionMs)
                        .put("deletedIds", JSONArray(deletedIds))
                )
            }
        }

        if (path == "/api/devices/delete" && method == NanoHTTPD.Method.POST) {
            val body = readJsonBody(session)
            val ids = jsonStringList(body.optJSONArray("ids"), 100)
            val deletedIds = store.deleteDevices(ids, body.optBoolean("allOffline", false))
            if (deletedIds.isNotEmpty()) publishDevicesChanged()
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                JSONObject().put("ok", true).put("deletedIds", JSONArray(deletedIds))
            )
        }

        if (path == "/api/device-name" && method == NanoHTTPD.Method.POST) {
            val body = readJsonBody(session)
            val id = cleanId(body.optString("id"))
            val name = body.optString("name")
            if (id.isEmpty()) throw BadRequest("Missing device id")
            if (safeHostText(name, MARKERDECK_HOST_MAX_NAME_LENGTH).isEmpty()) {
                throw BadRequest("Missing device name")
            }
            val mutation = store.renameDevice(id, name)
            if (!mutation.found) return textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Device not found")
            if (mutation.updated > 0) publishDevicesChanged()
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                JSONObject().put("ok", true).put("name", mutation.value).put("updated", mutation.updated)
            )
        }

        if (path == "/api/device-group" && method == NanoHTTPD.Method.POST) {
            val body = readJsonBody(session)
            val ids = jsonStringList(body.optJSONArray("ids"), 100)
            if (ids.isEmpty()) throw BadRequest("Missing device ids")
            val mutation = store.assignGroup(ids, body.optString("group"))
            if (mutation.updated > 0) publishDevicesChanged()
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                JSONObject().put("ok", true).put("group", mutation.value).put("updated", mutation.updated)
            )
        }

        if (path == "/api/state") {
            val deviceId = query(session, "deviceId").takeIf { it.isNotBlank() }
            if (method == NanoHTTPD.Method.GET) {
                return jsonResponse(NanoHTTPD.Response.Status.OK, hostStateToJson(store.getState(deviceId)))
            }
            if (method == NanoHTTPD.Method.POST) {
                val change = store.postState(deviceId, jsonObjectToValueMap(readJsonBody(session)))
                val event = JSONObject()
                    .put("sessionId", change.sessionId)
                    .put("state", hostStateToJson(change.state))
                sseHub.publish(
                    event = "state",
                    data = event,
                    role = "display",
                    targetSessionIds = change.targetSessionIds.takeIf { !change.broadcast }
                )
                if (deviceId != null) publishDevicesChanged()
                return jsonResponse(NanoHTTPD.Response.Status.OK, JSONObject().put("ok", true))
            }
        }

        if (path == "/api/lock-broadcast" && method == NanoHTTPD.Method.POST) {
            val enabled = readJsonBody(session).optBoolean("enabled", false)
            val delivery = store.broadcastLock(enabled)
            publishLockDelivery(delivery)
            return lockResponse(delivery, includeCommand = true)
        }

        if (path == "/api/lock-command" && method == NanoHTTPD.Method.POST) {
            val body = readJsonBody(session)
            val ids = jsonStringList(body.optJSONArray("ids"), 200)
            if (ids.isEmpty()) throw BadRequest("Missing target ids")
            val delivery = store.createLockCommand(ids, body.optBoolean("enabled", false))
            publishLockDelivery(delivery)
            return lockResponse(delivery)
        }

        if (path == "/api/lock-ack" && method == NanoHTTPD.Method.POST) {
            val body = readJsonBody(session)
            val commandId = cleanId(body.optString("commandId"))
            val sessionId = cleanId(body.optString("sessionId"))
            val status = store.acknowledgeLock(
                commandId = commandId,
                sessionId = sessionId,
                ok = body.optBoolean("ok", true),
                locked = body.optBoolean("locked", false),
                error = body.optString("error")
            ) ?: return textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Lock command not found")
            sseHub.publish("lock-ack", status.toJson(), role = "control")
            publishDevicesChanged()
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                JSONObject().put("ok", true).putAll(status.toJson())
            )
        }

        if (path == "/api/lock-command/status" && method == NanoHTTPD.Method.GET) {
            val status = store.lockStatus(query(session, "id"))
                ?: return textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Lock command not found")
            return jsonResponse(NanoHTTPD.Response.Status.OK, status.toJson())
        }

        if (path == "/api/presets" && method == NanoHTTPD.Method.GET) {
            return jsonResponse(NanoHTTPD.Response.Status.OK, presetsJson())
        }

        if (path == "/api/presets" && method == NanoHTTPD.Method.POST) {
            val body = readJsonBody(session)
            val preset = store.savePreset(body.optString("name"), jsonObjectToValueMap(body.optJSONObject("state")))
                ?: throw BadRequest("Missing preset name")
            return jsonResponse(
                NanoHTTPD.Response.Status.OK,
                JSONObject()
                    .put("ok", true)
                    .put("preset", hostPresetToJson(preset))
                    .put("presets", presetsArray())
            )
        }

        if (path == "/api/presets/delete" && method == NanoHTTPD.Method.POST) {
            val id = cleanId(readJsonBody(session).optString("id"))
            if (!store.deletePreset(id)) return textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Preset not found")
            return jsonResponse(NanoHTTPD.Response.Status.OK, JSONObject().put("ok", true).put("presets", presetsArray()))
        }

        if (path == "/qr.svg" && method == NanoHTTPD.Method.GET) {
            val text = query(session, "text").ifBlank {
                buildHostInfo(ipProvider(), listeningPort(), store.hostSettings().hostName).url
            }
            return try {
                textResponse(NanoHTTPD.Response.Status.OK, buildHostQrSvg(text), "image/svg+xml; charset=utf-8")
            } catch (_: IllegalArgumentException) {
                textResponse(NanoHTTPD.Response.Status.BAD_REQUEST, "Invalid QR value")
            }
        }

        val asset = staticAssets[path] ?: return textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Not found")
        if (method != NanoHTTPD.Method.GET) return textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Not found")
        val bytes = assetReader(asset.fileName) ?: return textResponse(NanoHTTPD.Response.Status.NOT_FOUND, "Not found")
        val response = NanoHTTPD.newFixedLengthResponse(
            NanoHTTPD.Response.Status.OK,
            asset.contentType,
            ByteArrayInputStream(bytes),
            bytes.size.toLong()
        )
        return decorate(response)
    }

    private fun publishLockDelivery(delivery: HostLockDelivery) {
        sseHub.publish(
            event = "lock-command",
            data = JSONObject().put("commandId", delivery.commandId).put("enabled", delivery.enabled),
            role = "display",
            targetSessionIds = delivery.targetSessionIds
        )
        sseHub.publish("lock-ack", delivery.status.toJson(), role = "control")
    }

    private fun lockResponse(delivery: HostLockDelivery, includeCommand: Boolean = false): NanoHTTPD.Response {
        val body = delivery.status.toJson().put("ok", true)
        if (includeCommand) body.put("command", if (delivery.enabled) "lock" else "unlock")
        return jsonResponse(NanoHTTPD.Response.Status.OK, body)
    }

    private fun publishDevicesChanged() {
        sseHub.publish("devices", JSONObject().put("changedAt", System.currentTimeMillis()), role = "control")
    }

    private fun presetsJson(): JSONObject = JSONObject().put("presets", presetsArray())

    private fun presetsArray(): JSONArray = JSONArray().apply {
        store.getPresets().forEach { put(hostPresetToJson(it)) }
    }

    private fun readJsonBody(session: NanoHTTPD.IHTTPSession): JSONObject {
        val contentLength = header(session, "content-length").toLongOrNull()
        if (contentLength != null && contentLength > MARKERDECK_HOST_MAX_BODY_BYTES) {
            throw BadRequest("Request body too large")
        }
        val files = HashMap<String, String>()
        try {
            session.parseBody(files)
        } catch (_: Exception) {
            throw BadRequest("Invalid request body")
        }
        val raw = files["postData"].orEmpty()
        if (raw.toByteArray(StandardCharsets.UTF_8).size > MARKERDECK_HOST_MAX_BODY_BYTES) {
            throw BadRequest("Request body too large")
        }
        return if (raw.isBlank()) JSONObject() else try {
            JSONObject(raw)
        } catch (_: Exception) {
            throw BadRequest("Invalid JSON body")
        }
    }

    private fun jsonStringList(array: JSONArray?, limit: Int): List<String> {
        if (array == null) return emptyList()
        return buildList {
            for (index in 0 until minOf(array.length(), limit)) {
                val value = cleanId(array.optString(index))
                if (value.isNotEmpty()) add(value)
            }
        }
    }

    private fun query(session: NanoHTTPD.IHTTPSession, name: String): String =
        session.parms[name].orEmpty()

    private fun header(session: NanoHTTPD.IHTTPSession, name: String): String =
        session.headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value.orEmpty()

    private fun cleanId(value: String): String = safeHostText(value, MARKERDECK_HOST_MAX_ID_LENGTH)

    private fun listeningPort(): Int = getListeningPort().takeIf { it > 0 } ?: MARKERDECK_HOST_DEFAULT_PORT

    private fun redirect(location: String): NanoHTTPD.Response = decorate(
        NanoHTTPD.newFixedLengthResponse(NanoHTTPD.Response.Status.REDIRECT, "text/plain; charset=utf-8", "")
            .also { it.addHeader("Location", location) }
    )

    private fun emptyResponse(status: NanoHTTPD.Response.Status): NanoHTTPD.Response =
        decorate(NanoHTTPD.newFixedLengthResponse(status, "text/plain; charset=utf-8", ""))

    private fun textResponse(
        status: NanoHTTPD.Response.Status,
        body: String,
        contentType: String = "text/plain; charset=utf-8"
    ): NanoHTTPD.Response = decorate(NanoHTTPD.newFixedLengthResponse(status, contentType, body))

    private fun jsonResponse(status: NanoHTTPD.Response.Status, body: JSONObject): NanoHTTPD.Response =
        textResponse(status, body.toString(), "application/json; charset=utf-8")

    override fun stop() {
        sseHub.close()
        super.stop()
    }

    private fun decorate(response: NanoHTTPD.Response): NanoHTTPD.Response = response.apply {
        addHeader("Cache-Control", "no-store")
        addHeader("Access-Control-Allow-Origin", "*")
        addHeader("Access-Control-Allow-Methods", "GET,POST,OPTIONS")
        addHeader("Access-Control-Allow-Headers", "content-type")
    }

    private val instanceId: String = java.util.UUID.randomUUID().toString().replace("-", "")
}

private fun JSONObject.putAll(values: JSONObject): JSONObject {
    val keys = values.keys()
    while (keys.hasNext()) {
        val key = keys.next()
        put(key, values.opt(key))
    }
    return this
}
