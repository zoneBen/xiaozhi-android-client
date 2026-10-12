package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 补跑判定。
 *
 * 这一组值得写，是因为补跑**只在开机和冷启动这两条路上发生**——真机验证要关机、
 * 要等，而漏判的后果又特别显眼：要么该跑的一次没跑，要么同一封邮件发两遍。
 * 把四种情形摊在纯函数里钉住，比每次改完都去拔电源划算得多。
 */
class TaskCatchUpPolicyTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `还没到点就什么都不做`() {
        val task = task(nextFireAt = now + MINUTE, allowCatchUp = true)

        assertEquals(
            CatchUpDecision.Nothing,
            catchUpDecisionFor(task, now, alreadyRecorded = false),
        )
    }

    @Test
    fun `恰好到点也算该跑了`() {
        // 边界用 <=：闹钟到点那一刻被系统延后几毫秒是常态，
        // 用 < 会让「准点响的」和「漏了的」在边界上分不清。
        val task = task(nextFireAt = now, allowCatchUp = true)

        assertEquals(
            CatchUpDecision.CatchUp(now),
            catchUpDecisionFor(task, now, alreadyRecorded = false),
        )
    }

    @Test
    fun `漏了且勾了补跑就补一次`() {
        val missedAt = now - 30 * MINUTE
        val task = task(nextFireAt = missedAt, allowCatchUp = true)

        assertEquals(
            CatchUpDecision.CatchUp(missedAt),
            catchUpDecisionFor(task, now, alreadyRecorded = false),
        )
    }

    @Test
    fun `漏了但没勾补跑，也记一条让用户看得见`() {
        // 这一条最容易被当成「什么都不用做」。不记的话，用户在记录里什么都看不到，
        // 只能得出「这破玩意儿没响」——而真相是他自己没勾补跑。区别很重要。
        val missedAt = now - 3 * HOUR
        val task = task(nextFireAt = missedAt, allowCatchUp = false)

        assertEquals(
            CatchUpDecision.SkipMissed(missedAt),
            catchUpDecisionFor(task, now, alreadyRecorded = false),
        )
    }

    @Test
    fun `这个发生点已经有记录就不再补`() {
        // 防重复的核心：闹钟 receiver 落完 PENDING 行、还没推进 nextFireAt 就被杀进程，
        // 此时库里 nextFireAt 仍在过去。没有再查一次，下次开机会再补一遍。
        val missedAt = now - 30 * MINUTE
        val task = task(nextFireAt = missedAt, allowCatchUp = true)

        assertEquals(
            CatchUpDecision.Nothing,
            catchUpDecisionFor(task, now, alreadyRecorded = true),
        )
    }

    @Test
    fun `已经有记录时，没勾补跑的也不再记一条`() {
        val task = task(nextFireAt = now - MINUTE, allowCatchUp = false)

        assertEquals(
            CatchUpDecision.Nothing,
            catchUpDecisionFor(task, now, alreadyRecorded = true),
        )
    }

    @Test
    fun `停用的任务不补跑也不记账`() {
        // 用户主动关的，不是「漏了」。给它记一条 SKIPPED_MISSED 反而会让人以为
        // 系统出了错，去翻自己明明已经关掉的任务。
        val task = task(nextFireAt = now - 5 * HOUR, allowCatchUp = true, enabled = false)

        assertEquals(
            CatchUpDecision.Nothing,
            catchUpDecisionFor(task, now, alreadyRecorded = false),
        )
    }

    @Test
    fun `没有下一次发生点就无从谈漏`() {
        val task = task(nextFireAt = null, allowCatchUp = true)

        assertEquals(
            CatchUpDecision.Nothing,
            catchUpDecisionFor(task, now, alreadyRecorded = false),
        )
    }

    @Test
    fun `只补最近的一次，不逐次回放`() {
        // 关机一整夜、每小时一次的任务漏了 8 次。判定只看 nextFireAt 这一个时刻，
        // 所以结构上就只会补 1 次——这一条钉的正是「不会刷屏」这个性质。
        val missedAt = now - 8 * HOUR
        val task = task(nextFireAt = missedAt, allowCatchUp = true)

        val decision = catchUpDecisionFor(task, now, alreadyRecorded = false)

        assertTrue(decision is CatchUpDecision.CatchUp)
        assertEquals(missedAt, (decision as CatchUpDecision.CatchUp).scheduledAtEpochMs)
    }

    private fun task(
        nextFireAt: Long?,
        allowCatchUp: Boolean,
        enabled: Boolean = true,
    ): ScheduledTaskEntity = ScheduledTaskEntity(
        id = 7L,
        title = "每小时看一眼",
        kind = TaskKind.INTERVAL.name,
        intervalMinutes = 60,
        hourOfDay = null,
        minuteOfHour = null,
        daysOfWeekMask = null,
        daysOfMonthCsv = null,
        fireAtEpochMs = null,
        anchorEpochMs = 0L,
        action = TaskAction.INJECT_TEXT.name,
        text = "报一下时间",
        emailTo = null,
        emailSubject = null,
        allowCatchUp = allowCatchUp,
        enabled = enabled,
        lastFireAtEpochMs = null,
        nextFireAtEpochMs = nextFireAt,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private companion object {
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
