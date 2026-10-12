package me.xiaozhi.androidclient.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `Properties` 的**精确键值**断言。
 *
 * 这些键名写错不会编译报错，只会让发信在真机上以一种和配置无关的方式失败
 * （一句 SSL 异常或干脆超时），从现象回不到"某个键拼错了"。所以在这里逐条钉住。
 */
class EmailSmtpPropertiesTest {

    private fun config(security: EmailSecurity, port: Int = security.defaultPort) = EmailConfig(
        enabled = true,
        host = "smtp.example.com",
        port = port,
        security = security,
        username = "someone@example.com",
        password = "authcode",
        defaultRecipient = "someone@example.com",
    )

    @Test
    fun `SSL 要的是隐式加密那一组键`() {
        val props = buildSmtpProperties(config(EmailSecurity.SSL))

        assertEquals("smtp.example.com", props.getProperty("mail.smtp.host"))
        assertEquals("465", props.getProperty("mail.smtp.port"))
        assertEquals("true", props.getProperty("mail.smtp.ssl.enable"))
        assertEquals("javax.net.ssl.SSLSocketFactory", props.getProperty("mail.smtp.socketFactory.class"))
        assertEquals("465", props.getProperty("mail.smtp.socketFactory.port"))
        // fallback=false：不许悄悄退回明文，否则用户以为加密了、其实密码裸奔
        assertEquals("false", props.getProperty("mail.smtp.socketFactory.fallback"))
        // SSL 模式下不该出现 STARTTLS 的键
        assertNull(props.getProperty("mail.smtp.starttls.enable"))
    }

    @Test
    fun `STARTTLS 要的是升级加密那一组键`() {
        val props = buildSmtpProperties(config(EmailSecurity.STARTTLS))

        assertEquals("587", props.getProperty("mail.smtp.port"))
        assertEquals("true", props.getProperty("mail.smtp.starttls.enable"))
        // required=true：服务器不支持就不发，而不是退回明文
        assertEquals("true", props.getProperty("mail.smtp.starttls.required"))
        assertNull(props.getProperty("mail.smtp.ssl.enable"))
        assertNull(props.getProperty("mail.smtp.socketFactory.class"))
    }

    @Test
    fun `不加密时一个加密键都不加`() {
        val props = buildSmtpProperties(config(EmailSecurity.NONE))

        assertEquals("25", props.getProperty("mail.smtp.port"))
        assertNull(props.getProperty("mail.smtp.ssl.enable"))
        assertNull(props.getProperty("mail.smtp.starttls.enable"))
        assertNull(props.getProperty("mail.smtp.socketFactory.class"))
    }

    @Test
    fun `超时必须显式设置，不能留给 JavaMail 的默认值`() {
        // JavaMail 默认无限等待：连不上会永久挂住一个协程，界面上表现为按钮永远转圈。
        val props = buildSmtpProperties(config(EmailSecurity.SSL))

        assertEquals("true", props.getProperty("mail.smtp.auth"))
        assertEquals("15000", props.getProperty("mail.smtp.connectiontimeout"))
        assertEquals("20000", props.getProperty("mail.smtp.timeout"))
        assertEquals("20000", props.getProperty("mail.smtp.writetimeout"))
    }

    @Test
    fun `端口跟用户填的值走，不跟加密方式的默认值走`() {
        // 用户可以把 SSL 配在 465 之外的自定义端口上（自建服务器常见）。
        val props = buildSmtpProperties(config(EmailSecurity.SSL, port = 2465))

        assertEquals("2465", props.getProperty("mail.smtp.port"))
        assertEquals("2465", props.getProperty("mail.smtp.socketFactory.port"))
    }

    @Test
    fun `三种加密方式的默认端口各自正确`() {
        assertEquals(465, EmailSecurity.SSL.defaultPort)
        assertEquals(587, EmailSecurity.STARTTLS.defaultPort)
        assertEquals(25, EmailSecurity.NONE.defaultPort)
    }
}
