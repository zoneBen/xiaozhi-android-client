package me.xiaozhi.androidclient.data

import me.xiaozhi.androidclient.data.db.HardwareProbeEntity
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 硬件自检缓存的有效期判定。
 *
 * 判据本身只有一行，但两个边界都不显然，而且错了很难发现：
 * 缓存永不失效 → 换了硬件也一直用旧结论；永不命中 → 缓存等于没写。
 */
class HardwareProbeFreshnessTest {

    private val now = 1_700_000_000_000L

    private fun probedAt(epochMs: Long) = HardwareProbeEntity(
        microphoneReady = true,
        microphoneMessage = "",
        hardwareControlAvailable = true,
        deviceControlStatus = "云台和补光灯都能控制，可以调节媒体音量",
        cameraFacingChoicesCsv = "BACK,FRONT",
        volumeAvailable = true,
        probedAtEpochMs = epochMs,
    )

    @Test
    fun `刚刚探测的结果算新鲜`() {
        assertTrue(HardwareProbeRepository.isFresh(probedAt(now), now))
    }

    @Test
    fun `差一点点到期的仍算新鲜`() {
        assertTrue(HardwareProbeRepository.isFresh(probedAt(now - HardwareProbeRepository.PROBE_TTL_MS + 1), now))
    }

    /** 边界取「开区间」：正好 24 小时就重探，不做「等于也算新鲜」的特例。 */
    @Test
    fun `正好到期就不新鲜了`() {
        assertFalse(HardwareProbeRepository.isFresh(probedAt(now - HardwareProbeRepository.PROBE_TTL_MS), now))
    }

    @Test
    fun `过期很久的不新鲜`() {
        assertFalse(HardwareProbeRepository.isFresh(probedAt(now - 10 * HardwareProbeRepository.PROBE_TTL_MS), now))
    }

    /**
     * 系统时间被往回调（NTP 校时、用户手动改）时 `now - probedAt` 是负数。
     * 如果只判 `age < TTL`，这一行会让缓存看起来永远新鲜、再也刷不掉。
     */
    @Test
    fun `探测时刻在未来时按过期处理`() {
        assertFalse(HardwareProbeRepository.isFresh(probedAt(now + 60_000L), now))
    }
}
