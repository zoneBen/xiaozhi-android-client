package me.xiaozhi.androidclient.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 定时任务的一次执行记录。
 *
 * 这张表**同时是执行队列**：`status = 'PENDING'` 的那些行就是待办。
 *
 * 为什么把队列放在磁盘上而不是把任务内容塞进 Intent：闹钟到点时 App 进程大概率不在，
 * 而 Android 10+ 对后台启动 Activity 有硬限制。让 receiver **只写库、绝不碰 ViewModel**，
 * 界面起来之后自己去消费 `PENDING` 行，是这里唯一可靠的交接方式——
 * 就算界面根本没被拉起来，那行记录也不会丢，下次前台时照常被消费。
 *
 * [taskTitle] / [action] 是**反规范化**的副本：任务被删掉之后，历史记录还得看得懂
 * 「当时是谁、干了什么」，靠 join 一张已删除的表做不到。
 */
@Entity(
    tableName = "task_executions",
    indices = [
        Index(value = ["taskId", "createdAtEpochMs"]),
        Index(value = ["status", "nextAttemptAtEpochMs"]),
    ],
)
data class TaskExecutionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val taskId: Long,
    val taskTitle: String,
    val action: String,
    /** 这一次「本该」发生的时刻（对齐到计划，不是实际触发时刻）。 */
    val scheduledAtEpochMs: Long,
    /** receiver 落这一行的时刻。用来量「晚了多久」。 */
    val firedAtEpochMs: Long?,
    /** `PENDING` / `RUNNING` / `DONE` / `FAILED`。 */
    val status: String,
    /** `SENT` / `CATCH_UP` / `SKIPPED_MISSED` / `SKIPPED_BUSY` / `FAILED_EMAIL` / `FAILED_CAPTURE`。 */
    val outcome: String?,
    /**
     * 是不是补跑。**单独一个布尔，不塞进 [outcome] 枚举**——
     * 这样「补执行且成功」既能按补跑筛、也能按成功筛，不用为每种 status 再乘一遍组合。
     */
    val isCatchUp: Boolean,
    /** 有界重试计数，见 `ScheduledTaskRunner`。 */
    val attempts: Int,
    /** 退避到什么时候再试。`status = PENDING` 且此列非 null 时表示「还没到重试时刻」。 */
    val nextAttemptAtEpochMs: Long?,
    /** 给人看的中文原因，直接显示在执行记录里。 */
    val detail: String?,
    val createdAtEpochMs: Long,
)
