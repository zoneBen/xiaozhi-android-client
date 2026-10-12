package me.xiaozhi.androidclient.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配置校验的分支覆盖。
 *
 * 这些分支的价值在于「用户填错了要当场说清楚」。放过了会在发信那一刻变成一句
 * 与配置无关的 SSL/超时错误；拦错了则会让一个能用的配置保存不了。
 */
class EmailConfigValidatorTest {

    private fun config(
        host: String = "smtp.qq.com",
        port: Int = 465,
        username: String = "someone@qq.com",
        password: String = "authcode",
        recipient: String = "someone@qq.com",
    ) = EmailConfig(
        enabled = true,
        host = host,
        port = port,
        username = username,
        password = password,
        defaultRecipient = recipient,
    )

    @Test
    fun `填全了就是通过`() {
        assertNull(EmailConfigValidator.validate(config()))
    }

    @Test
    fun `没填服务器地址`() {
        assertEquals("请填写 SMTP 服务器地址", EmailConfigValidator.validate(config(host = "  ")))
    }

    @Test
    fun `端口越界`() {
        assertEquals("端口要填 1~65535 之间的数字", EmailConfigValidator.validate(config(port = 0)))
        assertEquals("端口要填 1~65535 之间的数字", EmailConfigValidator.validate(config(port = 65536)))
    }

    @Test
    fun `端口边界值算合法`() {
        assertNull(EmailConfigValidator.validate(config(port = 1)))
        assertNull(EmailConfigValidator.validate(config(port = 65535)))
    }

    @Test
    fun `没填账号`() {
        assertEquals("请填写邮箱账号", EmailConfigValidator.validate(config(username = "")))
    }

    @Test
    fun `没填密码时提示要的是授权码`() {
        // 这条提示必须点名"授权码"：用户拿登录密码去试，得到的是 535，
        // 而 535 的文案不会告诉他"你该用授权码"。
        val reason = EmailConfigValidator.validate(config(password = ""))
        assertEquals("请填写密码或授权码（QQ / 163 要用授权码，不是登录密码）", reason)
    }

    @Test
    fun `没填默认收件人`() {
        assertEquals("请填写默认收件人邮箱", EmailConfigValidator.validate(config(recipient = "")))
    }

    @Test
    fun `默认收件人格式不对`() {
        assertEquals("默认收件人的邮箱格式不对", EmailConfigValidator.validate(config(recipient = "someone@")))
    }

    @Test
    fun `邮箱格式的判定`() {
        assertTrue(EmailConfigValidator.isValidAddress("a@b.cn"))
        assertTrue(EmailConfigValidator.isValidAddress("first.last+tag@sub.example.com"))
        // 两端空白是复制粘贴带进来的，不该因此判错
        assertTrue(EmailConfigValidator.isValidAddress("  a@b.cn  "))

        assertFalse(EmailConfigValidator.isValidAddress("a@b"))        // 域名没有点
        assertFalse(EmailConfigValidator.isValidAddress("@b.cn"))       // 没有本地部分
        assertFalse(EmailConfigValidator.isValidAddress("a b@c.cn"))    // 本地部分里有空格
        assertFalse(EmailConfigValidator.isValidAddress("a@b@c.cn"))    // 两个 @
        assertFalse(EmailConfigValidator.isValidAddress(""))
    }

    @Test
    fun `单独校验收件人时给的是收件人自己的说法`() {
        assertEquals("收件人不能为空", EmailConfigValidator.validateRecipient(""))
        assertEquals("收件人邮箱格式不对", EmailConfigValidator.validateRecipient("nope"))
        assertNull(EmailConfigValidator.validateRecipient("a@b.cn"))
    }

    @Test
    fun `总开关不影响配置校验`() {
        // enabled 是"要不要把能力暴露给大模型"，与"这份配置能不能用"是两件事。
        // 混在一起的话，用户刚填完还没打开开关时会被拦下来，而他并没做错什么。
        assertNull(EmailConfigValidator.validate(config().copy(enabled = false)))
    }
}
