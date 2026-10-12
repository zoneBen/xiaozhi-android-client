package me.xiaozhi.androidclient.scheduling

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity

/**
 * 把任务的下一次发生点交给系统闹钟。
 *
 * ## PendingIntent 的键：requestCode **加** 唯一的 data
 *
 * 只设 requestCode 是不够的：PendingIntent 的去重看的是 `Intent.filterEquals`，
 * 两个任务的 Intent 若在 action/component/data/… 上完全一致，就会被判成同一个，
 * 后一个把前一个覆盖掉——症状是"建了两条任务，只有一条会响"。
 * 所以 Intent 上挂一个 `xiaozhi://task/<id>` 的 data，彻底免疫；
 * requestCode 那位顺带当保险（将来 id 回绕也还是唯一的）。
 *
 * ## 精确闹钟在 Android 12+ 要授权
 *
 * `setExactAndAllowWhileIdle` 在没有 `SCHEDULE_EXACT_ALARM` 授权时会**抛
 * SecurityException**（不是静默降级），所以必须先 `canScheduleExactAlarms()` 问一句，
 * 没有就退到 `setAndAllowWhileIdle`——不精确、系统可能推迟，但至少会响。
 * 界面那一侧负责提示"去授权"。
 */
class TaskAlarmScheduler(private val context: Context) {

    private val alarmManager: AlarmManager? =
        context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager

    /** 某个时刻有没有授权精确闹钟（API 31 以下恒为 true）。界面拿它决定要不要提示去授权。 */
    fun canScheduleExactAlarms(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarmManager?.canScheduleExactAlarms() == true

    /** 到点触发（fireAtEpochMs 必须来自 [NextFireTimeCalculator]，这里是绝对时刻）。 */
    fun scheduleAt(taskId: Long, fireAtEpochMs: Long) {
        val manager = alarmManager ?: return
        val pendingIntent = alarmPendingIntent(taskId, PendingIntent.FLAG_UPDATE_CURRENT) ?: return
        if (canScheduleExactAlarms()) {
            manager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtEpochMs, pendingIntent)
        } else {
            // 降级：不精确。RTC_WAKEUP 保证会从 Doze 里醒来，但可能被推迟几分钟。
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtEpochMs, pendingIntent)
        }
    }

    fun cancel(taskId: Long) {
        // FLAG_NO_CREATE：不给"取消"这件事凭空造一个 PendingIntent 出来（那会白占一个槽位）。
        val pendingIntent = alarmPendingIntent(taskId, PendingIntent.FLAG_NO_CREATE) ?: return
        alarmManager?.cancel(pendingIntent)
        pendingIntent.cancel()
    }

    /**
     * 全量重排：先全清，再为每个「开着且有下一次」的任务重新 arm。
     *
     * 只在改时区 / 改系统时间 / 应用被更新时用。**不做增量**——那三种情况下所有闹钟
     * 要么已经失效要么时刻算错了，逐个判断"要不要重排"比全排一遍更容易错。
     */
    fun rescheduleAll(tasks: List<ScheduledTaskEntity>) {
        tasks.forEach { cancel(it.id) }
        tasks.filter { it.enabled && it.nextFireAtEpochMs != null }
            .forEach { scheduleAt(it.id, it.nextFireAtEpochMs!!) }
    }

    private fun alarmPendingIntent(taskId: Long, flag: Int): PendingIntent? {
        val intent = Intent(context, TaskAlarmReceiver::class.java).apply {
            action = TaskAlarmReceiver.ACTION_TASK_ALARM
            data = Uri.parse("$ALARM_URI_PREFIX/$taskId")
            putExtra(TaskAlarmReceiver.EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            (taskId % 1000L).toInt(),
            intent,
            flag or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        const val ALARM_URI_PREFIX = "xiaozhi://task"
    }
}
