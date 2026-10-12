package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.data.db.TaskExecutionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 「这条排队的执行记录还该不该做」。
 *
 * 这一组钉的是一个真机踩出来的失效：**一次性任务永远不执行**。闹钟响了、`PENDING` 行落了，
 * 但 receiver 紧接着把任务 `retire` 掉（`enabled = 0`），而消费者每秒才轮询一次——
 * 它看到的永远是已经停用的那一行，于是每次都记成「任务已被停用，这次不执行了」。
 *
 * 「用户关掉」和「一次性任务用掉自己」在 `enabled` 这一个布尔上是同一副面孔，
 * 所以这一组的重点全在**怎么把这两件事分开**，而不在返回值本身。
 */
class TaskExecutionGateTest {

    @Test
    fun `启用的任务照做`() {
        assertNull(queuedExecutionSkipReason(periodic(enabled = true), exec(AT)))
        assertNull(queuedExecutionSkipReason(oneShot(enabled = true), exec(AT)))
    }

    @Test
    fun `一次性任务跑完自己关掉，那一次照做`() {
        // 真机上的原样数字：用户 14:16:58 建的任务，定在 14:18:00，闹钟 14:18:46 响。
        // 修复前这一条返回原因字符串，照片没拍、邮件没发。
        val task = oneShot(enabled = false, fireAt = 1_791_785_880_000L)

        assertNull(queuedExecutionSkipReason(task, exec(1_791_785_880_000L)))
    }

    @Test
    fun `用户关掉一次性任务，排队里的活丢掉`() {
        // 与上一条只差一件事：这次发生点和 fireAtEpochMs 对不上（这里一次记录都没排过）。
        val task = oneShot(enabled = false, fireAt = AT + MINUTE)

        assertEquals("任务已被停用，这次不执行了", queuedExecutionSkipReason(task, exec(AT)))
    }

    @Test
    fun `一次性任务被改到别的时刻，旧的排队记录丢掉`() {
        // 这是「用 fireAtEpochMs 当凭据」而不只看 kind 的理由：改过时刻之后，
        // 那条旧记录不再代表任何真实意图，哪怕 kind 还是一样的一次性。
        val task = oneShot(enabled = false, fireAt = AT + HOUR)

        assertEquals("任务已被停用，这次不执行了", queuedExecutionSkipReason(task, exec(AT)))
    }

    @Test
    fun `周期任务被关掉，排队里的活丢掉`() {
        // 周期任务没有「用掉自己」这回事，`enabled = false` 只可能是用户关的。
        val task = periodic(enabled = false)

        assertEquals("任务已被停用，这次不执行了", queuedExecutionSkipReason(task, exec(AT)))
    }

    @Test
    fun `一次性任务没有 fireAt 就不认那次触发`() {
        // 只可能是手工写坏或来自更新版本的库。没有凭据就按停用处理——
        // 认不出来时宁可少做，也不要凭"它大概是一次性任务"去猜。
        val task = oneShot(enabled = false, fireAt = null)

        assertEquals("任务已被停用，这次不执行了", queuedExecutionSkipReason(task, exec(AT)))
    }

    // ---------- 夹具 ----------

    private fun task(
        kind: TaskKind,
        enabled: Boolean,
        fireAt: Long?,
    ): ScheduledTaskEntity = ScheduledTaskEntity(
        id = 2L,
        title = "拍张照片发到邮箱",
        kind = kind.name,
        intervalMinutes = if (kind == TaskKind.INTERVAL) 30 else null,
        hourOfDay = null,
        minuteOfHour = null,
        daysOfWeekMask = null,
        daysOfMonthCsv = null,
        fireAtEpochMs = fireAt,
        anchorEpochMs = 0L,
        action = TaskAction.PHOTO_TO_EMAIL.name,
        text = null,
        emailTo = null,
        emailSubject = null,
        allowCatchUp = true,
        enabled = enabled,
        lastFireAtEpochMs = null,
        nextFireAtEpochMs = null,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private fun oneShot(enabled: Boolean, fireAt: Long? = AT) =
        task(TaskKind.ONE_SHOT, enabled, fireAt)

    private fun periodic(enabled: Boolean) = task(TaskKind.INTERVAL, enabled, fireAt = null)

    private fun exec(scheduledAt: Long): TaskExecutionEntity = TaskExecutionEntity(
        id = 1L,
        taskId = 2L,
        taskTitle = "拍张照片发到邮箱",
        action = TaskAction.PHOTO_TO_EMAIL.name,
        scheduledAtEpochMs = scheduledAt,
        firedAtEpochMs = scheduledAt,
        status = TaskRunStatus.PENDING.name,
        outcome = null,
        isCatchUp = false,
        attempts = 0,
        nextAttemptAtEpochMs = null,
        detail = null,
        createdAtEpochMs = scheduledAt,
    )

    private companion object {
        const val AT = 1_791_785_880_000L
        const val MINUTE = 60_000L
        const val HOUR = 60 * MINUTE
    }
}
