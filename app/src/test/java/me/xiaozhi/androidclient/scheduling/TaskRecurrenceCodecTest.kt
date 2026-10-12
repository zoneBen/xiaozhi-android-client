package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek

/**
 * 库里的行 ↔ [RecurrenceSpec]，以及输入侧的规整。
 *
 * 这里每一条都在防同一类事故：**任务建好了、界面看着也对，但一次都不响**。
 * 原因永远是某个字段变成了 [NextFireTimeCalculator] 算不出来的值（掩码 0、
 * 日期写了 0 或 32），却没人报错。所以断言的是"规整之后是什么"，不是"没崩就过"。
 */
class TaskRecurrenceCodecTest {

    // ---------- 月度日期 ----------

    @Test
    fun `逗号分隔的日期串按数值排序去重`() {
        assertEquals(listOf(1, 15, 31), parseDaysOfMonth("31, 1 ,15,1"))
    }

    @Test
    fun `0 和 32 这种越界值直接丢掉，不留到计算里变成永不触发`() {
        // 留着它们的话，Calculator 那边只会算不出来 —— 界面显示得好好的，闹钟一次不响。
        assertEquals(listOf(15), parseDaysOfMonth("0,15,32,99"))
        assertEquals(emptyList<Int>(), parseDaysOfMonth("0,32"))
    }

    @Test
    fun `空串、null、纯空格、非数字都解析成空列表而不是抛异常`() {
        assertEquals(emptyList<Int>(), parseDaysOfMonth(null))
        assertEquals(emptyList<Int>(), parseDaysOfMonth(""))
        assertEquals(emptyList<Int>(), parseDaysOfMonth("   "))
        assertEquals(emptyList<Int>(), parseDaysOfMonth("每月一号"))
    }

    @Test
    fun `写回库里的格式能被自己读回来`() {
        val days = listOf(31, 1, 15, 1)

        assertEquals(listOf(1, 15, 31), parseDaysOfMonth(formatDaysOfMonth(days)))
        assertEquals(listOf(1, 15, 31), parseDaysOfMonth(formatDaysOfMonth(parseDaysOfMonth("15,1,31"))))
    }

    // ---------- 星期掩码 ----------

    @Test
    fun `星期一对应 bit0，与 DayOfWeek value 减一 对齐`() {
        assertEquals(1 shl 0, weekMaskOf(setOf(DayOfWeek.MONDAY)))
        assertEquals(1 shl 6, weekMaskOf(setOf(DayOfWeek.SUNDAY)))
    }

    @Test
    fun `掩码与星期集合能往返`() {
        val days = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY)

        val mask = weekMaskOf(days)

        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY, DayOfWeek.FRIDAY), daysOfWeekFrom(mask))
        assertEquals(mask, weekMaskOf(daysOfWeekFrom(mask).toSet()))
    }

    @Test
    fun `掩码为 0 时解不出任何星期`() {
        assertEquals(emptyList<DayOfWeek>(), daysOfWeekFrom(0))
    }

    // ---------- 实体 → 周期描述 ----------

    @Test
    fun `每周任务的实体能还原成带星期掩码的周期`() {
        val entity = task(kind = "WEEKLY", hour = 8, minute = 30, mask = weekMaskOf(setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)))

        val spec = entity.recurrenceSpec()

        assertEquals(TaskKind.WEEKLY, spec?.kind)
        assertEquals(8, spec?.hourOfDay)
        assertEquals(30, spec?.minuteOfHour)
        assertEquals(listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY), daysOfWeekFrom(spec!!.daysOfWeekMask))
    }

    @Test
    fun `月度任务的 csv 在还原时被规整`() {
        val entity = task(kind = "MONTHLY", hour = 9, minute = 0, daysCsv = "1,31,31,0")

        val spec = entity.recurrenceSpec()

        assertEquals(listOf(1, 31), spec?.daysOfMonth)
    }

    @Test
    fun `认不出的 kind 返回 null，不猜一个周期出来`() {
        // 库里的字符串将来可能来自更新的版本。猜一个（比如兜底成每天）会替用户做他没选的事，
        // 而且是"静默地在错的时间响"。宁可任务被跳过、在执行记录里看得见没跑。
        assertNull(task(kind = "EVERY_FULL_MOON").recurrenceSpec())
        assertNull(parseTaskKind(""))
        assertNull(parseTaskKind(null))
    }

    @Test
    fun `可选字段留 null 时给出安全默认值而不是崩`() {
        val entity = task(kind = "INTERVAL", interval = 30)

        val spec = entity.recurrenceSpec()

        assertEquals(0, spec?.hourOfDay)
        assertEquals(0, spec?.daysOfWeekMask)
        assertEquals(emptyList<Int>(), spec?.daysOfMonth)
    }

    @Test
    fun `间隔任务带着锚点，重启后网格不会变`() {
        val entity = task(kind = "INTERVAL", interval = 30, anchor = 1_700_000_000_000L)

        assertEquals(1_700_000_000_000L, entity.recurrenceSpec()?.anchorEpochMs)
    }

    private fun task(
        kind: String,
        interval: Int? = null,
        hour: Int? = null,
        minute: Int? = null,
        mask: Int? = null,
        daysCsv: String? = null,
        anchor: Long = 0L,
    ) = ScheduledTaskEntity(
        title = "测试任务",
        kind = kind,
        intervalMinutes = interval,
        hourOfDay = hour,
        minuteOfHour = minute,
        daysOfWeekMask = mask,
        daysOfMonthCsv = daysCsv,
        fireAtEpochMs = null,
        anchorEpochMs = anchor,
        action = "INJECT_TEXT",
        text = "该喝水了",
        emailTo = null,
        emailSubject = null,
        allowCatchUp = true,
        enabled = true,
        lastFireAtEpochMs = null,
        nextFireAtEpochMs = null,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )
}
