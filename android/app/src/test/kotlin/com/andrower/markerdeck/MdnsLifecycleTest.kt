package com.andrower.markerdeck

import org.junit.Assert.assertFalse
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
}
