package ru.rfidhunter.app.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TriggerAndInactivityTest {
    @Test
    fun triggerGestureUsesOneSecondBoundary() {
        val classifier = TriggerGestureClassifier(1_000)
        assertEquals(TriggerGesture.SHORT, classifier.classify(100, 1_099))
        assertEquals(TriggerGesture.LONG, classifier.classify(100, 1_100))
    }

    @Test
    fun inactivityPolicyExpiresOnlyAfterTimeout() {
        val policy = InactivityPolicy(3_600_000)
        assertFalse(policy.isExpired(10_000, 3_609_999))
        assertTrue(policy.isExpired(10_000, 3_610_000))
    }

    @Test
    fun volumeNavigationMovesPlusUpAndMinusDown() {
        assertEquals(2, moveCatalogIndex(3, 1, 8))
        assertEquals(4, moveCatalogIndex(3, -1, 8))
        assertEquals(0, moveCatalogIndex(0, 1, 8))
        assertEquals(7, moveCatalogIndex(7, -1, 8))
    }
}
