package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.data.db.TaskExecutionEntity

/**
 * 「这条排队中的执行记录，现在还该不该做？」
 *
 * 返回 `null` = 照做；返回中文原因 = 记一条 `SKIPPED_MISSED`，原因原样显示在执行记录里。
 *
 * ## 为什么值得单独一个纯函数
 *
 * 因为它拦的是**已经排进队列的活**，而这个判断写反的代价是「一次性任务永远不执行」——
 * 真机上已经踩到过：用户建了一条「拍张照片发到邮箱」的一次性任务，到点了，闹钟确实响了
 * （`AlarmManager` 日志里 `origWhen` 正好是那一刻），执行记录却写着「任务已被停用，
 * 这次不执行了」。照片没拍，邮件没发，而用户看到的是一句像是在怪自己关掉任务的提示。
 *
 * ## `enabled` 这一个布尔被两件事共用，才是根子
 *
 * | 谁写的 | 语义 | 排队里的活该怎么办 |
 * |---|---|---|
 * | 用户按开关（[me.xiaozhi.androidclient.data.ScheduledTaskDao.setEnabled]） | 「我改主意了」 | 丢掉 |
 * | 一次性任务用掉自己（[me.xiaozhi.androidclient.data.ScheduledTaskDao.retire]，由 [TaskAlarmReceiver] 调） | **触发的收尾** | 照做 |
 *
 * 第二行不是「改主意」，而是「这件事已经发生了」的记账。但 receiver 是先落 `PENDING` 行、
 * 再 `retire`，两个动作差几毫秒；而消费者是**每秒轮询一次**（见 [ScheduledTaskRunner] 的
 * 类注释：轮询而不是 collect，是被「退避到点时没有任何写入事件」逼出来的）。于是消费者
 * 看到的永远是已经 retired 的那一行——对一次性任务来说，「停用」这个词 100% 是误判。
 *
 * ## 拿什么把两者分开
 *
 * `retire` 只清 `enabled` 和 `nextFireAtEpochMs`，**`fireAtEpochMs` 留在行上不动**。
 * 所以「这次发生点正好等于它的 `fireAtEpochMs`」就是「这是我那次触发」的凭据。
 *
 * 用这个凭据而不是「是不是一次性任务」：用户把一个一次性任务改到别的时刻之后，凭据立刻
 * 对不上，那条旧的排队记录照旧按停用丢掉——这正是想要的，**旧记录不该再代表任何真实意图**。
 *
 * ## 剩下的那点不精确，是刻意留的
 *
 * 用户如果恰好在闹钟响后的那一秒内（消费者的轮询间隔）手动关掉这条一次性任务，这次仍会执行。
 * 权衡下来是划算的：那不到一秒的窗口里「跳过」和「执行」都说得通，而反过来的保守做法
 * （一律跳过）会让**所有**一次性任务失效。宁可偶尔多做事，也不要一个必然失效的功能。
 *
 * 周期任务不适用这一条：它们没有任何仪式性的自我停用，被关掉只可能是用户关的。
 */
fun queuedExecutionSkipReason(
    task: ScheduledTaskEntity,
    execution: TaskExecutionEntity,
): String? {
    if (task.enabled) return null

    val retiredByThisFire = task.kind == TaskKind.ONE_SHOT.name &&
        task.fireAtEpochMs != null &&
        task.fireAtEpochMs == execution.scheduledAtEpochMs
    if (retiredByThisFire) return null

    return "任务已被停用，这次不执行了"
}
