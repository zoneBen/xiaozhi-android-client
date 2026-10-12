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
 *
 * 「还没连上」那档是另一笔账，还要更长：真机实测里一条后台触发的任务在 46 秒里
 * 全撞在"还没连上"，被记成失败，而它半分钟后本来就会成功。
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
    fun `默认预算就是窄的那档`() {
        // 调用方不传 maxAttempts 时必须落到「设备正忙」那档。反过来（默认取宽的那档）
        // 会让任何一个忘了传参数的调用点白等四分钟——而四分钟后的插话基本等于骚扰。
        assertTrue(TaskRunPolicy.hasAttemptsLeft(TaskRunPolicy.MAX_ATTEMPTS - 1))
        assertFalse(TaskRunPolicy.hasAttemptsLeft(TaskRunPolicy.MAX_ATTEMPTS))

        // 显式传宽的那档，同样的次数就该还留着机会。
        assertTrue(
            TaskRunPolicy.hasAttemptsLeft(
                TaskRunPolicy.MAX_ATTEMPTS,
                TaskRunPolicy.CONNECT_WAIT_MAX_ATTEMPTS,
            ),
        )
    }

    @Test
    fun `还没连上那档总时长约 3 分 47 秒，够冷启动重连`() {
        // 这条对应真机上踩到的那次：闹钟把 App 从后台拽回来，进程起来 → 连服务端 →
        // 音频通路重建，实测要一两分钟。46 秒那档撑不过去，这档必须撑得过。
        val total = (1 until TaskRunPolicy.CONNECT_WAIT_MAX_ATTEMPTS)
            .sumOf { TaskRunPolicy.retryDelayMs(it) }

        assertEquals(226_500L, total)
        assertTrue("这档必须明显宽于「设备正忙」那档", total > 46_500L)
    }

    @Test
    fun `等重连的预算短于最小合理周期，免得自己和下一次到点追尾`() {
        // 退避到帽子之后靠次数往后铺，所以这档的边界不是"够久"，而是"别超过最短的
        // 任务周期"。INTERVAL 最小给到 1 分钟；一条补跑若等 4 分钟，就会和后面几次
        // 到点叠在一起排队，记录页上变成一串补跑，这恰恰是补跑判定要避免的。
        val total = (1 until TaskRunPolicy.CONNECT_WAIT_MAX_ATTEMPTS)
            .sumOf { TaskRunPolicy.retryDelayMs(it) }

        assertTrue("等重连不该超过 5 分钟", total < 5 * 60_000L)
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
