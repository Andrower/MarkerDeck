package com.andrower.markerdeck

enum class DiscoveryScanTrigger {
    STARTUP,
    USER_REFRESH,
    NETWORK
}

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
