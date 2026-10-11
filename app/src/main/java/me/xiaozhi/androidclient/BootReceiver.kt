package me.xiaozhi.androidclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action.orEmpty()
        if (action !in SUPPORTED_ACTIONS) return

        Log.i(TAG, "received: $action")
        val pendingResult = goAsync()
        Thread({
            try {
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
