package com.andrower.markerdeck

import org.json.JSONObject
import java.util.LinkedHashMap
import java.util.UUID

interface HostStatePersistence {
    fun loadSettings(): HostSettings
    fun saveSettings(settings: HostSettings)
    fun loadPresets(): List<HostPreset>?
    fun savePresets(presets: List<HostPreset>)
}

object EmptyHostStatePersistence : HostStatePersistence {
    override fun loadSettings(): HostSettings = HostSettings()
    override fun saveSettings(settings: HostSettings) = Unit
    override fun loadPresets(): List<HostPreset>? = null
    override fun savePresets(presets: List<HostPreset>) = Unit
}

data class HostStateChange(
    val state: Map<String, String>,
    val sessionId: String,
    val targetSessionIds: List<String>,
    val broadcast: Boolean
)

data class HostDeviceMutation(
    val found: Boolean,
    val updated: Int,
    val value: String = ""
)

data class HostLockCommandStatus(
    val commandId: String,
    val enabled: Boolean,
    val targetCount: Int,
    val acknowledgedCount: Int,
    val confirmedCount: Int,
    val failedCount: Int,
    val pendingCount: Int,
    val complete: Boolean
) {
    fun toJson(): JSONObject = JSONObject()
        .put("commandId", commandId)
        .put("enabled", enabled)
        .put("targetCount", targetCount)
        .put("acknowledgedCount", acknowledgedCount)
        .put("confirmedCount", confirmedCount)
        .put("failedCount", failedCount)
        .put("pendingCount", pendingCount)
        .put("complete", complete)
}

data class HostLockDelivery(
    val commandId: String,
    val enabled: Boolean,
    val targetSessionIds: List<String>,
    val status: HostLockCommandStatus
)

class MarkerDeckHostStateStore(
    private val persistence: HostStatePersistence = EmptyHostStatePersistence,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val idFactory: () -> String = { UUID.randomUUID().toString().replace("-", "") }
) {
    private data class MutableDevice(
        val id: String,
        val deviceId: String,
        val sessionId: String,
        var pageInstanceId: String,
        var name: String,
        var group: String,
        var role: String,
        var width: Int,
        var height: Int,
        var dpr: Double,
        var userAgent: String,
        var lastSeen: Long,
        var order: Long,
        var state: Map<String, String>
    )

    private data class MutableLockCommand(
        val id: String,
        val enabled: Boolean,
        val targetIds: LinkedHashSet<String>,
        val acknowledgements: LinkedHashMap<String, LockAcknowledgement>,
        val createdAt: Long
    )

    private data class LockAcknowledgement(
        val ok: Boolean,
        val locked: Boolean,
        val error: String,
        val receivedAt: Long
    )

    private data class GlobalLock(val command: String = "none", val commandId: String = "0")

    private var settings = normalizeHostSettings(
        runCatching { persistence.loadSettings() }.getOrDefault(HostSettings())
    )
    private var globalState = normalizeHostState(null)
    private val devices = LinkedHashMap<String, MutableDevice>()
    private var nextDeviceOrder = 1L
    private var lockSequence = 0L
    private var globalLock = GlobalLock()
    private val lockCommands = LinkedHashMap<String, MutableLockCommand>()
    private var presets = loadInitialPresets()

    @Synchronized
    fun hostSettings(): HostSettings = settings

    @Synchronized
    fun setHostName(value: String): HostSettings {
        settings = normalizeHostSettings(settings.copy(hostName = value))
        persistence.saveSettings(settings)
        return settings
    }

    @Synchronized
    fun deviceSettings(): HostSettings = settings

    @Synchronized
    fun updateDeviceRetention(value: Long): List<String> {
        settings = normalizeHostSettings(settings.copy(deviceRetentionMs = value))
        persistence.saveSettings(settings)
        return removeExpiredLocked(clock())
    }

    @Synchronized
    fun getPresets(): List<HostPreset> = presets.toList()

    @Synchronized
    fun savePreset(name: String, state: Map<String, *>): HostPreset? {
        val candidate = cleanHostPreset(
            HostPreset(
                id = "preset-${clock().toString(36)}-${idFactory().take(8)}",
                name = name,
                state = normalizeHostState(state)
            ),
            "preset-${idFactory().take(8)}"
        ) ?: return null
        presets.add(candidate)
        persistence.savePresets(presets)
        return candidate
    }

    @Synchronized
    fun deletePreset(id: String): Boolean {
        val cleanId = cleanId(id)
        val next = presets.filterNot { it.id == cleanId }
        if (next.size == presets.size) return false
        presets = next.toMutableList()
        persistence.savePresets(presets)
        return true
    }

    @Synchronized
    fun register(
        request: HostRegistrationRequest,
        hasActiveDisplaySession: (sessionId: String, pageInstanceId: String) -> Boolean = { _, _ -> false }
    ): HostRegistrationResult {
        var sessionId = cleanId(request.sessionId.ifBlank { request.legacyId })
        if (sessionId.isEmpty()) {
            return HostRegistrationResult(ok = false, error = "Missing device id")
        }
        val pageInstanceId = cleanId(request.pageInstanceId)
        if (pageInstanceId.isNotEmpty() && hasActiveDisplaySession(sessionId, pageInstanceId)) {
            sessionId = "${sessionId.take(58)}-${idFactory()}".take(MARKERDECK_HOST_MAX_ID_LENGTH)
        }

        val deviceId = cleanId(request.deviceId.ifBlank { request.legacyId.ifBlank { sessionId } })
        val previous = devices[sessionId]
        val physicalPeer = devices.values.firstOrNull { it.deviceId == deviceId }
        val requestedName = safeHostText(request.name, MARKERDECK_HOST_MAX_NAME_LENGTH)
        val registeredName = when {
            request.updateName && requestedName.isNotEmpty() -> requestedName
            !previous?.name.isNullOrEmpty() -> previous?.name.orEmpty()
            !physicalPeer?.name.isNullOrEmpty() -> physicalPeer?.name.orEmpty()
            requestedName.isNotEmpty() -> requestedName
            else -> "设备 ${hostIdSuffix(deviceId)}"
        }
        if (request.updateName && requestedName.isNotEmpty()) {
            devices.values.filter { it.deviceId == deviceId }.forEach { it.name = requestedName }
        }

        val nextState = previous?.state
            ?: if (request.state.isEmpty()) globalState else normalizeHostState(request.state)
        val role = normalizedHostRole(request.role)
        val width = request.width.coerceAtLeast(0)
        val height = request.height.coerceAtLeast(0)
        val dpr = request.dpr.takeIf { it.isFinite() && it > 0 } ?: 1.0
        val userAgent = safeHostText(request.userAgent, 180)
        val now = clock()
        val changed = previous == null ||
            previous.name != registeredName ||
            previous.width != width ||
            previous.height != height ||
            previous.dpr != dpr ||
            previous.role != role
        devices[sessionId] = MutableDevice(
            id = sessionId,
            deviceId = deviceId,
            sessionId = sessionId,
            pageInstanceId = pageInstanceId,
            name = registeredName,
            group = previous?.group ?: physicalPeer?.group.orEmpty(),
            role = role,
            width = width,
            height = height,
            dpr = dpr,
            userAgent = userAgent,
            lastSeen = now,
            order = previous?.order ?: nextDeviceOrder++,
            state = nextState
        )
        return HostRegistrationResult(
            ok = true,
            sessionId = sessionId,
            name = registeredName,
            state = nextState,
            globalLockCommandId = globalLock.commandId,
            deviceListChanged = changed
        )
    }

    @Synchronized
    fun getDevices(): List<HostDeviceSnapshot> {
        val now = clock()
        removeExpiredLocked(now)
        return devices.values
            .map { snapshot(it, now) }
            .sortedWith(compareByDescending<HostDeviceSnapshot> { it.online }.thenBy { it.order })
    }

    @Synchronized
    fun pruneExpiredDevices(): List<String> = removeExpiredLocked(clock())

    @Synchronized
    fun deleteDevices(ids: Collection<String>, allOffline: Boolean): List<String> {
        val now = clock()
        val targetIds = if (allOffline) devices.keys.toList() else ids.map(::cleanId)
        val deleted = targetIds.distinct().filter { id ->
            val device = devices[id] ?: return@filter false
            if (now - device.lastSeen < MARKERDECK_HOST_OFFLINE_MS) return@filter false
            devices.remove(id)
            true
        }
        return deleted
    }

    @Synchronized
    fun renameDevice(id: String, name: String): HostDeviceMutation {
        val device = devices[cleanId(id)] ?: return HostDeviceMutation(found = false, updated = 0)
        val cleanName = safeHostText(name, MARKERDECK_HOST_MAX_NAME_LENGTH)
        if (cleanName.isEmpty()) return HostDeviceMutation(found = true, updated = 0)
        val updated = devices.values.count { candidate ->
            if (candidate.deviceId != device.deviceId) return@count false
            candidate.name = cleanName
            true
        }
        return HostDeviceMutation(found = true, updated = updated, value = cleanName)
    }

    @Synchronized
    fun assignGroup(ids: Collection<String>, group: String): HostDeviceMutation {
        val cleanGroup = safeHostText(group, MARKERDECK_HOST_MAX_NAME_LENGTH)
        val updated = ids.map(::cleanId).distinct().count { id ->
            val device = devices[id] ?: return@count false
            device.group = cleanGroup
            true
        }
        return HostDeviceMutation(found = updated > 0, updated = updated, value = cleanGroup)
    }

    @Synchronized
    fun getState(deviceId: String? = null): Map<String, String> {
        val device = deviceId?.let { devices[cleanId(it)] }
        return withGlobalLock(device?.state ?: globalState)
    }

    @Synchronized
    fun postState(deviceId: String?, next: Map<String, *>): HostStateChange {
        val normalized = normalizeHostState(next)
        val id = cleanId(deviceId.orEmpty())
        val device = devices[id]
        if (id.isNotEmpty() && device != null) {
            device.state = normalized
            device.lastSeen = clock()
            return HostStateChange(
                state = normalized,
                sessionId = id,
                targetSessionIds = listOf(id),
                broadcast = false
            )
        }
        globalState = normalized
        return HostStateChange(
            state = normalized,
            sessionId = "",
            targetSessionIds = emptyList(),
            broadcast = true
        )
    }

    @Synchronized
    fun createLockCommand(
        ids: Collection<String>,
        enabled: Boolean,
        persistToDevice: Boolean = true
    ): HostLockDelivery {
        pruneLockCommandsLocked(clock())
        val targetIds = LinkedHashSet(ids.map(::cleanId).filter { devices.containsKey(it) })
        val commandId = "${clock()}-${++lockSequence}"
        val command = MutableLockCommand(
            id = commandId,
            enabled = enabled,
            targetIds = targetIds,
            acknowledgements = LinkedHashMap(),
            createdAt = clock()
        )
        lockCommands[commandId] = command
        if (persistToDevice) {
            targetIds.forEach { id ->
                val device = devices[id] ?: return@forEach
                device.state = normalizeHostState(
                    device.state + mapOf(
                        "forceLock" to if (enabled) "1" else "0",
                        "lockCommand" to if (enabled) "lock" else "unlock",
                        "lockCommandId" to commandId
                    )
                )
            }
        }
        return HostLockDelivery(
            commandId = commandId,
            enabled = enabled,
            targetSessionIds = targetIds.toList(),
            status = statusLocked(command)
        )
    }

    @Synchronized
    fun broadcastLock(enabled: Boolean): HostLockDelivery {
        val targetIds = devices.values
            .filter { it.role == "display" && clock() - it.lastSeen < MARKERDECK_HOST_OFFLINE_MS }
            .map { it.id }
        val delivery = createLockCommand(targetIds, enabled, persistToDevice = false)
        globalLock = GlobalLock(
            command = if (enabled) "lock" else "unlock",
            commandId = delivery.commandId
        )
        return delivery
    }

    @Synchronized
    fun acknowledgeLock(
        commandId: String,
        sessionId: String,
        ok: Boolean,
        locked: Boolean,
        error: String
    ): HostLockCommandStatus? {
        val command = lockCommands[cleanId(commandId)] ?: return null
        val id = cleanId(sessionId)
        if (!command.targetIds.contains(id)) return null
        command.acknowledgements[id] = LockAcknowledgement(
            ok = ok,
            locked = locked,
            error = safeHostText(error, 200),
            receivedAt = clock()
        )
        devices[id]?.let { device ->
            device.lastSeen = clock()
            device.state = normalizeHostState(
                device.state + mapOf(
                    "forceLock" to if (command.enabled) "1" else "0",
                    "displayLocked" to if (locked) "1" else "0",
                    "lockCommand" to "none",
                    "lockCommandId" to command.id
                )
            )
        }
        return statusLocked(command)
    }

    @Synchronized
    fun lockStatus(commandId: String): HostLockCommandStatus? =
        lockCommands[cleanId(commandId)]?.let(::statusLocked)

    @Synchronized
    fun currentGlobalLockCommandId(): String = globalLock.commandId

    private fun loadInitialPresets(): MutableList<HostPreset> {
        val persisted = runCatching { persistence.loadPresets() }.getOrNull()
        if (persisted == null) return defaultHostPresets().toMutableList()
        return persisted.mapIndexedNotNull { index, preset ->
            cleanHostPreset(preset, "saved-${index + 1}")
        }.toMutableList()
    }

    private fun snapshot(device: MutableDevice, now: Long): HostDeviceSnapshot = HostDeviceSnapshot(
        id = device.id,
        deviceId = device.deviceId,
        sessionId = device.sessionId,
        pageInstanceId = device.pageInstanceId,
        name = device.name,
        group = device.group,
        role = device.role,
        width = device.width,
        height = device.height,
        dpr = device.dpr,
        userAgent = device.userAgent,
        lastSeen = device.lastSeen,
        order = device.order,
        online = now - device.lastSeen < MARKERDECK_HOST_OFFLINE_MS,
        state = device.state
    )

    private fun removeExpiredLocked(now: Long): List<String> {
        if (settings.deviceRetentionMs == 0L) return emptyList()
        val expired = devices.values
            .filter { now - it.lastSeen >= settings.deviceRetentionMs }
            .map { it.id }
        expired.forEach(devices::remove)
        return expired
    }

    private fun withGlobalLock(state: Map<String, String>): Map<String, String> =
        LinkedHashMap(state).apply {
            put("globalLockCommandId", globalLock.commandId)
            put("globalLockCommand", globalLock.command)
        }

    private fun statusLocked(command: MutableLockCommand): HostLockCommandStatus {
        val acknowledgements = command.acknowledgements.values
        val confirmed = acknowledgements.count { it.ok && it.locked == command.enabled }
        return HostLockCommandStatus(
            commandId = command.id,
            enabled = command.enabled,
            targetCount = command.targetIds.size,
            acknowledgedCount = acknowledgements.size,
            confirmedCount = confirmed,
            failedCount = acknowledgements.size - confirmed,
            pendingCount = (command.targetIds.size - acknowledgements.size).coerceAtLeast(0),
            complete = acknowledgements.size >= command.targetIds.size
        )
    }

    private fun pruneLockCommandsLocked(now: Long) {
        val expired = lockCommands.values
            .filter { now - it.createdAt >= 10 * 60 * 1000L }
            .map { it.id }
        expired.forEach(lockCommands::remove)
        while (lockCommands.size > 128) lockCommands.remove(lockCommands.keys.first())
    }

    private fun cleanId(value: String): String = safeHostText(value, MARKERDECK_HOST_MAX_ID_LENGTH)
}
