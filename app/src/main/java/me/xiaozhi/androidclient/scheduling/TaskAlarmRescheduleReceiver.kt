package me.xiaozhi.androidclient.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import me.xiaozhi.androidclient.data.ScheduledTaskRepository
import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import java.time.ZoneId

/**
 * 「世界的时钟变了」这一下：重算所有任务的下一次发生点，再重新挂闹钟。
 *
 * ## 为什么这个 receiver 是必需的，不是锦上添花
 *
 * 闹钟里存的是**绝对 epoch 毫秒**。而「每天 8:30」是一个**墙上时间**的约定。
 * 两者平时等价，一旦时区变了就不再等价：从北京飞到东京，那条任务的 epoch 没变，
 * 但它在当地已经变成 9:30 了——不重算就会**永远错一整个时差**，而且用户看不出来，
 * 只会觉得"这破玩意儿时间不对"。
 *
 * `TIME_SET` 同理：用户手动把系统时间调准（或从 1970 调回来），残留的闹钟时刻就失去了意义。
 * `MY_PACKAGE_REPLACED` 则是补一刀——**应用被覆盖安装时系统会清掉本应用的所有闹钟**，
 * 不在这里重排，用户升级一次 App 就会发现自己所有定时任务都哑了。
 *
 * ## 这里只重排，不补跑
 *
 * 漏掉的触发点由 [ScheduledTaskRecovery] 统一负责（"只补一次、不逐次回放"那套幂等逻辑）。
 * 放在两个地方各写一遍，迟早会分叉成"开机补一次、改时区又补一次"的双跑。
 * 所以这里对过去的发生点**直接跳过**（`nextFireAt` 从 `now` 之后重算），
 * 语义干净：改时钟之后，下一次从此刻往后算。
 */
class TaskAlarmRescheduleReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action !in HANDLED_ACTIONS) return

        Log.i(TAG, "系统时钟/时区变化，重排全部定时任务：$action")
        val pendingResult = goAsync()
        // 和 BootReceiver 同一个套路：goAsync 之后必须换线程，onReceive 的 10 秒预算不够用。
        Thread({
            try {
                rescheduleAll(context.applicationContext)
            } catch (error: Exception) {
                Log.e(TAG, "重排定时任务失败", error)
            } finally {
                pendingResult.finish()
            }
        }, "xiaozhi-task-reschedule").start()
    }

    private fun rescheduleAll(context: Context) {
        val repository = ScheduledTaskRepository(context)
        val scheduler = TaskAlarmScheduler(context)
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()

        val enabled = runBlocking { repository.allEnabled() }
        // 重排要用**算过之后**的任务：allEnabled() 拿到的还是旧的 nextFireAt，
        // 直接把它喂给 rescheduleAll 就等于白算一场（拿旧时刻重新挂一遍）。
        val recomputed = enabled.map { task -> recompute(repository, task, now, zone) }
        scheduler.rescheduleAll(recomputed)
    }

    private fun recompute(
        repository: ScheduledTaskRepository,
        task: ScheduledTaskEntity,
        now: Long,
        zone: ZoneId,
    ): ScheduledTaskEntity {
        val spec = task.recurrenceSpec()
        if (spec == null) {
            // kind 来自更新的版本，算不出来。**原样留着**，别动它的 nextFireAt——
            // 置空等于替用户把任务悄悄废掉，而我们并不知道它本来该什么时候响。
            Log.w(TAG, "任务周期配置无法解析，重排时跳过 id=${task.id} kind=${task.kind}")
            return task
        }

        val next = NextFireTimeCalculator.nextFireAt(spec, now, zone)
        if (next == null) {
            // 一次性任务早就用掉了（或配置再也算不出下一次）：与到点触发时一致，关掉不删。
            runBlocking { repository.retire(task.id) }
            return task.copy(enabled = false, nextFireAtEpochMs = null)
        }

        // 走 setNextFire 而不是 recordFired：这不是一次触发，不能碰 lastFireAt，
        // 否则补跑的判定条件（lastFireAt < nextFireAt）会被这次重排搅乱。
        runBlocking { repository.setNextFire(task.id, next) }
        return task.copy(nextFireAtEpochMs = next)
    }

    companion object {
        private const val TAG = "XiaozhiTaskAlarm"

        private val HANDLED_ACTIONS = setOf(
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
        )
    }
}
