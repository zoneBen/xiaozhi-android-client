package me.xiaozhi.androidclient.scheduling

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.runBlocking
import me.xiaozhi.androidclient.MainActivity
import me.xiaozhi.androidclient.data.ScheduledTaskRepository
import java.time.ZoneId

/**
 * 闹钟到点这一下。
 *
 * ## 这一层只做一件事：写库。**绝不碰 MainViewModel。**
 *
 * 到点时 App 进程大概率不在，而且 Android 10+ 对后台启动 Activity 有硬限制。
 * 所以这里把"该执行了"落成一行 `PENDING` 的执行记录就退出，界面起来之后自己去消费它。
 * 这条边界带来三个好处，缺一个都会出问题：
 *
 * 1. 进程被杀时闹钟照样响，而磁盘是唯一可靠的交接面；
 * 2. 就算下面的 `startActivity` 被系统拦下，那行记录也不会丢，下次前台照常执行；
 * 3. 界面根本没起来（用户从没打开过 App）时，记录还在，不是静默丢失。
 *
 * 顺序是刻意的：**先落 PENDING 行，再更新下一次发生点**。反过来的话，中间失败
 * （进程被杀、库写不进去）就变成"闹钟响了、记录没有、下一次也推走了"——那次触发凭空消失。
 *
 * 为什么用 `runBlocking`：本文件的宿主是一个 receiver 的后台线程，没有现成的 CoroutineScope
 * 可挂（[goAsync] 给的是 BroadcastReceiver.PendingResult，不是 scope）。这里每一段都是
 * 一次性的库读写，阻塞这个线程是正确的做法，也不会卡到主线程。
 */
class TaskAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TASK_ALARM) return
        val taskId = intent.getLongExtra(EXTRA_TASK_ID, 0L)
        if (taskId <= 0L) return

        Log.i(TAG, "定时任务到点：id=$taskId")
        val pendingResult = goAsync()
        // 照 BootReceiver 的写法：goAsync 之后必须换到别的线程，onReceive 的 10 秒预算不够用。
        Thread({
            try {
                handle(context.applicationContext, taskId)
            } catch (error: Exception) {
                Log.e(TAG, "定时任务触发处理失败 id=$taskId", error)
            } finally {
                pendingResult.finish()
            }
        }, "xiaozhi-task-alarm").start()
    }

    private fun handle(context: Context, taskId: Long) {
        val repository = ScheduledTaskRepository(context)
        val now = System.currentTimeMillis()
        val task = runBlocking { repository.byId(taskId) }
        if (task == null) {
            Log.w(TAG, "任务已被删除，忽略触发 id=$taskId")
            return
        }
        if (!task.enabled) {
            Log.i(TAG, "任务已停用，忽略触发 id=$taskId")
            return
        }

        // 计划发生的时刻就是它身上记着的那个；万一丢了（老数据），退化成"现在"，
        // 反正执行记录里那一列只是给人看"晚了多久"。
        val scheduledAt = task.nextFireAtEpochMs ?: now
        runBlocking {
            repository.enqueue(
                pendingExecutionOf(task, scheduledAt, now, isCatchUp = false),
            )
        }

        val spec = task.recurrenceSpec()
        if (spec == null) {
            // 只可能是"库里的 kind 来自更新的版本"这一种情形。**不重排**（算不出来），
            // 但上面那行 PENDING 已经落了：界面消费它时会发现配置看不懂并记一条失败，
            // 用户能在执行记录里看见，而不是"这条任务从此再没响过，也没人知道为什么"。
            Log.e(TAG, "任务周期配置无法解析，不再重排 id=$taskId kind=${task.kind}")
            launchUi(context)
            TaskNotifier(context).notifyExecuted(task)
            return
        }

        val next = NextFireTimeCalculator.nextFireAt(spec, now, ZoneId.systemDefault())
        if (next == null) {
            // 一次性任务已经用掉（或配置再也算不出下一次）：关掉自己，但**不删**——
            // 用户还要在列表里看到它跑过了。
            runBlocking { repository.retire(task.id) }
        } else {
            runBlocking { repository.recordFired(task.id, next, now) }
            TaskAlarmScheduler(context).scheduleAt(task.id, next)
        }

        launchUi(context)
        TaskNotifier(context).notifyExecuted(task)
    }

    /**
     * 尽力把界面拉起来，**失败就算了**。
     *
     * 走 `MainActivity` 而不是 `SplashActivity`：开机动画是冷启动的仪式感，
     * 每次任务触发都走一遍不合适，而且 SplashActivity 钉在 `:splash` 进程，
     * 会多一次进程往返。被系统拦下也没关系——PENDING 那行还在，
     * 用户从通知点进来、或下次自己打开 App 时照常执行。
     */
    private fun launchUi(context: Context) {
        try {
            context.startActivity(
                Intent(context, MainActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                },
            )
        } catch (error: Exception) {
            // Android 10+ 的后台启动限制会在这里抛，属预期之内：兜底靠通知与 PENDING 记录。
            Log.w(TAG, "拉起界面被拦下，改由通知兜底：${error.message.orEmpty()}")
        }
    }

    companion object {
        const val ACTION_TASK_ALARM = "me.xiaozhi.androidclient.action.TASK_ALARM"
        const val EXTRA_TASK_ID = "task_id"
        private const val TAG = "XiaozhiTaskAlarm"
    }
}
