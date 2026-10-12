package me.xiaozhi.androidclient.email

/**
 * 发信要用的全部设置。
 *
 * [enabled] 是**总开关**，默认关：它回答的是「要不要给大模型一个能往外发东西的能力」。
 * 只有用户自己配好服务器并主动打开，才会把这个能力暴露出去（MCP 工具那侧据此决定
 * 要不要向服务端声明 `self.email.*`）。
 */
data class EmailConfig(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = EmailSecurity.SSL.defaultPort,
    val security: EmailSecurity = EmailSecurity.SSL,
    val username: String = "",
    /** 明文存在独立 prefs 文件里，取舍见 [MailSettingsStore] 的文件头注释。 */
    val password: String = "",
    /** 「拍张照发我邮箱」里的"我"：用户没指定收件人时发到这里。 */
    val defaultRecipient: String = "",
    /** 发件人显示名。留空则退回用邮箱地址，不编造一个名字。 */
    val fromName: String = "",
    /** 服务器用自签证书时打开。会降低安全性，界面要写明。 */
    val trustAllCerts: Boolean = false,
)

/**
 * 配置校验。**返回中文原因，通过时返回 null**。
 *
 * 为什么不用 `android.util.Patterns.EMAIL_ADDRESS`：那是 Android 类，用它会把整个
 * 校验器拖出 JVM 单测范围，而这里恰恰是"分支多、容易写错、又不值得起真机去点"的地方。
 * 所以自己写一个够用的正则——要的是"明显不是邮箱的能拦住"，不是 RFC 5322 完全实现。
 */
object EmailConfigValidator {

    /**
     * 够用的邮箱格式：一段非空、不含 `@` 与空白的本地部分，`@`，域名里带一个点。
     *
     * 刻意不追求完备：宁可放过个别怪地址（服务器会拒），也别把合法地址拦下来。
     */
    private val EMAIL_REGEX = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")

    fun validate(config: EmailConfig): String? =
        validateServer(config) ?: when {
            config.defaultRecipient.isBlank() -> "请填写默认收件人邮箱"
            !isValidAddress(config.defaultRecipient) -> "默认收件人的邮箱格式不对"
            else -> null
        }

    /**
     * 只校验**发信必需的服务器与凭据**，不含收件人。
     *
     * 单独拆出来是因为收件人有两种来源：设置页里的默认收件人，以及模型在调用
     * `self.email.send_photo` 时临时指定的 `to`。后者可以让默认收件人空着也照样发出去，
     * 所以"能不能发信"和"发给谁"必须能分开问——混在一起的话，没填默认收件人就会连
     * "指定了收件人"这种正常调用也一起拒掉。
     */
    fun validateServer(config: EmailConfig): String? {
        if (config.host.isBlank()) return "请填写 SMTP 服务器地址"
        if (config.port !in 1..65535) return "端口要填 1~65535 之间的数字"
        if (config.username.isBlank()) return "请填写邮箱账号"
        if (config.password.isBlank()) return "请填写密码或授权码（QQ / 163 要用授权码，不是登录密码）"
        return null
    }

    /** 校验一个单独用到的收件人（比如模型指定了 `to`，或界面上手填一个）。 */
    fun validateRecipient(address: String): String? {
        if (address.isBlank()) return "收件人不能为空"
        if (!isValidAddress(address)) return "收件人邮箱格式不对"
        return null
    }

    fun isValidAddress(address: String): Boolean = EMAIL_REGEX.matches(address.trim())
}

/**
 * 换了加密方式之后的新配置：**端口跟着走，但不覆盖用户自己填的端口**。
 *
 * 判断依据是"当前端口是不是就跟着上一种加密方式的默认值"：
 * - 是 → 用户从没手动改过，跟着新版默认值走（SSL 465 ↔ STARTTLS 587）；
 * - 不是 → 用户填过一个自定义端口（自建服务器常见），保持不动。
 *
 * 不这么区分的话，要么换了加密方式端口不跟着变（用户得到一句莫名其妙的超时），
 * 要么把用户精心填的自定义端口冲掉。两种都是"界面自作聪明"。
 */
internal fun applySecurityChange(config: EmailConfig, security: EmailSecurity): EmailConfig {
    val portFollowedDefault = config.port == config.security.defaultPort
    return config.copy(
        security = security,
        port = if (portFollowedDefault) security.defaultPort else config.port,
    )
}
