package me.xiaozhi.androidclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.runBlocking
import me.xiaozhi.androidclient.data.ScheduledTaskRepository
import me.xiaozhi.androidclient.scheduling.ScheduledTaskRecovery
import me.xiaozhi.androidclient.scheduling.TaskAlarmScheduler

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action !in SUPPORTED_ACTIONS) return

        Log.i(TAG, "received: $action")
        val pendingResult = goAsync()
        Thread({
            try {
                // 先补跑 + 重排定时任务，再睡那 3 秒。
                // 顺序有意义：重启会把系统里所有闹钟清空，越早重挂越好；
                // 而下面的延迟是为了等系统稳定再拉界面，和这件事无关，不该一起等。
                recoverScheduledTasks(context)

                SystemClock.sleep(BOOT_START_DELAY_MS)
                // 走 SplashActivity 而不是直接拉 MainActivity：开机时主进程要现场校验
                // 字节码，冷启动有 8~9 秒，头屏得顶在上面。详见 SplashActivity.kt。
                val launchIntent = Intent(context, SplashActivity::class.java).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    )
                }
                context.startActivity(launchIntent)
                Log.i(TAG, "app auto-start requested")
            } catch (error: Exception) {
                Log.e(TAG, "auto-start failed", error)
            } finally {
                pendingResult.finish()
            }
        }, "xiaozhi-boot-start").start()
    }

    /**
     * 重启之后补跑错过的定时任务，并把所有闹钟重新挂进系统。
     *
     * 失败**不往上抛**：开机自动起界面是这条广播的主职责，定时任务恢复是搭车的。
     * 库读不出来（比如首次装机还没有数据库文件）就跳过，下一次冷启动的
     * `ScheduledTaskRecovery` 还会再跑一遍——它本来就是幂等的。
     */
    private fun recoverScheduledTasks(context: Context) {
        try {
            runBlocking {
                ScheduledTaskRecovery.run(
                    ScheduledTaskRepository(context),
                    TaskAlarmScheduler(context),
                )
            }
        } catch (error: Exception) {
            Log.e(TAG, "定时任务恢复失败", error)
        }
    }

    companion object {
        private const val TAG = "XiaozhiBoot"
        private const val BOOT_START_DELAY_MS = 3_000L
        private const val ACTION_ANDROID_QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"
        private const val ACTION_HTC_QUICKBOOT = "com.htc.intent.action.QUICKBOOT_POWERON"

        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_ANDROID_QUICKBOOT,
            ACTION_HTC_QUICKBOOT,
        )
    }
}
