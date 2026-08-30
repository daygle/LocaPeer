package com.locapeer.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.Mockito.mock

/**
 * Tiny coverage of [AppLockManager]'s observable behaviour. Goals:
 *
 *  1. Confirm the unlocked StateFlow defaults to null on construction (lock state
 *     not yet read from disk — the UI shows a loading placeholder until
 *     [AppLockManager.onAppStart] resolves the preference asynchronously).
 *  2. Confirm [AppLockManager.setUnlocked] flips the StateFlow immediately and is
 *     reversible. The bulk of the manager's logic (ProcessLifecycleOwner observer,
 *     DataStore-backed preference read, backgroundedAtMs arithmetic) is exercised
 *     by inspection rather than these tests because simulating Android Context and
 *     Preferences DataStore requires Robolectric, which isn't a dependency yet.
 */
class AppLockManagerTest {
    @Test
    fun `initial state is null while preference loads`() {
        // Mockito mock satisfies the @Inject constructor without touching DataStore.
        val manager = AppLockManager(mock(AppPreferences::class.java))
        assertNull(manager.unlocked.value)
    }

    @Test
    fun `setUnlocked false flips state to locked`() {
        val manager = AppLockManager(mock(AppPreferences::class.java))
        manager.setUnlocked(false)
        assertEquals(false, manager.unlocked.value)
    }

    @Test
    fun `setUnlocked true flips state back to unlocked`() {
        val manager = AppLockManager(mock(AppPreferences::class.java))
        manager.setUnlocked(false)
        manager.setUnlocked(true)
        assertEquals(true, manager.unlocked.value)
    }

    @Test
    fun `multiple subscribers see the same StateFlow value`() {
        val manager = AppLockManager(mock(AppPreferences::class.java))
        val a = manager.unlocked
        val b = manager.unlocked
        manager.setUnlocked(false)
        assertEquals(false, a.value)
        assertEquals(false, b.value)
        manager.setUnlocked(true)
        assertEquals(true, a.value)
        assertEquals(true, b.value)
    }
}
