package me.xiaozhi.androidclient.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「能不能发信」和「这封信发给谁」这两个判断。
 *
 * 它们各有三个调用方（MCP 工具的声明、工具执行前的拒绝、后面的定时任务动作），
 * 而**文案本身就是功能**——用户是照着那句话去改设置的，模型也是照着它跟用户解释的。
 * 所以逐句钉住，尤其是"没填默认收件人"和"没指定收件人"这两条容易混的失败路径。
 */
class MailSendPrepTest {

    private fun config(
        enabled: Boolean = true,
        host: String = "smtp.189.cn",
        port: Int = 465,
        username: String = "13355967231@189.cn",
        password: String = "secret",
        recipient: String = "809468582@qq.com",
    ) = EmailConfig(
        enabled = enabled,
        host = host,
        port = port,
        security = EmailSecurity.SSL,
        username = username,
        password = password,
        defaultRecipient = recipient,
    )

    @Test
    fun `总开关关着时说的是已被用户关闭，不是没配好`() {
        // 这个子串和 DeviceToolSet 里那几句是同一个约定：它是"关掉之后真的拦住了"
        // 的可观测标记，日志断言要用。写成"没配好"会让用户去改一堆根本没问题的设置。
        val reason = mailSendProblem(config(enabled = false))

        assertEquals(EMAIL_DISABLED_MESSAGE, reason)
        assertTrue(reason!!.contains("已被用户关闭"))
    }

    @Test
    fun `开着但服务器没填时，原因里带着原话并指向设置页`() {
        val reason = mailSendProblem(config(host = "  "))

        assertTrue(reason!!.contains("请填写 SMTP 服务器地址"))
        assertTrue(reason.contains("邮件设置"))
    }

    @Test
    fun `开着且凭据齐了就能发，默认收件人空着也算能发`() {
        // 这一条是拆分 validateServer 的全部理由：模型可以临时指定 to，
        // 所以"没填默认收件人"不该让 self.email.* 这两个工具整个消失。
        assertNull(mailSendProblem(config(recipient = "")))
    }

    @Test
    fun `模型指定的收件人优先于默认收件人`() {
        val resolved = resolveRecipient(config(), "someone@163.com")

        assertEquals("someone@163.com", resolved.getOrNull())
    }

    @Test
    fun `没指定收件人时用默认收件人`() {
        assertEquals("809468582@qq.com", resolveRecipient(config(), null).getOrNull())
        // 空白串和没给是一回事——模型有时会传一个空字符串过来。
        assertEquals("809468582@qq.com", resolveRecipient(config(), "   ").getOrNull())
    }

    @Test
    fun `两边都没有收件人时失败，并且让人知道该去填哪儿`() {
        val reason = resolveRecipient(config(recipient = ""), null).exceptionOrNull()?.message

        assertTrue(reason!!.contains("默认收件人"))
        assertTrue(reason.contains("邮件设置"))
    }

    @Test
    fun `地址不对时说清楚是谁给的地址`() {
        // 两句话必须能区分：一个要用户去改设置，另一个是模型自己传错了、
        // 它该重试而不是让用户去改设置。
        val fromModel = resolveRecipient(config(), "not-an-email").exceptionOrNull()?.message
        val fromSettings = resolveRecipient(config(recipient = "not-an-email"), null).exceptionOrNull()?.message

        assertTrue(fromModel!!.contains("模型指定的收件人"))
        assertTrue(fromSettings!!.contains("默认收件人"))
    }
}
