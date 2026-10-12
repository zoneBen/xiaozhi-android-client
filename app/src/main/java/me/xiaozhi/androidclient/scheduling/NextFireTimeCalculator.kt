package me.xiaozhi.androidclient.scheduling

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.ZoneId

/**
 * 「下一次该在什么时候触发」。纯计算，没有一行 Android 代码，[afterEpochMs] 与 [zone] 全部显式传入。
 *
 * 这是新一套定时引擎（见 [RecurrenceSpec] 的文件头，与 [ReminderScheduler] 的分工）里
 * 最需要被钉死的一块：算错一次不是报个错，而是**任务在错的时间把用户叫醒**，
 * 或者更糟——算出一个永远不前进的时刻，闹钟每次都定在同一秒上。
 *
 * ## 三条贯穿全文件的原则
 *
 * 1. **严格晚于 [afterEpochMs]**。相等不算"下一次"——否则闹钟到点后重算会算出它自己，
 *    一秒之内反复触发。
 * 2. **闭式公式，不做累加**。INTERVAL 不用 `上次 + 步长`，而是拿锚点直接算第 k 个格子：
 *    累加会随每次触发漂移（每次都往上取整一点），跑一天就偏出一截。
 * 3. **算不出来就返回 null，绝不进死循环**。掩码是 0、月度日期全是无效值、区间为负，
 *    这些都直接 null；循环也都有上界（天最多 2、周最多 8、月最多 49）。
 *
 * 时区由调用方给：生产传 `ZoneId.systemDefault()`，测试传固定时区，结果才确定。
 */
object NextFireTimeCalculator {

    /** 严格晚于 [afterEpochMs] 的下一次触发；任务已耗尽或配置非法时返回 null。 */
    fun nextFireAt(spec: RecurrenceSpec, afterEpochMs: Long, zone: ZoneId): Long? =
        when (spec.kind) {
            // 一次性：还没到就把那个时刻还回去，到了就没有下一次（null 会让任务自己停用）。
            TaskKind.ONE_SHOT -> spec.fireAtEpochMs?.takeIf { it > afterEpochMs }
            TaskKind.INTERVAL -> nextInterval(spec, afterEpochMs)
            TaskKind.DAILY -> nextDaily(spec, afterEpochMs, zone)
            TaskKind.WEEKLY -> nextWeekly(spec, afterEpochMs, zone)
            TaskKind.MONTHLY -> nextMonthly(spec, afterEpochMs, zone)
        }

    /**
     * 每隔 N 分钟：拿锚点直接算**下一个格子的序号**。
     */
    private fun nextInterval(spec: RecurrenceSpec, afterEpochMs: Long): Long? {
        val step = spec.intervalMinutes.toLong() * 60_000L
        if (step <= 0L) return null
        // floorDiv 而不是 `/`：after 早于锚点（补跑、时钟被 NTP 往回拨）时，
        // 普通整除朝零取整会算出**已经过去**的那个格子。
        val index = Math.floorDiv(afterEpochMs - spec.anchorEpochMs, step) + 1
        return spec.anchorEpochMs + index * step
    }

    /**
     * 每天固定时刻。今天那一刻没过就今天，过了就明天，所以最多看两天。
     */
    private fun nextDaily(spec: RecurrenceSpec, afterEpochMs: Long, zone: ZoneId): Long? {
        val time = localTimeOrNull(spec) ?: return null
        val today = Instant.ofEpochMilli(afterEpochMs).atZone(zone).toLocalDate()
        return (0..1).firstNotNullOfOrNull { offset ->
            fireAtOrNull(today.plusDays(offset.toLong()), time, zone, afterEpochMs)
        }
    }

    /**
     * 每周固定几天。从今天起最多看 8 天（今天 + 未来 7 天），必然覆盖到下一个命中日。
     */
    private fun nextWeekly(spec: RecurrenceSpec, afterEpochMs: Long, zone: ZoneId): Long? {
        // 掩码是 0（或只有 0..6 之外的无效位）时先出局，否则下面那圈永远找不到命中日。
        if (spec.daysOfWeekMask and WEEKDAY_MASK_ALL == 0) return null
        val time = localTimeOrNull(spec) ?: return null
        val today = Instant.ofEpochMilli(afterEpochMs).atZone(zone).toLocalDate()
        return (0..7).firstNotNullOfOrNull { offset ->
            val date = today.plusDays(offset.toLong())
            val hit = spec.daysOfWeekMask and (1 shl (date.dayOfWeek.value - 1)) != 0
            if (hit) fireAtOrNull(date, time, zone, afterEpochMs) else null
        }
    }

    /**
     * 每月固定几天。**不满的那个月顺延到当月最后一天**（用户选的语义），
     * 而不是跳过整个月：设了「每月 31 号」的人在 2 月也一样该被提醒一次。
     *
     * 顺延带来的坑在这里必须去重：设了「每月 30、31 号」时，2 月里两个日期**都**收敛到
     * 28 号，不去重就会在 28 号这一天连响两次。
     *
     * 上界 49 个月：只要 [RecurrenceSpec.daysOfMonth] 里有一个有效值，
     * 第一个月或第二个月必定命中，4 年只是"绝不死循环"的兜底。
     */
    private fun nextMonthly(spec: RecurrenceSpec, afterEpochMs: Long, zone: ZoneId): Long? {
        val days = spec.daysOfMonth.filter { it in 1..31 }.distinct().sorted()
        if (days.isEmpty()) return null
        val time = localTimeOrNull(spec) ?: return null

        val today = Instant.ofEpochMilli(afterEpochMs).atZone(zone).toLocalDate()
        var year = today.year
        var month = today.monthValue
        repeat(49) {
            val lengthOfMonth = YearMonth.of(year, month).lengthOfMonth()
            val effectiveDays = days.map { minOf(it, lengthOfMonth) }.distinct().sorted()
            for (day in effectiveDays) {
                fireAtOrNull(LocalDate.of(year, month, day), time, zone, afterEpochMs)?.let { return it }
            }
            if (month == 12) { year += 1; month = 1 } else { month += 1 }
        }
        return null
    }

    /**
     * 某一天的那个本地时刻，晚于 [afterEpochMs] 才要。
     *
     * [LocalDate.atTime] + [java.time.ZonedDateTime.of] 是夏令时正确的原语，两种情况都自动处理：
     * **春季跳变的空洞**（本地 2:30 那分钟不存在）会把结果往后推到 3:30；
     * **秋季回拨的重叠**（本地 2:30 出现两次）取**较早**的那个 offset。
     */
    private fun fireAtOrNull(date: LocalDate, time: LocalTime, zone: ZoneId, afterEpochMs: Long): Long? =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli().takeIf { it > afterEpochMs }

    /** 时刻字段越界就当作配置非法（null），不要靠 `LocalTime.of` 抛异常来表达这件事。 */
    private fun localTimeOrNull(spec: RecurrenceSpec): LocalTime? =
        if (spec.hourOfDay in 0..23 && spec.minuteOfHour in 0..59) {
            LocalTime.of(spec.hourOfDay, spec.minuteOfHour)
        } else {
            null
        }

    /** 周一到周日七位全满。 */
    private const val WEEKDAY_MASK_ALL = 0b111_1111
}
