package me.xiaozhi.androidclient.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 「下一次什么时候响」的边界。
 *
 * 这些用例不是凑覆盖率：每一条都对应一种**真机上会让人骂人**的错法——
 * 闰月里 31 号的任务一次不响、夏天少响一次、闹钟定在一个永远不前进的时刻上。
 * 所以断言尽量写"具体到哪一天哪一刻"，而不是只断言"不为 null"。
 *
 * 所有用例使用**固定时区**（[SHANGHAI] 或 DST 那两个），不依赖跑测试的这台机器的时区。
 */
class NextFireTimeCalculatorTest {

    // ---------- 一次性 ----------

    @Test
    fun `一次性任务，after 恰好等于触发时刻时要返回 null 而不是它自己`() {
        // 相等不算"下一次"。返回它自己的话，闹钟到点重算还是同一秒，会连环触发。
        val fireAt = at(SHANGHAI, 2026, 3, 1, 8, 30)
        val spec = oneShot(fireAt)

        assertNull(NextFireTimeCalculator.nextFireAt(spec, fireAt, SHANGHAI))
    }

    @Test
    fun `一次性任务，时刻已经过去就返回 null`() {
        val fireAt = at(SHANGHAI, 2026, 3, 1, 8, 30)

        assertNull(NextFireTimeCalculator.nextFireAt(oneShot(fireAt), fireAt + 60_000, SHANGHAI))
    }

    @Test
    fun `一次性任务，还没到就返回那个时刻`() {
        val fireAt = at(SHANGHAI, 2026, 3, 1, 8, 30)

        assertEquals(fireAt, NextFireTimeCalculator.nextFireAt(oneShot(fireAt), fireAt - 1, SHANGHAI))
    }

    // ---------- 重复间隔 ----------

    @Test
    fun `间隔任务连取三次是等差数列，不会随触发次数漂移`() {
        // 这条钉的是"闭式公式而不是累加"：累加版每轮都往上取整一点，跑一天就偏出一截。
        val anchor = at(SHANGHAI, 2026, 3, 1, 8, 0)
        val spec = RecurrenceSpec(TaskKind.INTERVAL, intervalMinutes = 30, anchorEpochMs = anchor)

        val first = NextFireTimeCalculator.nextFireAt(spec, anchor, SHANGHAI)
        val second = NextFireTimeCalculator.nextFireAt(spec, first!!, SHANGHAI)
        val third = NextFireTimeCalculator.nextFireAt(spec, second!!, SHANGHAI)

        assertEquals(anchor + MINUTE_MS * 30, first)
        assertEquals(anchor + MINUTE_MS * 60, second)
        assertEquals(anchor + MINUTE_MS * 90, third)
    }

    @Test
    fun `间隔任务，after 早于锚点时返回第一个未来的格子`() {
        // 时钟被 NTP 往回拨、或补跑时算出比锚点还早的时刻，都会走这条。用 floorDiv 才对：
        // 普通整除朝零取整，会返回一个已经过去的时刻。
        // 网格是 锚点 + k×10 分钟，after 在锚点前 25 分钟 → 第一个未来的格子是锚点前 20 分钟。
        val anchor = at(SHANGHAI, 2026, 3, 1, 8, 0)
        val spec = RecurrenceSpec(TaskKind.INTERVAL, intervalMinutes = 10, anchorEpochMs = anchor)

        assertEquals(anchor - MINUTE_MS * 20, NextFireTimeCalculator.nextFireAt(spec, anchor - MINUTE_MS * 25, SHANGHAI))
    }

    @Test
    fun `间隔为 0 或负数时返回 null`() {
        val anchor = at(SHANGHAI, 2026, 3, 1, 8, 0)

        assertNull(NextFireTimeCalculator.nextFireAt(RecurrenceSpec(TaskKind.INTERVAL, intervalMinutes = 0, anchorEpochMs = anchor), anchor, SHANGHAI))
        assertNull(NextFireTimeCalculator.nextFireAt(RecurrenceSpec(TaskKind.INTERVAL, intervalMinutes = -5, anchorEpochMs = anchor), anchor, SHANGHAI))
    }

    // ---------- 每天 ----------

    @Test
    fun `每天任务，今天那一刻还没到就是今天`() {
        val spec = daily(8, 30)
        val now = at(SHANGHAI, 2026, 3, 1, 8, 29)

        assertEquals(at(SHANGHAI, 2026, 3, 1, 8, 30), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    @Test
    fun `每天任务，今天那一刻过了就顺延到明天`() {
        val spec = daily(8, 30)
        val now = at(SHANGHAI, 2026, 3, 1, 8, 31)

        assertEquals(at(SHANGHAI, 2026, 3, 2, 8, 30), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    @Test
    fun `每天任务，跨年时落到次年 1 月`() {
        val spec = daily(0, 5)
        val now = at(SHANGHAI, 2026, 12, 31, 23, 0)

        assertEquals(at(SHANGHAI, 2027, 1, 1, 0, 5), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    @Test
    fun `每天任务，时刻字段越界时返回 null 而不是抛异常`() {
        val now = at(SHANGHAI, 2026, 3, 1, 0, 0)

        assertNull(NextFireTimeCalculator.nextFireAt(daily(24, 0), now, SHANGHAI))
        assertNull(NextFireTimeCalculator.nextFireAt(daily(8, 60), now, SHANGHAI))
    }

    // ---------- 夏令时（每天/每周共用同一段落地代码） ----------

    @Test
    fun `每天任务落在春季跳变的空洞里时，不抛异常且仍在当天`() {
        // 纽约 2024-03-10 02:00 直接跳到 03:00，本地 2:30 这一天不存在。
        // java.time 会把结果往后推到跳变之后（03:30），关键是"还在当天、且是未来"。
        val newYork = ZoneId.of("America/New_York")
        val spec = daily(2, 30)
        val now = at(newYork, 2024, 3, 9, 12, 0)

        val next = NextFireTimeCalculator.nextFireAt(spec, now, newYork)

        assertNotNull(next)
        val fireDate = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next!!), newYork)
        assertEquals(LocalDate.of(2024, 3, 10), fireDate.toLocalDate())
        assertTrue("跳变之后必须仍是未来时刻", next > now)
    }

    @Test
    fun `每天任务落在秋季回拨的重叠里时，取较早的那个 offset`() {
        // 柏林 2024-10-27 03:00 回拨到 02:00，本地 2:30 出现两次。
        // 取较早的那个 = 先到的那次，用户不会被同一句提醒叫两次。
        val berlin = ZoneId.of("Europe/Berlin")
        val spec = daily(2, 30)
        val now = at(berlin, 2024, 10, 26, 12, 0)

        val next = NextFireTimeCalculator.nextFireAt(spec, now, berlin)

        val fire = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(next!!), berlin)
        assertEquals(LocalDate.of(2024, 10, 27), fire.toLocalDate())
        assertEquals(2, fire.hour)
        assertEquals(30, fire.minute)
        // 较早那个 offset 相对 UTC 是 +2 小时（夏令时还没结束）。
        assertEquals(2, nextDurationHours(next, berlin))
    }

    // ---------- 每周 ----------

    @Test
    fun `每周任务，掩码是 0 时立即返回 null`() {
        // 不先出局的话下面那圈 0..7 一圈都命中不了，白转一轮还是 null——但更容易写错成死循环。
        val now = at(SHANGHAI, 2026, 3, 1, 0, 0)

        assertNull(NextFireTimeCalculator.nextFireAt(RecurrenceSpec(TaskKind.WEEKLY, hourOfDay = 8, minuteOfHour = 30, daysOfWeekMask = 0, anchorEpochMs = now), now, SHANGHAI))
    }

    @Test
    fun `每周任务，今天就是那一天但时刻已过，要顺延到下周一`() {
        // 2026-03-02 是周一。
        val spec = weekly(8, 30, MON)
        val now = at(SHANGHAI, 2026, 3, 2, 9, 0)

        assertEquals(at(SHANGHAI, 2026, 3, 9, 8, 30), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    @Test
    fun `每周一和周五的任务，在周三时返回周五`() {
        val spec = weekly(8, 30, MON or FRI)
        val now = at(SHANGHAI, 2026, 3, 4, 12, 0) // 周三

        assertEquals(at(SHANGHAI, 2026, 3, 6, 8, 30), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    @Test
    fun `每周七天全选等价于每天`() {
        val spec = weekly(8, 30, MON or TUE or WED or THU or FRI or SAT or SUN)
        val now = at(SHANGHAI, 2026, 3, 4, 12, 0)

        assertEquals(at(SHANGHAI, 2026, 3, 5, 8, 30), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    @Test
    fun `每周任务跨年时落到次年 1 月`() {
        // 2026-12-31 是周四，2027-01-04 是周一。
        val spec = weekly(8, 30, MON)
        val now = at(SHANGHAI, 2026, 12, 31, 23, 0)

        assertEquals(at(SHANGHAI, 2027, 1, 4, 8, 30), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    // ---------- 每月 ----------

    @Test
    fun `每月 31 号，平年 2 月顺延到 28 号`() {
        val spec = monthly(9, 0, 31)
        val now = at(SHANGHAI, 2026, 2, 1, 0, 0)

        assertEquals(at(SHANGHAI, 2026, 2, 28, 9, 0), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    @Test
    fun `每月 31 号，闰年 2 月顺延到 29 号`() {
        val spec = monthly(9, 0, 31)
        val now = at(SHANGHAI, 2028, 2, 1, 0, 0)

        assertEquals(at(SHANGHAI, 2028, 2, 29, 9, 0), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    @Test
    fun `每月 30 和 31 号，2 月里两个都收敛到 28 号但只触发一次`() {
        // 这是顺延语义最容易写错的一处：不去重就会在 28 号这一天连响两次。
        val spec = monthly(9, 0, 30, 31)
        val now = at(SHANGHAI, 2026, 2, 1, 0, 0)

        val first = NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI)
        val second = NextFireTimeCalculator.nextFireAt(spec, first!!, SHANGHAI)

        assertEquals(at(SHANGHAI, 2026, 2, 28, 9, 0), first)
        assertEquals("同一天不能响两次，下一次必须跳到 3 月", at(SHANGHAI, 2026, 3, 30, 9, 0), second)
    }

    @Test
    fun `每月 29 号，平年 2 月顺延到 28 号`() {
        val spec = monthly(9, 0, 29)
        val now = at(SHANGHAI, 2026, 2, 1, 0, 0)

        assertEquals(at(SHANGHAI, 2026, 2, 28, 9, 0), NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI))
    }

    @Test
    fun `每月日期全是无效值时返回 null`() {
        val now = at(SHANGHAI, 2026, 3, 1, 0, 0)

        assertNull(NextFireTimeCalculator.nextFireAt(monthly(9, 0, 0), now, SHANGHAI))
        assertNull(NextFireTimeCalculator.nextFireAt(monthly(9, 0, 32), now, SHANGHAI))
        assertNull(NextFireTimeCalculator.nextFireAt(monthly(9, 0), now, SHANGHAI))
    }

    @Test
    fun `每月同一个日期，连取两次分别是本月和下月`() {
        val spec = monthly(9, 0, 15)
        val now = at(SHANGHAI, 2026, 3, 1, 0, 0)

        val first = NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI)
        val second = NextFireTimeCalculator.nextFireAt(spec, first!!, SHANGHAI)

        assertEquals(at(SHANGHAI, 2026, 3, 15, 9, 0), first)
        assertEquals(at(SHANGHAI, 2026, 4, 15, 9, 0), second)
    }

    // ---------- 补跑检测依赖的性质 ----------

    @Test
    fun `无论周期类型，只要配置合法就必须算出一个有限的未来时刻`() {
        // 补跑那一步是拿"现在"去比 nextFireAt：算不出未来时刻的任务会被一路跳过。
        // 所以"合法配置一定算得出来"是补跑的前提，单独钉一条。
        val now = at(SHANGHAI, 2026, 3, 1, 12, 0)
        val specs = listOf(
            oneShot(now + MINUTE_MS),
            RecurrenceSpec(TaskKind.INTERVAL, intervalMinutes = 5, anchorEpochMs = now),
            daily(8, 30),
            weekly(8, 30, MON or SUN),
            monthly(9, 0, 1),
        )

        specs.forEach { spec ->
            val next = NextFireTimeCalculator.nextFireAt(spec, now, SHANGHAI)
            assertNotNull("$spec 应当算得出下一次", next)
            assertTrue("$spec 算出的必须是未来", next!! > now)
        }
    }

    // ---------- 助手 ----------

    private fun at(zone: ZoneId, y: Int, m: Int, d: Int, h: Int, min: Int): Long =
        LocalDate.of(y, m, d).atTime(h, min).atZone(zone).toInstant().toEpochMilli()

    /** 某个 epoch 在目标时区里相对 UTC 的偏移小时数（用来分辨夏令时重叠里取的是哪一个）。 */
    private fun nextDurationHours(epochMs: Long, zone: ZoneId): Long =
        java.time.Instant.ofEpochMilli(epochMs).atZone(zone).offset.totalSeconds / 3600L

    private fun oneShot(fireAt: Long) =
        RecurrenceSpec(TaskKind.ONE_SHOT, fireAtEpochMs = fireAt, anchorEpochMs = fireAt)

    private fun daily(hour: Int, minute: Int) =
        RecurrenceSpec(TaskKind.DAILY, hourOfDay = hour, minuteOfHour = minute, anchorEpochMs = 0)

    private fun weekly(hour: Int, minute: Int, mask: Int) =
        RecurrenceSpec(TaskKind.WEEKLY, hourOfDay = hour, minuteOfHour = minute, daysOfWeekMask = mask, anchorEpochMs = 0)

    private fun monthly(hour: Int, minute: Int, vararg days: Int) =
        RecurrenceSpec(TaskKind.MONTHLY, hourOfDay = hour, minuteOfHour = minute, daysOfMonth = days.toList(), anchorEpochMs = 0)

    private companion object {
        const val MINUTE_MS = 60_000L

        /** 周一 = bit0，对齐 `DayOfWeek.value - 1`。 */
        const val MON = 1 shl 0
        const val TUE = 1 shl 1
        const val WED = 1 shl 2
        const val THU = 1 shl 3
        const val FRI = 1 shl 4
        const val SAT = 1 shl 5
        const val SUN = 1 shl 6

        val SHANGHAI: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}
