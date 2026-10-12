package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.data.db.TaskExecutionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId

/**
 * 定时任务页面上那些句子的**具体措辞**。
 *
 * 为什么值得逐字钉住：用户在列表里看到的只有这一行，而他要靠它判断
 * "我这条任务会不会按我想的那样响"。写成「每 60 分钟」而不是「每 1 小时」，
 * 设的人就会怀疑是不是没认出来；月度那条不写顺延说明，设「31 号」的人
 * 会以为 2 月被跳过了。
 */
class ScheduledTaskSummaryTest {

    // ---------- 周期描述 ----------

    @Test
    fun `间隔能整除 60 就说小时，否则说分钟`() {
        assertEquals("每 30 分钟", recurrenceSummary(interval(30), SHANGHAI))
        assertEquals("每 1 小时", recurrenceSummary(interval(60), SHANGHAI))
        assertEquals("每 2 小时", recurrenceSummary(interval(120), SHANGHAI))
        assertEquals("每 90 分钟", recurrenceSummary(interval(90), SHANGHAI))
    }

    @Test
    fun `间隔非正数时明说无效，不要说每 0 分钟`() {
        assertEquals("间隔无效", recurrenceSummary(interval(0), SHANGHAI))
        assertEquals("间隔无效", recurrenceSummary(interval(-10), SHANGHAI))
    }

    @Test
    fun `每天任务带补零的时刻`() {
        assertEquals("每天 08:30", recurrenceSummary(daily(8, 30), SHANGHAI))
        assertEquals("每天 00:05", recurrenceSummary(daily(0, 5), SHANGHAI))
    }

    @Test
    fun `每周任务把勾选的星期连起来写`() {
        val spec = weekly(8, 30, DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

        assertEquals("每周一三五 08:30", recurrenceSummary(spec, SHANGHAI))
    }

    @Test
    fun `每天都勾上时就说每天，不写七个周一…周日`() {
        val spec = weekly(8, 30, *DayOfWeek.entries.toTypedArray())

        assertEquals("每天 08:30", recurrenceSummary(spec, SHANGHAI))
    }

    @Test
    fun `一天都没勾时明说没选星期，而不是拼出一句残话`() {
        assertEquals("没有选星期", recurrenceSummary(weekly(8, 30), SHANGHAI))
        assertEquals("没有选日期", recurrenceSummary(monthly(9, 0), SHANGHAI))
    }

    @Test
    fun `月度任务列出日期并说明顺延规则`() {
        val spec = monthly(9, 0, 1, 15)

        assertEquals("每月 1、15 号 09:00", recurrenceSummary(spec, SHANGHAI))
    }

    @Test
    fun `月度日期里有 29 号以上时必须说明顺延，否则用户以为 2 月被跳过`() {
        val spec = monthly(9, 0, 31)

        val text = recurrenceSummary(spec, SHANGHAI)

        assertTrue(text.startsWith("每月 31 号 09:00"))
        assertTrue(text.contains("顺延到月底"))
    }

    @Test
    fun `一次性任务写成具体日期时刻`() {
        val fireAt = LocalDate.of(2026, 3, 1).atTime(8, 30).atZone(SHANGHAI).toInstant().toEpochMilli()

        assertEquals("2026-03-01 08:30 一次", recurrenceSummary(oneShot(fireAt), SHANGHAI))
    }

    // ---------- 下次触发 ----------

    @Test
    fun `一小时内用相对时间，超过一小时后改用今天明天`() {
        val now = atLocal(2026, 3, 1, 8, 0)

        assertEquals("不到 1 分钟后", nextFireSummary(now + 30_000, now, SHANGHAI))
        assertEquals("5 分钟后", nextFireSummary(now + 5 * 60_000, now, SHANGHAI))
        assertEquals("今天 09:30", nextFireSummary(atLocal(2026, 3, 1, 9, 30), now, SHANGHAI))
        assertEquals("明天 08:30", nextFireSummary(atLocal(2026, 3, 2, 8, 30), now, SHANGHAI))
        assertEquals("3 月 15 日 08:30", nextFireSummary(atLocal(2026, 3, 15, 8, 30), now, SHANGHAI))
    }

    @Test
    fun `没有下一次和已经到点要分开说`() {
        val now = atLocal(2026, 3, 1, 8, 0)

        assertEquals("没有下一次", nextFireSummary(null, now, SHANGHAI))
        assertEquals("已经到点了", nextFireSummary(now, now, SHANGHAI))
        assertEquals("已经到点了", nextFireSummary(now - 60_000, now, SHANGHAI))
    }

    // ---------- 执行记录 ----------

    @Test
    fun `补跑成功要能被认出来，不能混进普通的已发送`() {
        assertEquals("已发送", executionSummary(execution(status = "DONE", outcome = "SENT")))
        assertEquals("补跑成功", executionSummary(execution(status = "DONE", outcome = "SENT", catchUp = true)))
        assertEquals("补跑成功", executionSummary(execution(status = "DONE", outcome = "CATCH_UP")))
    }

    @Test
    fun `每种失败都有各自的说法`() {
        assertEquals("错过了，未补跑", executionSummary(execution(status = "DONE", outcome = "SKIPPED_MISSED")))
        assertEquals("设备一直忙，没发出去", executionSummary(execution(status = "FAILED", outcome = "SKIPPED_BUSY")))
        assertEquals("发信失败", executionSummary(execution(status = "FAILED", outcome = "FAILED_EMAIL")))
        assertEquals("拍照失败", executionSummary(execution(status = "FAILED", outcome = "FAILED_CAPTURE")))
    }

    @Test
    fun `排队中和执行中要看得出来`() {
        assertEquals("排队中", executionSummary(execution(status = "PENDING")))
        assertEquals("正在执行", executionSummary(execution(status = "RUNNING")))
    }

    @Test
    fun `中文原因跟在结论后面，因为用户要的正是那半句`() {
        val text = executionSummary(
            execution(status = "FAILED", outcome = "FAILED_EMAIL", detail = "连不上服务器，请检查地址和端口"),
        )

        assertEquals("发信失败（连不上服务器，请检查地址和端口）", text)
    }

    @Test
    fun `状态认不出来时说不明，而不是当成成功`() {
        assertEquals("状态不明", executionSummary(execution(status = "WHATEVER")))
    }

    // ---------- 任务列表的一行 ----------

    @Test
    fun `一行里是做什么、什么时候、下一次`() {
        val now = atLocal(2026, 3, 1, 8, 0)
        val next = atLocal(2026, 3, 1, 8, 30)

        // 「做什么」必须在最前面：光看「每天 08:30」看不出这条任务到点会说话还是拍照。
        assertEquals(
            "给助手说句话 · 每天 08:30 · 下次：30 分钟后",
            taskRowSummary(task(nextFireAt = next), now, SHANGHAI),
        )
        assertEquals(
            "拍张照片发邮箱 · 每天 08:30 · 下次：30 分钟后",
            taskRowSummary(task(action = TaskAction.PHOTO_TO_EMAIL.name, nextFireAt = next), now, SHANGHAI),
        )
    }

    @Test
    fun `停用的任务不说下一次`() {
        // 停用的行里 nextFireAt 是停用前留下的旧值，照念出来等于在暗示它还会响。
        assertEquals(
            "给助手说句话 · 每天 08:30 · 已停用",
            taskRowSummary(task(enabled = false, nextFireAt = atLocal(2026, 3, 1, 8, 0)), atLocal(2026, 3, 1, 8, 0), SHANGHAI),
        )
    }

    @Test
    fun `没有下一次时只念一句，不念成下次没有下一次`() {
        assertEquals(
            "给助手说句话 · 每天 08:30 · 没有下一次",
            taskRowSummary(task(nextFireAt = null), atLocal(2026, 3, 1, 8, 0), SHANGHAI),
        )
    }

    @Test
    fun `动作或周期认不出来时也不崩`() {
        val now = atLocal(2026, 3, 1, 8, 0)
        val next = atLocal(2026, 3, 1, 8, 30)

        assertEquals(
            "不认识的动作 · 每天 08:30 · 下次：30 分钟后",
            taskRowSummary(task(action = "SOMETHING_NEW", nextFireAt = next), now, SHANGHAI),
        )
        assertEquals(
            "给助手说句话 · 周期认不出来 · 下次：30 分钟后",
            taskRowSummary(task(kind = "SOMETHING_NEW", nextFireAt = next), now, SHANGHAI),
        )
    }

    // ---------- 助手 ----------

    private fun atLocal(y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDate.of(y, m, d).atTime(h, min).atZone(SHANGHAI).toInstant().toEpochMilli()

    private fun task(
        kind: String = TaskKind.DAILY.name,
        action: String = TaskAction.INJECT_TEXT.name,
        enabled: Boolean = true,
        nextFireAt: Long? = null,
    ) = ScheduledTaskEntity(
        id = 1L,
        title = "测试任务",
        kind = kind,
        intervalMinutes = null,
        hourOfDay = 8,
        minuteOfHour = 30,
        daysOfWeekMask = null,
        daysOfMonthCsv = null,
        fireAtEpochMs = null,
        anchorEpochMs = 0L,
        action = action,
        text = "该吃药了",
        emailTo = null,
        emailSubject = null,
        allowCatchUp = true,
        enabled = enabled,
        lastFireAtEpochMs = null,
        nextFireAtEpochMs = nextFireAt,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )

    private fun interval(minutes: Int) =
        RecurrenceSpec(TaskKind.INTERVAL, intervalMinutes = minutes, anchorEpochMs = 0)

    private fun daily(hour: Int, minute: Int) =
        RecurrenceSpec(TaskKind.DAILY, hourOfDay = hour, minuteOfHour = minute, anchorEpochMs = 0)

    private fun weekly(hour: Int, minute: Int, vararg days: DayOfWeek) =
        RecurrenceSpec(
            TaskKind.WEEKLY,
            hourOfDay = hour,
            minuteOfHour = minute,
            daysOfWeekMask = weekMaskOf(days.toSet()),
            anchorEpochMs = 0,
        )

    private fun monthly(hour: Int, minute: Int, vararg days: Int) =
        RecurrenceSpec(TaskKind.MONTHLY, hourOfDay = hour, minuteOfHour = minute, daysOfMonth = days.toList(), anchorEpochMs = 0)

    private fun oneShot(fireAt: Long) =
        RecurrenceSpec(TaskKind.ONE_SHOT, fireAtEpochMs = fireAt, anchorEpochMs = fireAt)

    private fun execution(status: String, outcome: String? = null, detail: String? = null, catchUp: Boolean = false) =
        TaskExecutionEntity(
            id = 1L,
            taskId = 1L,
            taskTitle = "测试任务",
            action = "INJECT_TEXT",
            scheduledAtEpochMs = 0L,
            firedAtEpochMs = null,
            status = status,
            outcome = outcome,
            isCatchUp = catchUp,
            attempts = 0,
            nextAttemptAtEpochMs = null,
            detail = detail,
            createdAtEpochMs = 0L,
        )

    private companion object {
        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
