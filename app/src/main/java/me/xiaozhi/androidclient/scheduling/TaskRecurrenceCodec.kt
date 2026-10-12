package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity

/**
 * 库里的那一行 ↔ [RecurrenceSpec]。
 *
 * 单独一个文件、并且**放纯函数**，是因为"哪天算有效值"这件事直接决定闹钟会不会响：
 * 掩码是 0、月度日期写了 0 或 32，都会让 [NextFireTimeCalculator] 永远算不出下一次，
 * 表现是"任务建好了但一次都不响"——最难查的那类 bug。所以输入侧尽量早地规整掉，
 * 而不是让它在计算里静默变成 null。
 */

/** 认不出 [ScheduledTaskEntity.kind] 时返回 null：任务会被跳过，而不是猜一个周期出来。 */
fun ScheduledTaskEntity.recurrenceSpec(): RecurrenceSpec? {
    val kind = parseTaskKind(kind) ?: return null
    return RecurrenceSpec(
        kind = kind,
        intervalMinutes = intervalMinutes ?: 0,
        hourOfDay = hourOfDay ?: 0,
        minuteOfHour = minuteOfHour ?: 0,
        daysOfWeekMask = daysOfWeekMask ?: 0,
        daysOfMonth = parseDaysOfMonth(daysOfMonthCsv),
        fireAtEpochMs = fireAtEpochMs,
        anchorEpochMs = anchorEpochMs,
    )
}

fun parseTaskKind(name: String?): TaskKind? = TaskKind.entries.firstOrNull { it.name == name }

/**
 * `"1,15,31"` → `[1, 15, 31]`。
 *
 * **容忍脏输入，但不容忍它们混进来**：非数字、0、32、超出 1..31 的、重复的，
 * 一律丢掉并去重排序。理由同上——这些值到了计算里只会变成 null，不如在这里就清掉；
 * 而"31 号和 31 号"这种输入会让人以为一天响两次。
 */
fun parseDaysOfMonth(csv: String?): List<Int> =
    csv.orEmpty()
        .split(',')
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it in 1..31 }
        .distinct()
        .sorted()

/** [parseDaysOfMonth] 的逆：写回库里时用。空列表写空串，读回来还是空。 */
fun formatDaysOfMonth(days: List<Int>): String =
    days.filter { it in 1..31 }.distinct().sorted().joinToString(",")

/**
 * 一周七天的掩码 ↔ 勾选状态。**周一 = bit0**，对齐 `DayOfWeek.value - 1`。
 */
fun weekMaskOf(days: Set<java.time.DayOfWeek>): Int =
    days.fold(0) { mask, day -> mask or (1 shl (day.value - 1)) }

fun daysOfWeekFrom(mask: Int): List<java.time.DayOfWeek> =
    java.time.DayOfWeek.entries.filter { mask and (1 shl (it.value - 1)) != 0 }
