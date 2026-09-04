package com.andrower.markerdeck

enum class DiscoveryScanTrigger {
    STARTUP,
    USER_REFRESH,
    NETWORK
}

internal const val DISCOVERY_MULTI_HOST_GRACE_MS = 240L

private fun DiscoveryScanTrigger.priority(): Int = when (this) {
    DiscoveryScanTrigger.STARTUP -> 3
    DiscoveryScanTrigger.USER_REFRESH -> 2
    DiscoveryScanTrigger.NETWORK -> 1
}

/** Keeps a startup or explicit refresh trigger from being replaced by a network callback. */
fun mergeDiscoveryScanTrigger(
    current: DiscoveryScanTrigger?,
    incoming: DiscoveryScanTrigger
): DiscoveryScanTrigger = when {
    current == null -> incoming
    incoming.priority() > current.priority() -> incoming
    else -> current
}

data class AutoDiscoveryPromptRequest(
    val hosts: List<DiscoveryHost>,
    val trigger: DiscoveryScanTrigger
)

/** Merges late network results without losing a prompt-worthy startup or refresh result. */
fun mergeAutoDiscoveryPrompt(
    current: AutoDiscoveryPromptRequest?,
    incomingHosts: List<DiscoveryHost>,
    incomingTrigger: DiscoveryScanTrigger
): AutoDiscoveryPromptRequest? {
    if (incomingHosts.isEmpty()) return current
    if (current == null && incomingTrigger == DiscoveryScanTrigger.NETWORK) return null
    val trigger = mergeDiscoveryScanTrigger(current?.trigger, incomingTrigger)
    val hosts = if (current == null) {
        mergeDiscoveredHosts(emptyList(), incomingHosts)
    } else {
        mergeDiscoveredHosts(current.hosts, incomingHosts)
    }
    return AutoDiscoveryPromptRequest(hosts = hosts, trigger = trigger)
}

fun shouldFinishDiscoveryScan(
    udpComplete: Boolean,
    mdnsComplete: Boolean,
    pendingVerifications: Int,
    firstVerifiedAtMs: Long?,
    nowMs: Long,
    graceMs: Long = DISCOVERY_MULTI_HOST_GRACE_MS
): Boolean {
    if (pendingVerifications < 0) return false
    val sourcesComplete = udpComplete && mdnsComplete
    if (firstVerifiedAtMs == null) return sourcesComplete && pendingVerifications == 0
    val graceElapsed = nowMs >= firstVerifiedAtMs + graceMs.coerceAtLeast(0L)
    return graceElapsed || (sourcesComplete && pendingVerifications == 0)
}

/** A completed scan is kept until settings becomes interactive, then consumed exactly once. */
fun shouldDeferAutoDiscoveryPrompt(
    pending: AutoDiscoveryPromptRequest?,
    uiAvailable: Boolean
): Boolean = pending != null && !uiAvailable

fun shouldSendDiscoveryUdpRetry(
    retrySent: Boolean,
    stopped: Boolean,
    nowMs: Long,
    retryAtMs: Long,
    deadlineMs: Long
): Boolean = !retrySent && !stopped && nowMs >= retryAtMs && nowMs < deadlineMs

enum class AutoDiscoveryPromptKind {
    NONE,
    SINGLE_HOST,
    MULTIPLE_HOSTS
}

data class AutoDiscoveryPromptDecision(
    val kind: AutoDiscoveryPromptKind,
    val hosts: List<DiscoveryHost>
)

/** Decides whether a completed scan is allowed to interrupt the settings page. */
fun decideAutoDiscoveryPrompt(
    hosts: List<DiscoveryHost>,
    trigger: DiscoveryScanTrigger,
    promptedIdentities: Set<String>
): AutoDiscoveryPromptDecision {
    val candidates = when (trigger) {
        DiscoveryScanTrigger.STARTUP -> hosts.filterNot { it.identity in promptedIdentities }
        DiscoveryScanTrigger.USER_REFRESH -> hosts
        DiscoveryScanTrigger.NETWORK -> emptyList()
    }
    if (candidates.isEmpty()) return AutoDiscoveryPromptDecision(AutoDiscoveryPromptKind.NONE, emptyList())
    return AutoDiscoveryPromptDecision(
        kind = if (candidates.size == 1) {
            AutoDiscoveryPromptKind.SINGLE_HOST
        } else {
            AutoDiscoveryPromptKind.MULTIPLE_HOSTS
        },
        hosts = candidates
    )
}

fun markAutoDiscoveryPrompted(
    promptedIdentities: Set<String>,
    hosts: List<DiscoveryHost>
): Set<String> = promptedIdentities + hosts.map(DiscoveryHost::identity)
