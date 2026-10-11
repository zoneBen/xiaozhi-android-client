package me.xiaozhi.androidclient.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AutoExposureTest {

    // ---- lowLightFpsRange ----

    @Test
    fun `picks the range whose lower bound allows the longest exposure`() {
        // TV5701 真机上报的就是这一组，只有 [5,30] 允许 1/5 秒曝光。
        val ranges = listOf(
            intArrayOf(15, 15),
            intArrayOf(15, 20),
            intArrayOf(20, 20),
            intArrayOf(5, 30),
            intArrayOf(30, 30),
        ).map { it[0]..it[1] }
        assertEquals(5..30, AutoExposure.lowLightFpsRange(ranges))
    }

    @Test
    fun `breaks a tie on the lower bound by the smaller upper bound`() {
        // [5,30] 和 [5,60] 下限一样，选上限小的那个——上限才是曝光时间的封顶。
        assertEquals(5..30, AutoExposure.lowLightFpsRange(listOf(5..60, 5..30)))
    }

    @Test
    fun `falls back to a single available range`() {
        assertEquals(30..30, AutoExposure.lowLightFpsRange(listOf(30..30)))
    }

    @Test
    fun `returns null when the device reports no ranges`() {
        assertNull(AutoExposure.lowLightFpsRange(emptyList()))
    }

    // ---- shouldCapture ----

    @Test
    fun `captures as soon as AE has converged`() {
        assertTrue(AutoExposure.shouldCapture(AeReadiness.CONVERGED, elapsedMs = 0))
    }

    @Test
    fun `keeps waiting while AE is still searching`() {
        assertFalse(AutoExposure.shouldCapture(AeReadiness.NOT_CONVERGED, elapsedMs = 500))
    }

    @Test
    fun `gives up waiting at the deadline so the shot never hangs`() {
        assertTrue(AutoExposure.shouldCapture(AeReadiness.NOT_CONVERGED, AutoExposure.MAX_WAIT_MS))
        assertTrue(AutoExposure.shouldCapture(AeReadiness.NOT_CONVERGED, AutoExposure.MAX_WAIT_MS + 1))
    }

    @Test
    fun `captures at the deadline even when the state is unreadable`() {
        assertTrue(AutoExposure.shouldCapture(AeReadiness.UNKNOWN, AutoExposure.MAX_WAIT_MS))
    }

    @Test
    fun `waits rather than shooting blind before the deadline`() {
        assertFalse(AutoExposure.shouldCapture(AeReadiness.UNKNOWN, elapsedMs = 0))
        assertFalse(AutoExposure.shouldCapture(AeReadiness.UNKNOWN, AutoExposure.MAX_WAIT_MS - 1))
    }

    @Test
    fun `the deadline leaves room inside the overall capture timeout`() {
        // 等 AE 的时间必须明显短于一次拍摄的总超时，否则"等"会把整个操作拖成超时。
        assertTrue(AutoExposure.MAX_WAIT_MS < 12_000L)
    }

    // ---- readinessOfAeState ----

    @Test
    fun `maps the converged and locked states to ready`() {
        assertEquals(AeReadiness.CONVERGED, AutoExposure.readinessOfAeState(2))
        assertEquals(AeReadiness.CONVERGED, AutoExposure.readinessOfAeState(3))
    }

    @Test
    fun `maps searching and precapture to not ready`() {
        assertEquals(AeReadiness.NOT_CONVERGED, AutoExposure.readinessOfAeState(0))
        assertEquals(AeReadiness.NOT_CONVERGED, AutoExposure.readinessOfAeState(1))
        assertEquals(AeReadiness.NOT_CONVERGED, AutoExposure.readinessOfAeState(5))
    }

    @Test
    fun `maps a missing state to unknown`() {
        assertEquals(AeReadiness.UNKNOWN, AutoExposure.readinessOfAeState(null))
    }
}
