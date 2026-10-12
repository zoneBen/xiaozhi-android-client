package me.xiaozhi.androidclient.scheduling

import android.util.Log
import me.xiaozhi.androidclient.data.ScheduledTaskRepository
import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import java.time.ZoneId

/**
 * 「App 重新起来时，那些错过的定时任务怎么办？」
 *
 * 两个调用点：**开机**（[me.xiaozhi.androidclient.BootReceiver]）与**冷启动**
 * （`MainViewModel.init`）。两个都要，不是因为保险：
 *
 * - 开机广播覆盖「真关机了」；
 * - `init` 覆盖「没重启但 App 被 force-stop / 被系统回收 / 被 Doze 冻住」——这些情况下
 *   闹钟可能压根没响，而开机广播一次也不会来。设备内存小，这条路径是常态而非例外。
 *
 * 两处调同一个函数、同一套幂等键，重复调用是安全的（见 [catchUpDecisionFor]）。
 *
 * ## 每次都以「现在」为锚重算，而不是补完就完
 *
 * 补跑之后的下一次发生点必须**从 `now` 之后重算**，不能沿用任务上那个已经过去的
 * `nextFireAt`。否则任务会顶着一个过去的时刻被重新挂进系统闹钟——`setExactAndAllowWhileIdle`
 * 收到过去时刻会立刻触发，然后立刻再触发，刷屏。
 */
object ScheduledTaskRecovery {

    private const val TAG = "XiaozhiTaskAlarm"

    /**
     * 补跑 + 全量重排。**必须在后台线程/协程里调**（里面是阻塞的库读写）。
     *
     * 幂等：重复调用不会重复补跑，也不会把已经排好的闹钟搞乱。
     */
    suspend fun run(repository: ScheduledTaskRepository, scheduler: TaskAlarmScheduler) {
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        val enabled = repository.allEnabled()
        // mapNotNull：算不出周期的任务返回 null，会**整个被排除在重排之外**。
        // 这是刻意的——rescheduleAll 会先 cancel 它拿到的每一条，把它排除掉就等于
        // 「别碰它」，而不是「拿一个我们来路不明的时刻重新挂一遍」。
        val refreshed = enabled.mapNotNull { recover(repository, it, now, zone) }
        scheduler.rescheduleAll(refreshed)

        Log.i(TAG, "定时任务恢复完成：${enabled.size} 条启用，重排 ${refreshed.size} 条")
    }

    /**
     * 处理单条任务，返回它**重算之后**的样子（供重排用）。
     *
     * 返回 null = 周期配置解析不了，调用方应当完全不碰它。
     */
    private suspend fun recover(
        repository: ScheduledTaskRepository,
        task: ScheduledTaskEntity,
        now: Long,
        zone: ZoneId,
    ): ScheduledTaskEntity? {
        val spec = task.recurrenceSpec()
        if (spec == null) {
            Log.w(TAG, "任务周期配置无法解析，恢复时跳过 id=${task.id} kind=${task.kind}")
            return null
        }

        recordMissedIfAny(repository, task, now)

        val next = NextFireTimeCalculator.nextFireAt(spec, now, zone)
        if (next == null) {
            // 一次性任务已经用掉：与到点触发时同一个处置——关掉，不删。
            repository.retire(task.id)
            return task.copy(enabled = false, nextFireAtEpochMs = null)
        }

        // setNextFire 不碰 lastFireAt：这不算一次触发，否则补跑判定会被自己搅乱。
        repository.setNextFire(task.id, next)
        return task.copy(nextFireAtEpochMs = next)
    }

    private suspend fun recordMissedIfAny(
        repository: ScheduledTaskRepository,
        task: ScheduledTaskEntity,
        now: Long,
    ) {
        val scheduledAt = task.nextFireAtEpochMs ?: return
        val alreadyRecorded = repository.hasExecutionAt(task.id, scheduledAt)
        when (val decision = catchUpDecisionFor(task, now, alreadyRecorded)) {
            is CatchUpDecision.CatchUp -> {
                repository.enqueue(
                    pendingExecutionOf(task, decision.scheduledAtEpochMs, now, isCatchUp = true),
                )
                Log.i(TAG, "补跑一条错过的定时任务 id=${task.id} 计划时刻=${decision.scheduledAtEpochMs}")
            }

            is CatchUpDecision.SkipMissed -> {
                repository.enqueue(skippedMissedExecutionOf(task, decision.scheduledAtEpochMs, now))
                Log.i(TAG, "错过一条定时任务且未勾选补跑 id=${task.id}")
            }

            CatchUpDecision.Nothing -> Unit
        }
    }
}
