package me.xiaozhi.androidclient.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 百分比 ↔ Android 音量索引的换算。
 *
 * 这里的数不是随手写的：`STREAM_MUSIC` 的档数在真机上通常是 15，
 * 所以拿 15 当主场景。算错不会崩，只会"说调到 50% 实际是 53%"，
 * 属于不测就发现不了的那类问题。
 */
class VolumeScaleTest {

    private val steps = 15

    @Test
    fun `percent maps onto the device's actual steps`() {
        assertEquals(0, VolumeScale.toIndex(0, steps))
        assertEquals(2, VolumeScale.toIndex(10, steps))
        assertEquals(5, VolumeScale.toIndex(30, steps))
        // 50% 落在 7.5 档上，四舍五入到 8——这就是"说 50 实际 53"的来源。
        assertEquals(8, VolumeScale.toIndex(50, steps))
        assertEquals(steps, VolumeScale.toIndex(100, steps))
    }

    @Test
    fun `index maps back to a percent`() {
        assertEquals(0, VolumeScale.toPercent(0, steps))
        assertEquals(100, VolumeScale.toPercent(steps, steps))
        assertEquals(53, VolumeScale.toPercent(8, steps))
        assertEquals(7, VolumeScale.toPercent(1, steps))
    }

    @Test
    fun `out of range input is clamped instead of producing a bad index`() {
        // 越界索引直接交给 setStreamVolume 会抛 SecurityException，所以这里必须挡住。
        assertEquals(steps, VolumeScale.toIndex(150, steps))
        assertEquals(0, VolumeScale.toIndex(-10, steps))
        assertEquals(100, VolumeScale.toPercent(20, steps))
        assertEquals(0, VolumeScale.toPercent(-5, steps))
    }

    @Test
    fun `a device with no adjustable steps does not divide by zero`() {
        // 固定音量策略的设备上 getStreamMaxVolume 见过返回 0。
        assertEquals(0, VolumeScale.toIndex(50, 0))
        assertEquals(0, VolumeScale.toPercent(3, 0))
        assertEquals(0, VolumeScale.toIndex(50, -1))
    }

    @Test
    fun `a single step device still honours the ends`() {
        assertEquals(0, VolumeScale.toIndex(49, 1))
        assertEquals(1, VolumeScale.toIndex(50, 1))
        assertEquals(1, VolumeScale.toIndex(100, 1))
    }

    @Test
    fun `turning the volume up never lowers the index`() {
        // 换算得单调，否则"调大一点"有可能反而变小。
        for (maxIndex in listOf(1, 7, 15, 30)) {
            var previous = -1
            for (percent in VolumeScale.MIN..VolumeScale.MAX) {
                val index = VolumeScale.toIndex(percent, maxIndex)
                assertTrue(
                    "maxIndex=$maxIndex percent=$percent 时 $index < $previous",
                    index >= previous,
                )
                previous = index
            }
        }
    }

    @Test
    fun `range check rejects what the schema says is invalid`() {
        assertTrue(VolumeScale.isWithinRange(0))
        assertTrue(VolumeScale.isWithinRange(100))
        assertFalse(VolumeScale.isWithinRange(-1))
        assertFalse(VolumeScale.isWithinRange(101))
    }

    @Test
    fun `clamp keeps the ends exactly where they are`() {
        assertEquals(VolumeScale.MIN, VolumeScale.clamp(-999))
        assertEquals(VolumeScale.MAX, VolumeScale.clamp(999))
        assertEquals(42, VolumeScale.clamp(42))
    }
}
