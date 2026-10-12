package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.TaskExecutionEntity
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 定时任务页面上所有给人看的文字。纯函数，时区显式传入。
 *
 * 为什么值得单独一个文件：这些句子是这一屏**唯一**让用户判断"我这条任务到底会不会按我想的响"
 * 的东西。写成 `"每 30 分钟"` 还是 `"每 1 小时"` 不是审美问题——设了「每 60 分钟」的人
 * 看到"每 60 分钟"会怀疑是不是没认出来，看到"每 1 小时"才敢确认。
 */

/** `「每 30 分钟」「每天 08:30」「每周一三五 08:30」「每月 1、15 号 09:00」「2026-03-01 08:30 一次」` */
fun recurrenceSummary(spec: RecurrenceSpec, zone: ZoneId): String = when (spec.kind) {
    TaskKind.INTERVAL -> intervalSummary(spec.intervalMinutes)
    TaskKind.DAILY -> "每天 ${timeText(spec)}"
    TaskKind.WEEKLY -> weeklySummary(spec)
    TaskKind.MONTHLY -> monthlySummary(spec)
    TaskKind.ONE_SHOT -> spec.fireAtEpochMs?.let { "${dateTimeText(it, zone)} 一次" } ?: "没有设时间"
}

/** 间隔：能整除 60 就说小时。`60 分钟`和`1 小时`是一回事，但后者才是人心里那个说法。 */
private fun intervalSummary(minutes: Int): String = when {
    minutes <= 0 -> "间隔无效"
    minutes % ONE_HOUR_MINUTES == 0 -> "每 ${minutes / ONE_HOUR_MINUTES} 小时"
    else -> "每 $minutes 分钟"
}

private fun weeklySummary(spec: RecurrenceSpec): String {
    val days = daysOfWeekFrom(spec.daysOfWeekMask)
    if (days.isEmpty()) return "没有选星期"
    // 单字拼起来才是"每周一三五"；用「周一」那种全称会拼成"周一三周五"。
    val label = if (days.size == DayOfWeek.entries.size) {
        "每天"
    } else {
        "每周" + days.joinToString("") { it.shortLabel }
    }
    return "$label ${timeText(spec)}"
}

private fun monthlySummary(spec: RecurrenceSpec): String {
    val days = spec.daysOfMonth.filter { it in 1..31 }.distinct().sorted()
    if (days.isEmpty()) return "没有选日期"
    val base = "每月 ${days.joinToString("、")} 号 ${timeText(spec)}"

    // 29/30/31 这些号在有的月份不存在，会自动顺延到当月最后一天。这不是显而易见的，
    // 所以直接在界面上说明白——否则用户会以为"31 号"的任务 2 月就被跳过了。
    return if (days.any { it > 28 }) "$base（该月没有这天就顺延到月底）" else base
}

private fun timeText(spec: RecurrenceSpec): String =
    "%02d:%02d".format(spec.hourOfDay, spec.minuteOfHour)

private fun dateTimeText(epochMs: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(ONE_SHOT_FORMAT)

/**
 * 「下次什么时候」。**近期用相对时间，远用绝对时间**：用户最常问的是"它还会不会响、
 * 多久之后响"，而"3 分钟后"比"03-01 08:33"好读；超过今天才需要具体到哪天。
 */
fun nextFireSummary(nextFireAtEpochMs: Long?, nowEpochMs: Long, zone: ZoneId): String {
    if (nextFireAtEpochMs == null) return "没有下一次"
    val remaining = nextFireAtEpochMs - nowEpochMs
    if (remaining <= 0) return "已经到点了"
    return when {
        remaining < MINUTE_MS -> "不到 1 分钟后"
        remaining < HOUR_MS -> "${remaining / MINUTE_MS} 分钟后"
        else -> {
            val target = Instant.ofEpochMilli(nextFireAtEpochMs).atZone(zone).toLocalDate()
            val today = Instant.ofEpochMilli(nowEpochMs).atZone(zone).toLocalDate()
            val clock = Instant.ofEpochMilli(nextFireAtEpochMs).atZone(zone).toLocalTime()
                .format(CLOCK_FORMAT)
            when (target) {
                today -> "今天 $clock"
                today.plusDays(1) -> "明天 $clock"
                else -> "${target.monthValue} 月 ${target.dayOfMonth} 日 $clock"
            }
        }
    }
}

/**
 * 一条执行记录的一行说明。
 *
 * [TaskExecutionEntity.detail] 是中文原因（"连不上服务器""设备忙"），拼在结论后面：
 * 光看"发信失败"用户没法判断该改设置还是该等一会儿，原因才是他要的那半句。
 */
fun executionSummary(execution: TaskExecutionEntity): String {
    val status = TaskRunStatus.fromName(execution.status)
    val outcome = TaskOutcome.fromName(execution.outcome)
    val head = when {
        status == TaskRunStatus.PENDING -> "排队中"
        status == TaskRunStatus.RUNNING -> "正在执行"
        outcome == TaskOutcome.CATCH_UP -> TaskOutcome.CATCH_UP.label
        outcome == TaskOutcome.SENT && execution.isCatchUp -> TaskOutcome.CATCH_UP.label
        outcome != null -> outcome.label
        status == TaskRunStatus.DONE -> "已完成"
        status == TaskRunStatus.FAILED -> "失败"
        else -> "状态不明"
    }
    val detail = execution.detail.orEmpty().trim()
    return if (detail.isEmpty()) head else "$head（$detail）"
}

/** 「一」「二」……单字，用来拼「每周一三五」。 */
private val DayOfWeek.shortLabel: String
    get() = WEEKDAY_LABELS[value - 1]

private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

private val ONE_SHOT_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("HH:mm")

private const val ONE_HOUR_MINUTES = 60
private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 3_600_000L

/** 便于界面与测试复用的时刻解析（`"08:30"`）。解析不出来返回 null，不抛。 */
fun parseClockTime(text: String): LocalTime? =
    runCatching { LocalTime.parse(text.trim(), CLOCK_FORMAT) }.getOrNull()
