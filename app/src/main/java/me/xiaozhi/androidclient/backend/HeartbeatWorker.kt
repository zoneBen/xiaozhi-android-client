package me.xiaozhi.androidclient.backend

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * 心跳的兜底路径。
 *
 * ## 为什么必须有它
 *
 * `MainViewModel` 里那个协程循环只在 App 活着的时候转。而这台设备（TV5701 / RK3568 面板）
 * 内存小、又是个长期挂机的 kiosk，系统会很积极地回收进程 —— 进程一死，心跳就断了，
 * 后台设备列表上那台机器就成了「离线」，可它其实一直开着、就在墙上挂着。
 *
 * WorkManager 的周期任务由系统托管、跟着进程一起被回收也没关系，到点会把进程拉起来跑一次。
 * 所以这不是「可选增强」，它是「设备到底在线没有」这个判断能不能成立的前提。
 *
 * 15 分钟是 WorkManager 对周期任务的系统下限，正好和默认心跳间隔对齐。
 *
 * ## 失败不重试
 *
 * 全部返回 `Result.success()`，包括上报失败。周期任务本身就是重试：下一次 15 分钟后又跑。
 * 用 `Result.retry()` 反而会在服务器挂着的时候叠出指数退避的额外请求，
 * 而这条链路的意义只是「留个脚印」，不值得为它调度重试。
 */
class HeartbeatWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (!BackendConfig.isConfigured) {
            Log.i(TAG, "跳过：后端未配置 appSecret")
            return Result.success()
        }

        val reporter = BackendFactory.heartbeatReporter(applicationContext)
        when (val outcome = reporter.report(HeartbeatReporter.SOURCE_WORKER)) {
            is HeartbeatOutcome.Reported ->
                Log.i(TAG, "心跳成功，服务端下发间隔 ${outcome.result.intervalSec}s")

            HeartbeatOutcome.Disabled ->
                Log.w(TAG, "设备已被服务端停用，等待重新启用")

            HeartbeatOutcome.Skipped ->
                Log.i(TAG, "跳过：距上次上报不足半个间隔")

            is HeartbeatOutcome.Failed ->
                // 服务器挂了是常态（自托管），这里刻意只用 Log.i 而不是 Log.e：
                // 它不该在日志里表现成「异常」，否则真正的错误会被这类噪音淹没。
                Log.i(TAG, "心跳失败（不影响任何本地功能）：${outcome.error.message}")
        }
        return Result.success()
    }

    companion object {
        private const val TAG = "XiaozhiHeartbeat"
        private const val UNIQUE_WORK_NAME = "xiaozhi-backend-heartbeat"
        private const val REQUEST_TAG = "xiaozhi-backend"
        const val INTERVAL_MINUTES = 15L

        /**
         * 挂上周期任务。用 [ExistingPeriodicWorkPolicy.KEEP]：每次冷启动都重新排期的话，
         * 一个频繁开关机的设备会永远等不到下一次执行 —— 周期是从入队时刻开始算的。
         */
        fun enqueue(context: Context) {
            val request = PeriodicWorkRequestBuilder<HeartbeatWorker>(INTERVAL_MINUTES, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .addTag(REQUEST_TAG)
                .build()

            runCatching {
                WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                    UNIQUE_WORK_NAME,
                    ExistingPeriodicWorkPolicy.KEEP,
                    request,
                )
            }.onFailure { error ->
                // 入队失败不影响 App 正常使用 —— 心跳本来就是旁路，主循环还在跑。
                Log.w(TAG, "心跳兜底任务入队失败：${error.message}")
            }
        }
    }
}
