package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryAutoConnectTest {
    @Test
    fun startupTriggerCannotBeReplacedByNetworkCallbacks() {
        assertEquals(
            DiscoveryScanTrigger.STARTUP,
            mergeDiscoveryScanTrigger(
                current = DiscoveryScanTrigger.STARTUP,
                incoming = DiscoveryScanTrigger.NETWORK
            )
        )
        assertEquals(
            DiscoveryScanTrigger.USER_REFRESH,
            mergeDiscoveryScanTrigger(
                current = DiscoveryScanTrigger.USER_REFRESH,
                incoming = DiscoveryScanTrigger.NETWORK
            )
        )
        assertEquals(
            DiscoveryScanTrigger.USER_REFRESH,
            mergeDiscoveryScanTrigger(
                current = DiscoveryScanTrigger.NETWORK,
                incoming = DiscoveryScanTrigger.USER_REFRESH
            )
        )
    }

    @Test
    fun firstVerifiedHostFinishesAfterGraceEvenWithPendingVerification() {
        assertFalse(
            shouldFinishDiscoveryScan(
                udpComplete = false,
                mdnsComplete = false,
                pendingVerifications = 0,
                firstVerifiedAtMs = 100L,
                nowMs = 339L,
                graceMs = 240L
            )
        )
        assertTrue(
            shouldFinishDiscoveryScan(
                udpComplete = false,
                mdnsComplete = false,
                pendingVerifications = 1,
                firstVerifiedAtMs = 100L,
                nowMs = 340L,
                graceMs = 240L
            )
        )
        assertTrue(
            shouldFinishDiscoveryScan(
                udpComplete = true,
                mdnsComplete = true,
                pendingVerifications = 0,
                firstVerifiedAtMs = null,
                nowMs = 0L
            )
        )
    }

    @Test
    fun udpRetryIsLimitedToOneAttemptAndNeverRunsAfterAbortOrDeadline() {
        assertFalse(
            shouldSendDiscoveryUdpRetry(
                retrySent = false,
                stopped = false,
                nowMs = 299L,
                retryAtMs = 300L,
                deadlineMs = 1_800L
            )
        )
        assertTrue(
            shouldSendDiscoveryUdpRetry(
                retrySent = false,
                stopped = false,
                nowMs = 300L,
                retryAtMs = 300L,
                deadlineMs = 1_800L
            )
        )
        assertFalse(
            shouldSendDiscoveryUdpRetry(
                retrySent = true,
                stopped = false,
                nowMs = 301L,
                retryAtMs = 300L,
                deadlineMs = 1_800L
            )
        )
        assertFalse(
            shouldSendDiscoveryUdpRetry(
                retrySent = false,
                stopped = true,
                nowMs = 301L,
                retryAtMs = 300L,
                deadlineMs = 1_800L
            )
        )
        assertFalse(
            shouldSendDiscoveryUdpRetry(
                retrySent = false,
                stopped = false,
                nowMs = 1_800L,
                retryAtMs = 300L,
                deadlineMs = 1_800L
            )
        )
    }

    @Test
    fun startupPromptSurvivesNetworkResultsAndUiDeferral() {
        val firstHost = host("instance-a", "192.168.1.10", "主控一")
        val secondHost = host("instance-b", "192.168.1.11", "主控二")
        val startup = mergeAutoDiscoveryPrompt(
            current = null,
            incomingHosts = listOf(firstHost),
            incomingTrigger = DiscoveryScanTrigger.STARTUP
        )
        val merged = mergeAutoDiscoveryPrompt(
            current = startup,
            incomingHosts = listOf(secondHost),
            incomingTrigger = DiscoveryScanTrigger.NETWORK
        )

        assertEquals(DiscoveryScanTrigger.STARTUP, merged?.trigger)
        assertEquals(listOf(firstHost, secondHost), merged?.hosts)
        assertTrue(shouldDeferAutoDiscoveryPrompt(merged, uiAvailable = false))
        assertFalse(shouldDeferAutoDiscoveryPrompt(merged, uiAvailable = true))
        assertEquals(
            AutoDiscoveryPromptKind.MULTIPLE_HOSTS,
            decideAutoDiscoveryPrompt(
                hosts = merged!!.hosts,
                trigger = merged.trigger,
                promptedIdentities = emptySet()
            ).kind
        )
    }

    @Test
    fun networkOnlyResultsNeverCreateAnAutomaticPrompt() {
        assertEquals(
            null,
            mergeAutoDiscoveryPrompt(
                current = null,
                incomingHosts = listOf(host("instance-a", "192.168.1.10", "主控")),
                incomingTrigger = DiscoveryScanTrigger.NETWORK
            )
        )
    }

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
