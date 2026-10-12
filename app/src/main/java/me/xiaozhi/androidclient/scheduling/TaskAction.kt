package me.xiaozhi.androidclient.scheduling

/**
 * 任务到点时**做什么**。
 *
 * 存库时用 [name]（`INJECT_TEXT` / `PHOTO_TO_EMAIL`），所以**不要改这两个名字**——
 * 改了一夜之间所有已存在的任务都认不出来（[fromName] 会返回 null，界面显示"不认识的任务"）。
 */
enum class TaskAction(val label: String) {
    /** 往对话里注入任务存的那句话，走完整对话、助手朗读。 */
    INJECT_TEXT("给助手说句话"),

    /** 拍照并发到邮箱，与 MCP 的 `self.email.send_photo` 共用同一个实现。 */
    PHOTO_TO_EMAIL("拍张照片发邮箱"),
    ;

    companion object {
        /** 认不出来的返回 null——**不要**在这里兜底成某个默认动作，那会替用户做他没选的事。 */
        fun fromName(name: String?): TaskAction? = entries.firstOrNull { it.name == name }
    }
}
