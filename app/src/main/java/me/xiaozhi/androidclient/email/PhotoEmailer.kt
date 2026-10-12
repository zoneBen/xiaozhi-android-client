package me.xiaozhi.androidclient.email

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CancellationException
import me.xiaozhi.androidclient.camera.CameraVisionTool

/**
 * 这台设备"发一封邮件"的**唯一实现**：把邮箱配置、拍照、SMTP 三件事接在一起。
 *
 * 为什么要单独一个类：[McpCameraServer] 的邮件工具和执行定时任务的动作要做同一件事
 * ——"拍张照发出去"。两条路各写一遍的话，错误文案会分叉，而文案恰好是用户唯一看得见的
 * 差别（他会照着这句话去改设置）。
 *
 * 这里只做"接起来"和"读盘"，所有判断都在 [MailSendPrep] 那几个纯函数里，
 * 所以这一层薄到不需要替身就能读明白。
 */
class PhotoEmailer(
    private val cameraVisionTool: CameraVisionTool,
    private val mailSettings: MailSettingsStore,
    private val log: (String) -> Unit = {},
) {

    /**
     * 现在能不能发信。不能就给出中文原因（回给模型，也会显示在对话里）。
     *
     * 每次现读 prefs，和 [CameraVisionTool.enabled] 一个路子：用户在设置页改完、
     * 打开总开关，下一个回合就该生效，不必等重连或重启。
     */
    fun notReadyReason(): String? = mailSendProblem(mailSettings.load())

    /** 能发信吗。工具要不要向服务端声明就看它。 */
    fun isReady(): Boolean = notReadyReason() == null

    /**
     * 拍一张照片发到邮箱。
     *
     * 失败**不抛异常**，而是返回带中文原因的 `Result`：收口在 [SmtpClient] 和
     * [CameraVisionTool] 的错误文案，正是要原样回给模型、再被它讲给用户听的东西。
     *
     * @param to 模型指定的收件人。空则用设置里的默认收件人。
     * @return 成功时是一句可以直接念给用户听的话（含收件地址）。
     */
    suspend fun captureAndSend(to: String?, subject: String?, note: String?): Result<String> {
        val config = readyConfig() ?: return Result.failure(IllegalStateException(notReadyReason()))
        val recipient = resolveRecipient(config, to).getOrElse { return Result.failure(it) }

        // 先拍照再发信：拍不到就根本不进 SMTP，用户不必等一次必然会失败的发信。
        // 注意 captureToFile() 已经把这张图贴进对话了（CameraVisionTool.onPhotoCaptured），
        // 所以哪怕发信失败，用户在对话里也看得到"当时拍到了什么"。
        val photo = captureOrFailure().getOrElse { return Result.failure(it) }
        log("已拍下照片 ${photo.name}，准备发到 $recipient")

        val body = note?.trim().takeIf { !it.isNullOrBlank() } ?: defaultPhotoBody()
        return SmtpClient.send(
            config = config,
            to = recipient,
            subject = subject?.trim().takeIf { !it.isNullOrBlank() } ?: DEFAULT_PHOTO_SUBJECT,
            body = body,
            attachments = listOf(photo),
        ).fold(
            onSuccess = {
                log("照片已发到 $recipient")
                Result.success("照片已经发到 $recipient 了。")
            },
            onFailure = { error -> Result.failure(error) },
        )
    }

    /** 发一封纯文字的邮件（不带照片）。给 `self.email.send_message` 用。 */
    suspend fun sendText(to: String?, subject: String?, body: String): Result<String> {
        if (body.isBlank()) {
            return Result.failure(IllegalStateException("要发送的正文是空的，请先给出内容。"))
        }
        val config = readyConfig() ?: return Result.failure(IllegalStateException(notReadyReason()))
        val recipient = resolveRecipient(config, to).getOrElse { return Result.failure(it) }

        return SmtpClient.send(
            config = config,
            to = recipient,
            subject = subject?.trim().takeIf { !it.isNullOrBlank() } ?: DEFAULT_TEXT_SUBJECT,
            body = body,
        ).fold(
            onSuccess = {
                log("文字邮件已发到 $recipient")
                Result.success("邮件已经发到 $recipient 了。")
            },
            onFailure = { error -> Result.failure(error) },
        )
    }

    /**
     * 读一份**当下可用**的配置。不能用（总开关关着 / 服务器凭据不全）则返回 null，
     * 原因由 [notReadyReason] 给出——两个公开方法的失败路径都拿它去拼消息。
     */
    private fun readyConfig(): EmailConfig? = mailSettings.load().takeIf { mailSendProblem(it) == null }

    /**
     * 拍一张，拿不到就把异常**原样**交出去。
     *
     * 分开写是因为不能简单地 `runCatching`：[CancellationException] 必须重抛，
     * 协程被取消时把它吞成一次"拍照失败"会让上层的取消逻辑失效（而取消在这里是常态——
     * 会话断了、用户按了停止，都会撤掉这个协程）。
     */
    private suspend fun captureOrFailure(): Result<File> = try {
        Result.success(cameraVisionTool.captureToFile())
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Result.failure(PhotoCaptureException(error.message ?: "拍照失败", error))
    }

    /**
     * 随照片一起发出去的默认正文。
     *
     * 带上时间和文件名：收件人（常常就是用户自己）在相册里翻到这封信时，
     * 这两样是唯一能告诉他"这是什么时候、哪一张"的东西。
     */
    private fun defaultPhotoBody(): String =
        "这是小智拍下并寄给你的照片，见附件。\n\n拍摄时间：${timestamp()}\n"

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())

    private companion object {
        const val DEFAULT_PHOTO_SUBJECT = "小智拍的照片"
        const val DEFAULT_TEXT_SUBJECT = "小智发来的消息"
    }
}
