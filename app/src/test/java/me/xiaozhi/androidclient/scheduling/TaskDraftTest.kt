package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 表单那一层的账。
 *
 * 这里测的不是"能不能存下去"，而是**"存下去之后会不会响"**——一条任务最糟的失败不是报错，
 * 而是安静地躺在列表里一次都不响。所以每一条校验、每一个 `toEntity` 的分支，
 * 都配了一次真的 `NextFireTimeCalculator` 计算来收尾。
 *
 * 时区固定成 [ZONE]（没有夏令时，结论不随机器走）。
 */
class TaskDraftTest {

    @Test
    fun `没名字的任务不能让存`() {
        val draft = TaskDraft(title = "   ", kind = TaskKind.DAILY, text = "hello")

        assertEquals("给这条任务起个名字", draft.validate(ZONE, NOW))
    }

    @Test
    fun `名字最多 40 个字`() {
        val justRight = TaskDraft(title = "名".repeat(TaskDraft.MAX_TITLE_LENGTH), text = "hello")
        val tooLong = justRight.copy(title = "名".repeat(TaskDraft.MAX_TITLE_LENGTH + 1))

        assertNull(justRight.validate(ZONE, NOW))
        assertEquals("名字最多 40 个字", tooLong.validate(ZONE, NOW))
    }

    @Test
    fun `间隔必须是个在范围内的数字`() {
        val base = TaskDraft(title = "喝水", kind = TaskKind.INTERVAL, text = "喝点水")

        assertEquals("间隔要填数字，比如 30", base.copy(intervalMinutesText = "半小时").validate(ZONE, NOW))
        assertEquals("间隔要填 1 分钟到 30 天之间", base.copy(intervalMinutesText = "0").validate(ZONE, NOW))
        assertEquals("间隔要填 1 分钟到 30 天之间", base.copy(intervalMinutesText = "-5").validate(ZONE, NOW))
        assertEquals("间隔要填 1 分钟到 30 天之间", base.copy(intervalMinutesText = "43201").validate(ZONE, NOW))

        assertNull(base.copy(intervalMinutesText = "1").validate(ZONE, NOW))
        assertNull(base.copy(intervalMinutesText = "43200").validate(ZONE, NOW))
    }

    @Test
    fun `每天的时间要填成 08 点 30 分这样`() {
        val base = TaskDraft(title = "吃药", kind = TaskKind.DAILY, text = "该吃药了")

        assertEquals("时间要填成 08:30 这样", base.copy(clockText = "8点半").validate(ZONE, NOW))
        assertEquals("时间要填成 08:30 这样", base.copy(clockText = "").validate(ZONE, NOW))
        assertEquals("时间要填成 08:30 这样", base.copy(clockText = "25:00").validate(ZONE, NOW))

        assertNull(base.copy(clockText = "08:30").validate(ZONE, NOW))
    }

    @Test
    fun `每周任务一个都没勾就不成立`() {
        // 掩码是 0 时计算器直接返回 null —— 一条永远不会响的任务。这一条必须在保存前拦住。
        val draft = TaskDraft(title = "例会", kind = TaskKind.WEEKLY, text = "开周会")

        assertEquals("至少要选一个星期", draft.validate(ZONE, NOW))
        assertNull(draft.copy(weekdays = setOf(DayOfWeek.MONDAY)).validate(ZONE, NOW))
    }

    @Test
    fun `每月任务要填几号`() {
        val draft = TaskDraft(title = "交房租", kind = TaskKind.MONTHLY, text = "交房租")

        assertEquals("要填每月几号，比如 1,15", draft.copy(daysOfMonthText = "").validate(ZONE, NOW))
        // 0 和 32 不是"错得很明显"，是会被静默丢掉的脏值 —— 丢完就成了空列表，一样得拦住。
        assertEquals("要填每月几号，比如 1,15", draft.copy(daysOfMonthText = "0,32").validate(ZONE, NOW))

        assertNull(draft.copy(daysOfMonthText = "1,15").validate(ZONE, NOW))
    }

    @Test
    fun `一次性任务必须在将来`() {
        val base = TaskDraft(
            title = "提醒我",
            kind = TaskKind.ONE_SHOT,
            oneShotDateText = "2026-03-11",
            oneShotTimeText = "09:00",
            text = "该走了",
        )

        assertNull(base.validate(ZONE, NOW))
        // 过去的时刻算出来是 null，任务一建出来就是死的 —— 不如直接说清楚。
        assertEquals(
            "这个时刻已经过去了，换个未来的时间",
            base.copy(oneShotDateText = "2026-03-01").validate(ZONE, NOW),
        )
        // 正好等于"现在"也不行：计算器要求严格晚于当下。
        assertEquals(
            "这个时刻已经过去了，换个未来的时间",
            base.copy(oneShotDateText = "2026-03-10", oneShotTimeText = "09:00").validate(ZONE, NOW),
        )
        assertEquals("日期要填成 2026-03-01 这样", base.copy(oneShotDateText = "3月11日").validate(ZONE, NOW))
    }

    @Test
    fun `要给助手说的话不能是空的`() {
        val draft = TaskDraft(title = "问好", kind = TaskKind.DAILY, clockText = "09:00", text = "   ")

        assertEquals("要给助手说的话不能为空", draft.validate(ZONE, NOW))
        assertNull(draft.copy(text = "早上好").validate(ZONE, NOW))
    }

    @Test
    fun `拍照任务的收件人留空是合法的`() {
        // 留空 = "发到邮件设置里的默认收件人"。把它当错误拦住，等于逼用户
        // 在每条任务里重复填一遍同一个邮箱。
        val draft = TaskDraft(
            title = "看看家里",
            kind = TaskKind.DAILY,
            clockText = "12:00",
            action = TaskAction.PHOTO_TO_EMAIL,
            emailTo = "",
        )

        assertNull(draft.validate(ZONE, NOW))
        assertNull(draft.copy(emailTo = "  ").validate(ZONE, NOW))
    }

    @Test
    fun `拍照任务的收件人填了就要像邮箱`() {
        val draft = TaskDraft(
            title = "看看家里",
            kind = TaskKind.DAILY,
            clockText = "12:00",
            action = TaskAction.PHOTO_TO_EMAIL,
        )

        assertEquals("收件人邮箱格式不对", draft.copy(emailTo = "zoneben").validate(ZONE, NOW))
        assertNull(draft.copy(emailTo = "zoneben@example.com").validate(ZONE, NOW))
    }

    @Test
    fun `每天任务会带着算好的下一次触发一起出来`() {
        val draft = TaskDraft(title = "吃药", kind = TaskKind.DAILY, clockText = "08:30", text = "该吃药了")

        // NOW 是 03-10 09:00，今天 08:30 已经过了，所以落到明天。
        assertEquals(at(2026, 3, 11, 8, 30), draft.toEntity(null, ZONE, NOW).nextFireAtEpochMs)
    }

    @Test
    fun `一次性任务的下一次触发就是它自己`() {
        val draft = TaskDraft(
            title = "提醒我",
            kind = TaskKind.ONE_SHOT,
            oneShotDateText = "2026-03-11",
            oneShotTimeText = "09:00",
            text = "该走了",
        )

        val entity = draft.toEntity(null, ZONE, NOW)

        assertEquals(at(2026, 3, 11, 9, 0), entity.nextFireAtEpochMs)
        assertEquals(at(2026, 3, 11, 9, 0), entity.fireAtEpochMs)
    }

    @Test
    fun `一行只带当前周期用得到的字段`() {
        // 用户从"每月"改成"每天"之后，如果原来的 1,15 还留在行里，将来改回"每月"
        // 就会冒出几个他早忘了的值。
        val draft = TaskDraft(
            title = "吃药",
            kind = TaskKind.DAILY,
            clockText = "08:30",
            weekdays = setOf(DayOfWeek.MONDAY),
            daysOfMonthText = "1,15",
            intervalMinutesText = "30",
            text = "该吃药了",
        )

        val entity = draft.toEntity(null, ZONE, NOW)

        assertNull(entity.daysOfWeekMask)
        assertNull(entity.daysOfMonthCsv)
        assertNull(entity.intervalMinutes)
        assertNull(entity.fireAtEpochMs)
        assertEquals(8, entity.hourOfDay)
        assertEquals(30, entity.minuteOfHour)
    }

    @Test
    fun `换动作之后上一种动作的字段要被清掉`() {
        val photo = TaskDraft(
            title = "看看家里",
            kind = TaskKind.DAILY,
            clockText = "12:00",
            action = TaskAction.PHOTO_TO_EMAIL,
            text = "这句是给助手说的",
            emailTo = "zoneben@example.com",
            emailSubject = "家里的样子",
        )

        val sent = photo.toEntity(null, ZONE, NOW)
        assertNull(sent.text)
        assertEquals("zoneben@example.com", sent.emailTo)

        val spoken = photo.copy(action = TaskAction.INJECT_TEXT, text = "看看家里").toEntity(null, ZONE, NOW)
        assertNull(spoken.emailTo)
        assertNull(spoken.emailSubject)
        assertEquals("看看家里", spoken.text)
    }

    @Test
    fun `编辑往返一趟不丢字段`() {
        val existing = ScheduledTaskEntity(
            id = 7L,
            title = "开会提醒",
            kind = TaskKind.WEEKLY.name,
            intervalMinutes = null,
            hourOfDay = 7,
            minuteOfHour = 5,
            daysOfWeekMask = weekMaskOf(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)),
            daysOfMonthCsv = null,
            fireAtEpochMs = null,
            anchorEpochMs = at(2026, 1, 1, 0, 0),
            action = TaskAction.INJECT_TEXT.name,
            text = "十分钟后开会",
            emailTo = null,
            emailSubject = null,
            allowCatchUp = false,
            enabled = true,
            lastFireAtEpochMs = at(2026, 3, 9, 7, 5),
            nextFireAtEpochMs = at(2026, 3, 13, 7, 5),
            createdAtEpochMs = at(2026, 1, 1, 0, 0),
            updatedAtEpochMs = at(2026, 1, 1, 0, 0),
        )

        val back = existing.toDraft().toEntity(existing, ZONE, NOW)

        assertEquals(7L, back.id)
        assertEquals(existing.title, back.title)
        assertEquals(existing.kind, back.kind)
        assertEquals(existing.daysOfWeekMask, back.daysOfWeekMask)
        assertEquals(existing.hourOfDay, back.hourOfDay)
        assertEquals(existing.minuteOfHour, back.minuteOfHour)
        assertEquals(existing.text, back.text)
        assertEquals(existing.allowCatchUp, back.allowCatchUp)
        // 记账字段不是用户能编辑的东西，编辑一次任务不该把它抹掉。
        assertEquals(existing.createdAtEpochMs, back.createdAtEpochMs)
        assertEquals(existing.lastFireAtEpochMs, back.lastFireAtEpochMs)
        assertEquals(NOW, back.updatedAtEpochMs)
        // NOW 是周二 09:00，下一次命中是周五 07:05。
        assertEquals(at(2026, 3, 13, 7, 5), back.nextFireAtEpochMs)
    }

    @Test
    fun `只改名字不会动间隔的锚点`() {
        // 锚点一动，"每 30 分钟"的相位就漂了：本来是整点整半点响，改完名字变成 :07/:37。
        val existing = intervalTask(anchor = at(2026, 1, 1, 0, 0), minutes = 30)

        val named = existing.toDraft().copy(title = "换个名字").toEntity(existing, ZONE, NOW)

        assertEquals(existing.anchorEpochMs, named.anchorEpochMs)
    }

    @Test
    fun `改了间隔本身就要重新锚`() {
        // 7 分钟的格子套在半小时的网格上，第一个点会落在谁也说不清的位置 —— 重锚才是用户心里那个意思。
        val existing = intervalTask(anchor = at(2026, 1, 1, 0, 0), minutes = 30)

        val changed = existing.toDraft()
            .copy(intervalMinutesText = "7")
            .toEntity(existing, ZONE, NOW)

        assertEquals(NOW, changed.anchorEpochMs)
        assertEquals(NOW + 7 * 60_000L, changed.nextFireAtEpochMs)
    }

    @Test
    fun `新建的间隔任务从此刻起算`() {
        val draft = TaskDraft(title = "喝水", kind = TaskKind.INTERVAL, intervalMinutesText = "60", text = "喝点水")

        val entity = draft.toEntity(null, ZONE, NOW)

        assertEquals(NOW, entity.anchorEpochMs)
        assertEquals(NOW + 60 * 60_000L, entity.nextFireAtEpochMs)
        assertEquals(NOW, entity.createdAtEpochMs)
        assertNull(entity.lastFireAtEpochMs)
    }

    @Test
    fun `停用的一次性任务即使时间已过也存得下去`() {
        // 一次性任务用掉之后会被停用但**不删**，用户点开它仍然该能看懂、能改名。
        val draft = TaskDraft(
            title = "上次那个",
            kind = TaskKind.ONE_SHOT,
            oneShotDateText = "2026-03-01",
            oneShotTimeText = "09:00",
            text = "该走了",
            enabled = false,
        )

        assertNull(draft.validate(ZONE, NOW))
        assertNull(draft.toEntity(null, ZONE, NOW).nextFireAtEpochMs)
    }

    @Test
    fun `新建表单的一次性日期默认填今天`() {
        val draft = newTaskDraft(ZONE)

        assertNotNull(draft.oneShotDateText)
        assertNull(
            runCatching { java.time.LocalDate.parse(draft.oneShotDateText) }.exceptionOrNull(),
        )
    }

    private companion object {
        val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

        /** 2026-03-10 09:00（周二）。全部用例共用这一个"现在"，结论才好对照。 */
        val NOW: Long = at(2026, 3, 10, 9, 0)

        fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
            LocalDateTime.of(year, month, day, hour, minute).atZone(ZONE).toInstant().toEpochMilli()

        fun intervalTask(anchor: Long, minutes: Int) = ScheduledTaskEntity(
            id = 3L,
            title = "喝水",
            kind = TaskKind.INTERVAL.name,
            intervalMinutes = minutes,
            hourOfDay = null,
            minuteOfHour = null,
            daysOfWeekMask = null,
            daysOfMonthCsv = null,
            fireAtEpochMs = null,
            anchorEpochMs = anchor,
            action = TaskAction.INJECT_TEXT.name,
            text = "喝点水",
            emailTo = null,
            emailSubject = null,
            allowCatchUp = true,
            enabled = true,
            lastFireAtEpochMs = null,
            nextFireAtEpochMs = anchor,
            createdAtEpochMs = anchor,
            updatedAtEpochMs = anchor,
        )
    }
}
