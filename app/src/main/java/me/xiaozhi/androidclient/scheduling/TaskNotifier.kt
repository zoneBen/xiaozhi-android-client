package me.xiaozhi.androidclient.scheduling

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import me.xiaozhi.androidclient.MainActivity
import me.xiaozhi.androidclient.R
import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity

/**
 * 定时任务执行后的通知。
 *
 * 它不只是"告知"，还是**兜底入口**：Android 10+ 对后台启动 Activity 有硬限制，
 * 闹钟到点时若 startActivity 被拦下，用户至少能从这条通知点进去——而那条 PENDING
 * 记录本来就不会丢，进去之后照常被消费。
 *
 * 渠道用 `IMPORTANCE_LOW`：这类通知是"我做完了"，不是要打断用户的事。
 * 用 LOW 也顺带避开横幅和声音——设备在夜里执行任务时不该被自己的通知吵到。
 */
class TaskNotifier(private val context: Context) {

    /**
     * 通知用户"这条任务到点执行了"。
     *
     * 通知被关掉（用户拒绝 POST_NOTIFICATIONS 或手动关了本应用通知）时**静默跳过**，
     * 不抛也不回退到别的提示方式：那条 PENDING 记录还在，界面起来后照常执行，
     * 功能不依赖这条通知。
     */
    fun notifyExecuted(task: ScheduledTaskEntity) {
        if (!areNotificationsEnabled()) return
        ensureChannel()

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_timer)
            .setContentTitle("定时任务已执行")
            .setContentText(task.title)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setAutoCancel(true)
            .setContentIntent(openAppIntent(task.id))
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(notificationIdFor(task.id), notification)
        }
    }

    /**
     * 通知被关掉时 `areNotificationsEnabled()` 已经是 false，**不要**在这里申请权限——
     * 申请的时机在「定时任务」页首次保存任务时（那时用户才关心它，见计划第五节）。
     */
    private fun areNotificationsEnabled(): Boolean =
        runCatching { NotificationManagerCompat.from(context).areNotificationsEnabled() }.getOrDefault(false)

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "定时任务", NotificationManager.IMPORTANCE_LOW).apply {
                description = "定时任务执行后的提醒"
            },
        )
    }

    private fun openAppIntent(taskId: Long): PendingIntent {
        // 和闹钟同一个套路：requestCode + 唯一的 data，两者都设上，免得两条任务的通知
        // 判成同一个 PendingIntent 而互相串台（PendingIntent 的去重看 Intent 的 filterEquals）。
        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            setData(Uri.parse("$NOTIFICATION_URI_PREFIX/$taskId"))
        }
        return PendingIntent.getActivity(
            context,
            requestCodeFor(taskId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun notificationIdFor(taskId: Long): Int = NOTIFICATION_ID_BASE + requestCodeFor(taskId)

    /** 同一套 requestCode 取法：避开 0，并对 1000 取模免得两个任务撞在一起。 */
    private fun requestCodeFor(taskId: Long): Int = (taskId % 1000L).toInt()

    companion object {
        const val CHANNEL_ID = "scheduled_tasks"
        private const val NOTIFICATION_ID_BASE = 4200
        private const val NOTIFICATION_URI_PREFIX = "xiaozhi://task-done"
    }
}
