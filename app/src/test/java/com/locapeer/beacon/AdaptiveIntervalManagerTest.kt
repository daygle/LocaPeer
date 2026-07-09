package com.locapeer.beacon

import com.locapeer.settings.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AdaptiveIntervalManagerTest {

    private lateinit var manager: AdaptiveIntervalManager
    private val settings = AppSettings(
        stationaryIntervalMinutes = 15,
        walkingIntervalMinutes = 5,
        runningIntervalMinutes = 2,
        cyclingIntervalMinutes = 3,
        drivingIntervalMinutes = 2,
        lowBatteryIntervalMinutes = 30
    )

    @Before
    fun setUp() {
        manager = AdaptiveIntervalManager()
        manager.updateBattery(100)
    }

    @Test
    fun `each motion state maps to its configured interval`() {
        manager.updateMotionState(MotionState.STATIONARY)
        assertEquals(15 * 60_000L, manager.getIntervalMillis(settings))
        manager.updateMotionState(MotionState.WALKING)
        assertEquals(5 * 60_000L, manager.getIntervalMillis(settings))
        manager.updateMotionState(MotionState.RUNNING)
        assertEquals(2 * 60_000L, manager.getIntervalMillis(settings))
        manager.updateMotionState(MotionState.CYCLING)
        assertEquals(3 * 60_000L, manager.getIntervalMillis(settings))
        manager.updateMotionState(MotionState.DRIVING)
        assertEquals(2 * 60_000L, manager.getIntervalMillis(settings))
    }

    @Test
    fun `unknown motion uses the walking interval`() {
        manager.updateMotionState(MotionState.UNKNOWN)
        assertEquals(5 * 60_000L, manager.getIntervalMillis(settings))
    }

    @Test
    fun `sos overrides everything at 15 seconds`() {
        manager.setSosMode(enabled = true)
        manager.updateMotionState(MotionState.STATIONARY)
        manager.updateBattery(5)
        assertEquals(15_000L, manager.getIntervalMillis(settings))
    }

    @Test
    fun `low battery stretches to the low-battery interval`() {
        manager.updateMotionState(MotionState.DRIVING)
        manager.updateBattery(19)
        assertEquals(30 * 60_000L, manager.getIntervalMillis(settings))
        manager.updateBattery(20)
        assertEquals(2 * 60_000L, manager.getIntervalMillis(settings))
    }

    @Test
    fun `low battery interval is floored at one minute`() {
        manager.updateBattery(10)
        assertEquals(60_000L, manager.getIntervalMillis(settings.copy(lowBatteryIntervalMinutes = 0)))
    }

    @Test
    fun `motion interval is floored at 15 seconds`() {
        manager.updateMotionState(MotionState.DRIVING)
        assertEquals(15_000L, manager.getIntervalMillis(settings.copy(drivingIntervalMinutes = 0)))
    }

    @Test
    fun `low battery flag follows the 20 percent threshold`() {
        manager.updateBattery(19)
        assertTrue(manager.isLowBattery())
        manager.updateBattery(20)
        assertFalse(manager.isLowBattery())
    }
}
