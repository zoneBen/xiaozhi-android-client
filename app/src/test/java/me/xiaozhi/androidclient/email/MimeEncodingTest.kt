package me.xiaozhi.androidclient.email

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Properties
import javax.mail.Message
import javax.mail.Session
import javax.mail.internet.MimeMessage
import javax.mail.internet.MimeMultipart
import javax.mail.internet.MimeUtility
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 中文主题 / 中文附件名到底有没有被正确编码。
 *
 * 这一条在计划里被标成"最容易踩的坑"，但**真机上很难反复验**：要真发一封信、打开邮箱、
 * 肉眼看标题。所以这里直接组装一封信（不联网），把线上的原文和解出来的结果都钉住。
 *
 * 这里钉住的行为是**踩过一次坑之后**才对的：一度以为"中文附件名必须自己
 * `MimeUtility.encodeText`"，于是把编好的 `=?UTF-8?Q?...?=` 又交给 JavaMail——
 * 它会把这段 ASCII 当普通参数值重新折行，输出成 `filename*0=` / `filename*1=`
 * 两片，而且**断在转义序列中间**，收件端拼回去是坏名字。
 * 正确做法是**什么都不做**：JavaMail 1.6 自己会用 RFC 2231
 * （`filename*=UTF-8''%E5%B0%8F...`）编码，且能被正确解回。
 * 所以下面既断言"线上是 RFC 2231"，也断言"**不是**那两片拼接形态"。
 */
class MimeEncodingTest {

    private val config = EmailConfig(
        enabled = true,
        host = "smtp.qq.com",
        port = 465,
        security = EmailSecurity.SSL,
        username = "someone@qq.com",
        password = "authcode",
        defaultRecipient = "someone@qq.com",
        fromName = "小智",
    )

    private val session: Session = SmtpClient.buildSession(config)

    private fun assemble(subject: String, attachments: List<File> = emptyList()) = SmtpClient.buildMessage(
        session = session,
        config = config,
        to = "someone@qq.com",
        subject = subject,
        body = "这是一封测试邮件。",
        attachments = attachments,
    )

    /** 整封信的原始字节。用 ISO-8859-1 读回：头里的非 ASCII 一定是编码过的，不该出现裸中文。 */
    private fun raw(message: MimeMessage): String = ByteArrayOutputStream()
        .also { message.writeTo(it) }
        .toString("ISO-8859-1")

    /** 按收件端那样读回来——走一遍真正的线格式，而不是看内存里的对象。 */
    private fun reread(message: MimeMessage): MimeMessage = MimeMessage(
        Session.getInstance(Properties()),
        ByteArrayInputStream(ByteArrayOutputStream().also { message.writeTo(it) }.toByteArray()),
    )

    private fun tempPhoto(): File = File.createTempFile("小智拍的照片", ".jpg").apply {
        deleteOnExit()
        writeBytes(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `中文主题按 RFC 2047 编码，且收件端解得回原文`() {
        val message = assemble("小智测试邮件")

        assertTrue(
            "主题应当是编码过的 =?UTF-8? 形态，实际：${message.getHeader("Subject")?.joinToString()}",
            raw(message).contains("=?UTF-8?"),
        )
        // 往返：收件端解出来必须是原文，不是乱码。
        assertEquals("小智测试邮件", message.subject)
    }

    @Test
    fun `中文附件名走 RFC 2231，收件端解得回原文`() {
        val photo = tempPhoto()
        val message = assemble("带附件", listOf(photo))
        val raw = raw(message)

        assertTrue(
            "附件名应当是 RFC 2231 的 filename*=UTF-8'' 形态，实际：$raw",
            raw.contains("filename*=UTF-8''"),
        )
        // 双重编码会把它折成 filename*0= / filename*1= 两片，且断在转义序列中间。
        assertFalse("附件名不该出现分片形态（那是被二次编码的标志）", raw.contains("filename*0="))

        // 收件端解出来的名字必须是原来那个——这一步才是真正想钉住的东西。
        val part = (reread(message).content as MimeMultipart).getBodyPart(1)
        assertEquals(photo.name, part.getFileName())
        assertTrue("名字里那个中文前缀得原样在", part.getFileName().startsWith("小智拍的照片"))
    }

    @Test
    fun `附件名在 Content-Type 的 name 参数上也编码`() {
        // 有些客户端（尤其老版 Outlook）优先读 Content-Type 的 name=，那一处漏了照样是乱码。
        val message = assemble("带附件", listOf(tempPhoto()))

        assertTrue(raw(message).contains("name*=UTF-8''"))
    }

    @Test
    fun `中文正文声明 UTF-8，不露裸字节`() {
        val raw = raw(assemble("正文测试"))

        assertTrue(
            "正文部分应当声明 UTF-8，实际：$raw",
            raw.contains("charset=UTF-8") || raw.contains("charset=utf-8"),
        )
    }

    @Test
    fun `发件人显示名是中文时也编码`() {
        val from = assemble("无附件").getHeader("From")?.joinToString() ?: ""

        assertTrue("发件人头应当带编码过的显示名，实际：$from", from.contains("=?UTF-8?"))
        assertTrue("解码后应当能看到「小智」，实际：$from", MimeUtility.decodeText(from).contains("小智"))
    }

    @Test
    fun `没有附件时不带 Content-Disposition，免得客户端显示一个空附件`() {
        val part = (reread(assemble("无附件")).content as MimeMultipart).getBodyPart(0)

        assertNull(part.getHeader("Content-Disposition"))
    }

    @Test
    fun `纯 ASCII 的收件人原样进 To 头`() {
        // InternetAddress.parse(to, true) 的第二参是 strict：这里钉住"我们只喂纯地址"这个前提。
        val message = assemble("无附件")

        assertEquals(
            "someone@qq.com",
            message.getRecipients(Message.RecipientType.TO)?.first()?.toString(),
        )
    }
}
