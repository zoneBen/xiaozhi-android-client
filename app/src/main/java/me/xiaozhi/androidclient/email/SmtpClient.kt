package me.xiaozhi.androidclient.email

import java.io.File
import javax.mail.Authenticator
import javax.mail.Message
import javax.mail.PasswordAuthentication
import javax.mail.Session
import javax.mail.Transport
import javax.mail.internet.InternetAddress
import javax.mail.internet.MimeBodyPart
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 发一封邮件（可带附件）。**JavaMail 只住在这个文件里**——
 * 将来要换成别的实现（`android-mail` 是 2021 年的最后一版，已不再更新），
 * 替换面就是这一个文件。
 */
object SmtpClient {

    /**
     * 发送。失败也**不抛异常**，而是返回带中文原因的 `Result`——
     * 调用方要么是设置页的"发送测试邮件"、要么是定时任务的执行记录，
     * 两处都需要把原因原样显示给用户，抛出去只会被层层包成没用的东西。
     *
     * @param attachments 直接传 `File`。JavaMail 自己读文件，**不需要 FileProvider、
     *   也不需要 ContentResolver**——所以 `res/xml/file_paths.xml` 里不必加
     *   `<external-files-path>`。（只有将来要用系统分享 Intent 把照片发出去时才需要。）
     */
    suspend fun send(
        config: EmailConfig,
        to: String,
        subject: String,
        body: String,
        attachments: List<File> = emptyList(),
    ): Result<Unit> = withContext(Dispatchers.IO) {
        val recipientError = EmailConfigValidator.validateRecipient(to)
        if (recipientError != null) {
            return@withContext Result.failure(IllegalArgumentException(recipientError))
        }

        try {
            val session = buildSession(config)
            val message = buildMessage(session, config, to, subject, body, attachments)
            Transport.send(message)
            Result.success(Unit)
        } catch (error: Throwable) {
            Result.failure(IllegalStateException(smtpErrorMessage(error), error))
        }
    }

    /** 会话（含认证器）。抽出来是为了让 [buildMessage] 能被单测直接组装一封信。 */
    internal fun buildSession(config: EmailConfig): Session {
        val properties = buildSmtpProperties(config).apply {
            // 自签证书在这里补：它要的是一个**类名**（JavaMail 反射实例化），
            // 而 buildSmtpProperties 是一份要单测的纯字符串配置，不适合塞反射细节。
            //
            // SSL 与 STARTTLS 都读这个键，所以不必分两种情况写。
            if (config.trustAllCerts) {
                setProperty(
                    "mail.smtp.ssl.socketFactory.class",
                    TrustingSslSocketFactory::class.java.name,
                )
                // 同样不许悄悄退回明文。
                setProperty("mail.smtp.ssl.socketFactory.fallback", "false")
            }
        }
        return Session.getInstance(properties, object : Authenticator() {
            override fun getPasswordAuthentication(): PasswordAuthentication =
                PasswordAuthentication(config.username, config.password)
        })
    }

    /**
     * 组装一封信。**只组装、不发送**，所以它是可测的——
     * 而"中文主题和中文附件名会不会乱码"恰恰是整条发信链路上最难在真机上反复验、
     * 又最容易踩的一处（`setSubject(subject)` 少一个 `"UTF-8"` 就乱），
     * 放在这里就能用 JVM 单测钉死（见 `MimeEncodingTest`）。
     */
    internal fun buildMessage(
        session: Session,
        config: EmailConfig,
        to: String,
        subject: String,
        body: String,
        attachments: List<File>,
    ): MimeMessage = MimeMessage(session).apply {
        val fromAddress = InternetAddress(
            config.username,
            config.fromName.ifBlank { config.username },
            // 指定 UTF-8：不指定的话中文发件人名会乱码。
            "UTF-8",
        )
        setFrom(fromAddress)
        setRecipients(Message.RecipientType.TO, InternetAddress.parse(to, true))
        // 中文主题必须显式指定 UTF-8，否则 QQ 邮箱里看到的是乱码。
        setSubject(subject, "UTF-8")
        setContent(buildContent(body, attachments))
        saveChanges()
    }

    /**
     * 正文 + 附件。有附件时用 `multipart/mixed`；没有附件时也走同一条路——
     * 两种形态各写一遍才是隐患所在（改一处忘一处）。
     *
     * **中文附件名这里一个字都不要动。** 曾经"顺手"加过
     * `fileName = MimeUtility.encodeText(file.name, "UTF-8", null)`，结果 JavaMail
     * 把这段编好的 ASCII 当普通参数值再折一次行，线上变成 `filename*0=` / `filename*1=`
     * 两片、还断在转义序列中间，收件端拼回来是坏名字。
     * `attachFile` 自己就会按 RFC 2231（`filename*=UTF-8''%E5%B0%8F...`）编码中文名，
     * 这一条在 `MimeEncodingTest` 里有往返验证。名字若真要改，改的是那两份测试，不是这里。
     */
    private fun buildContent(body: String, attachments: List<File>): MimeMultipart {
        val multipart = MimeMultipart("mixed")
        multipart.addBodyPart(
            MimeBodyPart().apply { setText(body, "UTF-8") },
        )
        attachments.forEach { file ->
            multipart.addBodyPart(
                MimeBodyPart().apply {
                    // 用 File 这一版：JavaMail 自己读文件，不需要 FileProvider / ContentResolver。
                    attachFile(file)
                },
            )
        }
        return multipart
    }
}
