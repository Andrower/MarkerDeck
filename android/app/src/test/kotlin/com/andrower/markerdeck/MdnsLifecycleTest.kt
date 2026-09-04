package com.andrower.markerdeck

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsLifecycleTest {
    @Test
    fun acceptsOnlyTheActiveRegistrationCallback() {
        assertTrue(isCurrentMdnsRegistration(7L, 7L, listenerRegistered = true))
        assertFalse(isCurrentMdnsRegistration(8L, 7L, listenerRegistered = true))
        assertFalse(isCurrentMdnsRegistration(7L, 7L, listenerRegistered = false))
    }

    @Test
    fun incrementingGenerationAfterStopInvalidatesTheOldCallback() {
        val generationBeforeStop = 3L
        val generationAfterStop = generationBeforeStop + 1

        assertFalse(
            isCurrentMdnsRegistration(
                activeGeneration = generationAfterStop,
                callbackGeneration = generationBeforeStop,
                listenerRegistered = true
            )
        )
    }

    @Test
    fun resolveQueueUsesOneChannelAndDrainsAfterFailure() {
        val queue = MdnsResolveQueue<Int>()
        queue.enqueue(1)
        queue.enqueue(2)

        assertEquals(1, MARKERDECK_MDNS_MAX_CONCURRENT_RESOLVES)
        assertEquals(1, queue.takeNext())
        assertNull(queue.takeNext())
        assertEquals(1, queue.activeCount())

        // A failed resolve releases the only slot so the next service can proceed.
        queue.complete()
        assertEquals(2, queue.takeNext())
        assertEquals(1, queue.activeCount())
    }

    @Test
    fun multicastLockStaysWhileEitherDiscoveryPathIsUsableOrPending() {
        assertFalse(shouldReleaseMulticastLock(false, false, true))
        assertFalse(shouldReleaseMulticastLock(true, false, false))
        assertFalse(shouldReleaseMulticastLock(false, true, false))
        assertTrue(shouldReleaseMulticastLock(false, false, false))
    }
}
