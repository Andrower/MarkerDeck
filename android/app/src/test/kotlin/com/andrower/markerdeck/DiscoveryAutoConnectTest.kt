package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryAutoConnectTest {
    @Test
    fun startupPromptsOncePerHostButRefreshCanChooseItAgain() {
        val host = host("instance-a", "192.168.1.10", "主控")
        val first = decideAutoDiscoveryPrompt(
            hosts = listOf(host),
            trigger = DiscoveryScanTrigger.STARTUP,
            promptedIdentities = emptySet()
        )
        assertEquals(AutoDiscoveryPromptKind.SINGLE_HOST, first.kind)
        val prompted = markAutoDiscoveryPrompted(emptySet(), first.hosts)

        assertEquals(
            AutoDiscoveryPromptKind.NONE,
            decideAutoDiscoveryPrompt(
                hosts = listOf(host),
                trigger = DiscoveryScanTrigger.STARTUP,
                promptedIdentities = prompted
            ).kind
        )
        assertEquals(
            AutoDiscoveryPromptKind.SINGLE_HOST,
            decideAutoDiscoveryPrompt(
                hosts = listOf(host),
                trigger = DiscoveryScanTrigger.USER_REFRESH,
                promptedIdentities = prompted
            ).kind
        )
    }

    @Test
    fun startupShowsAListForMultipleHostsAndNeverConnectsImplicitly() {
        val hosts = listOf(
            host("instance-a", "192.168.1.10", "主控一"),
            host("instance-b", "192.168.1.11", "主控二")
        )
        val decision = decideAutoDiscoveryPrompt(
            hosts = hosts,
            trigger = DiscoveryScanTrigger.STARTUP,
            promptedIdentities = emptySet()
        )

        assertEquals(AutoDiscoveryPromptKind.MULTIPLE_HOSTS, decision.kind)
        assertEquals(hosts, decision.hosts)
        assertTrue(markAutoDiscoveryPrompted(emptySet(), hosts).contains("instance:instance-a"))
    }

    @Test
    fun networkChangesDoNotInterruptTheSettingsPage() {
        val host = host("instance-a", "192.168.1.10", "主控")
        val decision = decideAutoDiscoveryPrompt(
            hosts = listOf(host),
            trigger = DiscoveryScanTrigger.NETWORK,
            promptedIdentities = emptySet()
        )
        assertEquals(AutoDiscoveryPromptKind.NONE, decision.kind)
    }

    private fun host(instanceId: String, address: String, name: String) = DiscoveryHost(
        instanceId = instanceId,
        name = name,
        serviceAddress = "http://$address:8765",
        port = 8765,
        advertisedHttpUrl = "http://$address:8765"
    )
}
