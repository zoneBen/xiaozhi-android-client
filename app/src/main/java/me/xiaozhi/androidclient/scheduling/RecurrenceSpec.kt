package me.xiaozhi.androidclient.scheduling

/**
 * 用户手建的定时任务，五种周期。
 *
 * ## 为什么这里有两套定时引擎（后来的人一定会问）
 *
 * [ReminderScheduler] 管的是**会话内、生命周期短、随时可取消**的临时提醒与摄像头监督
 * （`self.timer.*` / `self.supervision.*`）——它的可靠性契约是"进程活着就会响"，
 * 且监督带一整套相位状态机、与对话深度耦合。
 *
 * 本文件这一族（[RecurrenceSpec] / [NextFireTimeCalculator] / `TaskAlarm*`）管的是
 * **用户手建、要跨重启、要周期重复**的任务——可靠性契约是"系统闹钟"，进程被杀也照响。
 *
 * 两者的中断语义共用 [ReminderDeliveryPolicy]，其余各管各的，**不要试图合并**。
 */
enum class TaskKind { INTERVAL, DAILY, WEEKLY, MONTHLY, ONE_SHOT }

/**
 * 一条任务的周期描述。**纯值类型，不 import 任何 Android 类**——
 * 这样 [NextFireTimeCalculator] 才能用固定时区在 JVM 上把边界（闰月、夏令时）逐个钉死。
 *
 * 字段按 [kind] 分派，用不到的那些保持默认值即可（存库那一侧是同构的：一行的可选列）。
 *
 * - [intervalMinutes]：INTERVAL 用。按 [anchorEpochMs] 的网格闭式计算，**不做累加**。
 * - [hourOfDay] / [minuteOfHour]：DAILY / WEEKLY / MONTHLY 用，本地时间。
 * - [daysOfWeekMask]：WEEKLY 用。位掩码，**周一 = bit0**（对齐 `DayOfWeek.value - 1`）。
 * - [daysOfMonth]：MONTHLY 用，1~31；超出范围的项会被忽略，全无效则算不出下次触发。
 * - [fireAtEpochMs]：ONE_SHOT 用。
 * - [anchorEpochMs]：INTERVAL 的网格锚点（= 任务创建时刻），其余 kind 用不到。
 */
data class RecurrenceSpec(
    val kind: TaskKind,
    val intervalMinutes: Int = 0,
    val hourOfDay: Int = 0,
    val minuteOfHour: Int = 0,
    val daysOfWeekMask: Int = 0,
    val daysOfMonth: List<Int> = emptyList(),
    val fireAtEpochMs: Long? = null,
    val anchorEpochMs: Long,
)
