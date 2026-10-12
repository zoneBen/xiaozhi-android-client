package me.xiaozhi.androidclient.email

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 换加密方式时端口的去留、一键填充，以及下拉里选谁。
 *
 * 两处都属于"看起来显而易见、错了却要真机发信才发现"的逻辑：端口被冲掉会得到一句
 * 莫名其妙的超时，端口不跟着走也是一样。放在这里逐条钉住。
 */
class EmailConfigChangesTest {

    private fun config(
        port: Int = EmailSecurity.SSL.defaultPort,
        security: EmailSecurity = EmailSecurity.SSL,
        host: String = "smtp.qq.com",
    ) = EmailConfig(
        enabled = true,
        host = host,
        port = port,
        security = security,
        username = "someone@qq.com",
        password = "authcode",
        defaultRecipient = "someone@qq.com",
    )

    @Test
    fun `端口还是默认值时，换加密方式端口跟着走`() {
        val changed = applySecurityChange(config(), EmailSecurity.STARTTLS)

        assertEquals(EmailSecurity.STARTTLS, changed.security)
        assertEquals(587, changed.port)
    }

    @Test
    fun `用户自己填过端口时，换加密方式不覆盖它`() {
        // 自建服务器常见：SSL 挂在 2465 上。用户改过一次就说明他知道自己要什么。
        val changed = applySecurityChange(config(port = 2465), EmailSecurity.STARTTLS)

        assertEquals(EmailSecurity.STARTTLS, changed.security)
        assertEquals(2465, changed.port)
    }

    @Test
    fun `连着换两次也不会把端口判成自定义`() {
        // 465 → 587 之后端口等于 STARTTLS 的默认值，再换回 SSL 应当回到 465。
        // 这条防的是"用第一次的默认值去比对"这类实现走偏。
        val once = applySecurityChange(config(), EmailSecurity.STARTTLS)
        val twice = applySecurityChange(once, EmailSecurity.SSL)

        assertEquals(465, twice.port)
    }

    @Test
    fun `换加密方式不动其它字段`() {
        val original = config(port = 465)
        val changed = applySecurityChange(original, EmailSecurity.NONE)

        assertEquals(original.copy(security = EmailSecurity.NONE, port = 25), changed)
    }

    @Test
    fun `套用预设只动服务器那三项`() {
        val applied = config().copy(username = "mine@qq.com", password = "code")
            .applyPreset(MAIL_PROVIDER_PRESETS.first { it.label == "Gmail" })

        assertEquals("smtp.gmail.com", applied.host)
        assertEquals(587, applied.port)
        assertEquals(EmailSecurity.STARTTLS, applied.security)
        // 账号密码是用户的，预设不可能知道，也不该抹掉。
        assertEquals("mine@qq.com", applied.username)
        assertEquals("code", applied.password)
    }

    @Test
    fun `高亮的那一项要跟着实际字段走，不是跟着点过谁`() {
        val gmail = config().applyPreset(MAIL_PROVIDER_PRESETS.first { it.label == "Gmail" })
        assertEquals("Gmail", gmail.matchingPreset()?.label)

        // 套用后又手动改了端口 → 不再匹配，界面上就不该继续亮着。
        assertNull(gmail.copy(port = 2525).matchingPreset())
    }

    @Test
    fun `服务器地址大小写与多余空格不影响匹配`() {
        val qq = config(host = "  SMTP.QQ.COM  ", port = 465, security = EmailSecurity.SSL)
        assertEquals("QQ 邮箱", qq.matchingPreset()?.label)
    }

    @Test
    fun `一份都没填的配置不匹配任何预设`() {
        assertNull(EmailConfig().matchingPreset())
    }

    @Test
    fun `189 邮箱是电信的 smtp_189_cn 加 465`() {
        // 189 = 天翼邮箱。写死在这里是因为它没有第二条可验证的途径：
        // 真机上发一封要用户自己的账号密码，而填错了只会得到一句超时。
        val preset = MAIL_PROVIDER_PRESETS.first { it.label == "189 邮箱" }

        assertEquals("smtp.189.cn", preset.host)
        assertEquals(465, preset.port)
        assertEquals(EmailSecurity.SSL, preset.security)
    }

    @Test
    fun `每家预设的端口都等于它那种加密方式的默认端口`() {
        // 这条不变量撑着 applySecurityChange 那套"端口跟着默认值走"的判断：
        // 预设要是填了个非默认端口（比如把 163 写成 994），用户从它切到别的加密方式时
        // 就会被当成"自己填过端口"而不再跟随，得到一句莫名其妙的超时。
        MAIL_PROVIDER_PRESETS.forEach { preset ->
            assertEquals(
                "${preset.label} 的端口应当是 ${preset.security} 的默认端口",
                preset.security.defaultPort,
                preset.port,
            )
        }
    }

    @Test
    fun `每家预设都得写清楚它额外要求什么`() {
        // 这一句是用户"服务器端口都填对了却仍然 535"时唯一的救兵：
        // QQ/163 要授权码、189 要先在网页版把发信服务打开——都不是界面上能看出来的。
        MAIL_PROVIDER_PRESETS.forEach { preset ->
            assertTrue("${preset.label} 缺一句注意事项", preset.note.isNotBlank())
        }
    }

    @Test
    fun `其他那一项永远排在最后，序号就是预设的个数`() {
        val options = mailProviderOptions()

        assertEquals(MAIL_PROVIDER_PRESETS.size + 1, options.size)
        assertEquals(OTHER_MAIL_PROVIDER, options.last())
        // 没匹配上任何预设（含全新空配置、含用户手填的服务器） → 落在「其他」。
        assertEquals(MAIL_PROVIDER_PRESETS.size, EmailConfig().mailProviderIndex())
    }

    @Test
    fun `选其他只清服务器地址，端口和加密方式留着`() {
        // 端口和加密有合理默认值，清掉只会逼用户凭空想一个数字；
        // 真正猜不到的只有服务器地址。
        val cleared = config().applyProviderChoice(MAIL_PROVIDER_PRESETS.size)

        assertEquals("", cleared.host)
        assertEquals(465, cleared.port)
        assertEquals(EmailSecurity.SSL, cleared.security)
        assertNull(cleared.matchingPreset())
    }

    @Test
    fun `下拉里选了某一家等于套用它那份预设`() {
        val index = mailProviderOptions().indexOf("189 邮箱")
        val applied = config().applyProviderChoice(index)

        assertEquals("smtp.189.cn", applied.host)
        assertEquals("189 邮箱", applied.matchingPreset()?.label)
        assertEquals(index, applied.mailProviderIndex())
    }
}
