package me.xiaozhi.androidclient.scheduling

/**
 * 一次执行记录走到哪一步了。
 *
 * `PENDING` 既是"还没轮到"也是**队列本身**：闹钟到点时 receiver 只写下一行 PENDING
 * 就退出，界面起来后自己去认领（见 `TaskExecutionDao.claimPending` 的原子认领）。
 */
enum class TaskRunStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    ;

    companion object {
        fun fromName(name: String?): TaskRunStatus? = entries.firstOrNull { it.name == name }
    }
}

/**
 * 一次执行**最后的结论**。与 [TaskRunStatus] 分开，因为同一句"失败"还有好几种原因，
 * 而用户在执行记录里要看的正是原因（"设备忙"和"连不上服务器"该做的事完全不同）。
 */
enum class TaskOutcome(val label: String) {
    /** 注入成功 / 邮件发出。 */
    SENT("已发送"),

    /** 补跑成功。单独一个值而不是"用 [isCatchUp] 标记的 SENT"——它要出现在记录列表里。 */
    CATCH_UP("补跑成功"),

    /** 错过了且用户没勾"允许补跑"。 */
    SKIPPED_MISSED("错过了，未补跑"),

    /** 重试到上限时设备仍忙。 */
    SKIPPED_BUSY("设备一直忙，没发出去"),

    /** 拍照/发信失败，原因在记录的中文 detail 里。 */
    FAILED_EMAIL("发信失败"),
    FAILED_CAPTURE("拍照失败"),
    ;

    companion object {
        fun fromName(name: String?): TaskOutcome? = entries.firstOrNull { it.name == name }
    }
}
