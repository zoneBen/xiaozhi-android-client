package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.data.db.TaskExecutionEntity

/**
 * 建执行记录的工厂。
 *
 * 单独抽出来只有一个理由：`TaskExecutionEntity` 有 14 个字段，而这张表的行有**两个**产生点
 * （闹钟到点的 receiver、开机的补跑）。两处各写一遍字面量，迟早会漏字段——最要命的是
 * `isCatchUp` 或 `taskTitle` 这种「漏了也不报错、只是显示错」的字段。
 * 反规范化字段（[TaskExecutionEntity.taskTitle] / [TaskExecutionEntity.action]）在这里
 * 从任务上抄一份，正是为了任务被删掉之后记录还看得懂。
 */
fun pendingExecutionOf(
    task: ScheduledTaskEntity,
    scheduledAtEpochMs: Long,
    nowEpochMs: Long,
    isCatchUp: Boolean,
): TaskExecutionEntity = TaskExecutionEntity(
    taskId = task.id,
    taskTitle = task.title,
    action = task.action,
    scheduledAtEpochMs = scheduledAtEpochMs,
    firedAtEpochMs = nowEpochMs,
    status = TaskRunStatus.PENDING.name,
    outcome = null,
    isCatchUp = isCatchUp,
    attempts = 0,
    nextAttemptAtEpochMs = null,
    detail = null,
    createdAtEpochMs = nowEpochMs,
)

/**
 * 「漏了，而且这条任务没勾允许补跑」的记录。
 *
 * 直接落成 `DONE` 而不是 `FAILED`：它没失败，是用户自己选了不补。
 * 也不落 `PENDING`——落了就会被 runner 捞起来执行，那就等于补跑了。
 * 存在的意义只有一个：让用户在记录里看得见「它本来该跑」，而不是静默消失。
 */
fun skippedMissedExecutionOf(
    task: ScheduledTaskEntity,
    scheduledAtEpochMs: Long,
    nowEpochMs: Long,
): TaskExecutionEntity = TaskExecutionEntity(
    taskId = task.id,
    taskTitle = task.title,
    action = task.action,
    scheduledAtEpochMs = scheduledAtEpochMs,
    firedAtEpochMs = null,
    status = TaskRunStatus.DONE.name,
    outcome = TaskOutcome.SKIPPED_MISSED.name,
    isCatchUp = false,
    attempts = 0,
    nextAttemptAtEpochMs = null,
    detail = "关机或休眠期间错过了这次，这条任务没勾「允许补跑」",
    createdAtEpochMs = nowEpochMs,
)
