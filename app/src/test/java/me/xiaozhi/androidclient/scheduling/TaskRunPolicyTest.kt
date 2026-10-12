package me.xiaozhi.androidclient.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重试的账。
 *
 * 这一组里最值得钉的不是某一个数字，而是**总时长**——「设备正忙」到底能让用户觉得
 * 等多久。改任何一个常数都会挪动这个总数，而它才是用户真正感受到的东西
 * （46 秒：能撑过一句寒暄，撑不过一场对话；再长就该认输了）。
 */
class TaskRunPolicyTest {

    @Test
    fun `退避从 15 秒开始，每次翻倍`() {
        assertEquals(1_500L, TaskRunPolicy.retryDelayMs(1))
        assertEquals(3_000L, TaskRunPolicy.retryDelayMs(2))
        assertEquals(6_000L, TaskRunPolicy.retryDelayMs(3))
        assertEquals(12_000L, TaskRunPolicy.retryDelayMs(4))
    }

    @Test
    fun `单次间隔封顶 30 秒`() {
        // 第 5 次是最后一次真的翻倍（24000），第 6 次按翻倍会到 48000，被帽子压回 30 秒。
        assertEquals(24_000L, TaskRunPolicy.retryDelayMs(5))
        assertEquals(30_000L, TaskRunPolicy.retryDelayMs(6))
        assertEquals(30_000L, TaskRunPolicy.retryDelayMs(7))
        // 移位也不能溢出：真把次数调大，这里不许出现负数或零。
        assertTrue(TaskRunPolicy.retryDelayMs(40) > 0L)
    }

    @Test
    fun `次数算错时不变成立刻重试`() {
        // 最坏的一种错：调用方传了 0 或负数，若照直算就是 0 延迟 —— 循环里等于放弃退避、
        // 每秒打一次设备。按 1 处理，至少还退避一次。
        assertEquals(TaskRunPolicy.retryDelayMs(1), TaskRunPolicy.retryDelayMs(0))
        assertEquals(TaskRunPolicy.retryDelayMs(1), TaskRunPolicy.retryDelayMs(-3))
    }

    @Test
    fun `总等待时长约 46 秒`() {
        // 这条是"为什么是 6 次"的全部理由，改常数时它会先红。
        val total = (1 until TaskRunPolicy.MAX_ATTEMPTS).sumOf { TaskRunPolicy.retryDelayMs(it) }

        assertEquals(46_500L, total)
    }

    @Test
    fun `试满上限就不再试`() {
        assertTrue(TaskRunPolicy.hasAttemptsLeft(0))
        assertTrue(TaskRunPolicy.hasAttemptsLeft(TaskRunPolicy.MAX_ATTEMPTS - 1))
        assertFalse(TaskRunPolicy.hasAttemptsLeft(TaskRunPolicy.MAX_ATTEMPTS))
        assertFalse(TaskRunPolicy.hasAttemptsLeft(TaskRunPolicy.MAX_ATTEMPTS + 1))
    }

    @Test
    fun `投递结果的约定与 VM 一致`() {
        // 这三个串是和 MainViewModel.submitExternalMessage 共用的，
        // 值本身不重要，重要的是**改的时候会被这里绊一下**。
        assertEquals("已发送", ExternalSend.SENT)
        assertEquals("设备正忙，未发送", ExternalSend.BUSY)
        assertEquals("文本为空，未发送", ExternalSend.EMPTY)
    }
}
