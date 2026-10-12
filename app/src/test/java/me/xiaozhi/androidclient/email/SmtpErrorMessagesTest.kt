package me.xiaozhi.androidclient.email

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.mail.AuthenticationFailedException
import javax.mail.MessagingException
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发信失败时给用户看的那句话。
 *
 * 这一族分支（认证失败、找不到域名、证书不过、超时）**没有一个能在真机上方便地复现**，
 * 所以它们必须在这里被钉住。判定的依据是 cause 链：JavaMail 把真实原因包在
 * `MessagingException` 里，只看最外层只能得到一句没用的 "Sending failed"。
 */
class SmtpErrorMessagesTest {

    private fun assertContains(needle: String, actual: String) {
        assertTrue("期望「$actual」里包含「$needle」", actual.contains(needle))
    }

    @Test
    fun `535 认证失败时点名要说授权码`() {
        // 这是最高频的一种：用户拿邮箱登录密码当密码填。
        val error = MessagingException("Sending failed", AuthenticationFailedException("535 5.7.8 Username and Password not accepted"))
        val message = smtpErrorMessage(error)

        assertContains("授权码", message)
        assertContains("不对", message)
    }

    @Test
    fun `找不到域名`() {
        val error = MessagingException("Sending failed", UnknownHostException("smtp.qq.comx"))
        assertContains("找不到这个服务器地址", smtpErrorMessage(error))
    }

    @Test
    fun `证书校验失败时提示去开信任开关`() {
        val error = MessagingException(
            "Sending failed",
            SSLHandshakeException("Trust anchor for certification path not found"),
        )
        assertContains("证书校验失败", smtpErrorMessage(error))
        assertContains("信任自签证书", smtpErrorMessage(error))
    }

    @Test
    fun `超时`() {
        val error = MessagingException("Sending failed", SocketTimeoutException("connect timed out"))
        assertContains("超时", smtpErrorMessage(error))
    }

    @Test
    fun `550 收件地址被拒`() {
        val error = MessagingException("Sending failed", MessagingException("550 5.1.1 The email account that you tried to reach does not exist"))
        assertContains("收件地址被服务器拒绝", smtpErrorMessage(error))
    }

    @Test
    fun `530 要求先认证`() {
        val error = MessagingException("530 5.7.0 Authentication required")
        assertContains("SMTP 服务", smtpErrorMessage(error))
    }

    @Test
    fun `认不出来时把原因原样透出来，而不是吞掉`() {
        // 失败原因里常带服务器给的诊断文本，那是对付疑难杂症的唯一线索。
        val error = MessagingException("Sending failed", MessagingException("421 4.7.0 Try again later"))
        assertContains("421 4.7.0 Try again later", smtpErrorMessage(error))
    }

    @Test
    fun `连消息都没有时不假装知道原因`() {
        val message = smtpErrorMessage(RuntimeException())
        assertContains("RuntimeException", message)
    }

    @Test
    fun `证书问题优先于超时判定`() {
        // SSL 失败在部分实现下会被包成 SocketTimeoutException；证书要先说。
        val error = MessagingException(
            "Sending failed",
            SSLHandshakeException("handshake failed"),
        )
        assertEquals(
            "证书校验失败。如果服务器用的是自签证书，请打开「信任自签证书」",
            smtpErrorMessage(error),
        )
    }
}
