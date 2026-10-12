package me.xiaozhi.androidclient.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条用户自建的**持久**定时任务。
 *
 * 五种周期共用一行表达，哪几个字段有意义由 [kind] 决定，其余留 null：
 *
 * | kind | 有意义的字段 |
 * |---|---|
 * | `INTERVAL` | [intervalMinutes]、[anchorEpochMs] |
 * | `DAILY` | [hourOfDay]、[minuteOfHour] |
 * | `WEEKLY` | [hourOfDay]、[minuteOfHour]、[daysOfWeekMask] |
 * | `MONTHLY` | [hourOfDay]、[minuteOfHour]、[daysOfMonthCsv] |
 * | `ONE_SHOT` | [fireAtEpochMs] |
 *
 * 这张表**没有 `roleId`**：定时任务是**设备级**的，到点注入**当前活跃角色**。
 * 自动切角色会打断用户正在进行的对话，代价远大于收益；将来真需要再加一个筛选字段。
 *
 * 也**没有「是否朗读」字段**：定时对话一律走完整对话、助手朗读，这是定下来的行为，不是配置项。
 *
 * 与 `scheduling/ReminderScheduler` 那套（会话内临时提醒 / 摄像头监督）是**两套并存的引擎**，
 * 详见 `scheduling/NextFireTimeCalculator.kt` 的文件头说明。
 */
@Entity(
    tableName = "scheduled_tasks",
    indices = [Index(value = ["nextFireAtEpochMs"])],
)
data class ScheduledTaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    /** 列表里显示的名字，也是执行通知的标题。 */
    val title: String,
    val kind: String,
    /** INTERVAL：间隔分钟数，必须 > 0。 */
    val intervalMinutes: Int?,
    val hourOfDay: Int?,
    val minuteOfHour: Int?,
    /** WEEKLY：位掩码，`1 shl (DayOfWeek.value - 1)`，**周一 = bit0**。 */
    val daysOfWeekMask: Int?,
    /** MONTHLY：形如 `"1,15,31"`。31 号在平月顺延到当月最后一天，详见 `NextFireTimeCalculator`。 */
    val daysOfMonthCsv: String?,
    /** ONE_SHOT：一次性触发时刻。 */
    val fireAtEpochMs: Long?,
    /**
     * INTERVAL 的网格锚点（= 创建时刻）。
     *
     * 间隔触发的下一次是**闭式**算出来的 `anchor + k * step`，不是「上次触发 + step」的累加——
     * 累加会随每次触发漂移，而且没法在进程重启后重建。锚点必须在库里，否则重启后网格就变了。
     */
    val anchorEpochMs: Long,
    /** `INJECT_TEXT` / `PHOTO_TO_EMAIL`。 */
    val action: String,
    /** INJECT_TEXT：要注入对话的原文。 */
    val text: String?,
    /** PHOTO_TO_EMAIL：收件人。为空则用邮件设置里的默认收件人。 */
    val emailTo: String?,
    val emailSubject: String?,
    /** 新建时由用户勾选：错过之后要不要补跑一次。 */
    val allowCatchUp: Boolean,
    val enabled: Boolean,
    val lastFireAtEpochMs: Long?,
    val nextFireAtEpochMs: Long?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)
