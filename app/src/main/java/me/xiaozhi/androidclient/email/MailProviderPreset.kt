package me.xiaozhi.androidclient.email

/**
 * 常见邮箱的一键填充。
 *
 * 存在的理由是**选错一次要付出一次失败的代价**：加密方式和端口写错不会给出
 * "你填错了"，只会给一句含义模糊的 SSL 异常或超时，用户完全回不到"哪个字段不对"。
 * 与其让人去翻服务商的帮助页，不如把国内最常配的几家直接摆出来。
 *
 * **只填服务器、端口和加密方式**——账号、授权码、收件人必须由用户自己填，
 * 我们不可能知道，也不该猜。选「其他」则连服务器地址也交给用户（见
 * [EmailConfig.applyProviderChoice]）。
 */
internal data class MailProviderPreset(
    val label: String,
    val host: String,
    val port: Int,
    val security: EmailSecurity,
    /**
     * 套用它之后补一句"这一家还得注意什么"。
     *
     * 不是可有可无的装饰：三家国内邮箱都卡在**各自不同**的一个前置条件上
     * （QQ/163 要授权码、189 要先在网页版把发信服务打开），而这些条件都写在
     * 服务商的帮助页里、界面上看不出来。用户按对了服务器和端口却仍然 535 的时候，
     * 唯一能救他的就是这一句。
     */
    val note: String = "",
)

internal val MAIL_PROVIDER_PRESETS = listOf(
    // 三家国内邮箱都是隐式 SSL 的 465。
    MailProviderPreset(
        label = "QQ 邮箱",
        host = "smtp.qq.com",
        port = 465,
        security = EmailSecurity.SSL,
        note = "QQ 邮箱要用「授权码」当密码：网页版 设置 → 账户 → POP3/IMAP/SMTP服务 里生成",
    ),
    MailProviderPreset(
        label = "163 邮箱",
        host = "smtp.163.com",
        port = 465,
        security = EmailSecurity.SSL,
        note = "163 也要用「授权码」：网页版 设置 → POP3/SMTP/IMAP 里开启并生成",
    ),
    // 189 = 中国电信天翼邮箱。SMTP 服务默认是关着的，必须先开，否则连不上。
    MailProviderPreset(
        label = "189 邮箱",
        host = "smtp.189.cn",
        port = 465,
        security = EmailSecurity.SSL,
        note = "189 邮箱要先在网页版 设置 → IMAP/POP3/SMTP服务 里打开发信服务，密码一般就是登录密码",
    ),
    // Gmail 用 587 + STARTTLS，国内直连通常不通、需要代理。
    MailProviderPreset(
        label = "Gmail",
        host = "smtp.gmail.com",
        port = 587,
        security = EmailSecurity.STARTTLS,
        note = "Gmail 要用「应用专用密码」，且国内直连通常不通，需要代理",
    ),
)

/** 套用一份预设：只动服务器那三项，账号密码收件人原样保留。 */
internal fun EmailConfig.applyPreset(preset: MailProviderPreset): EmailConfig =
    copy(host = preset.host, port = preset.port, security = preset.security)

/**
 * 这份配置现在等于哪一份预设？都不等则返回 null（界面上就不高亮任何一项）。
 *
 * 比的是 `host + port + security` 三个字段，而不是"用户点过哪个"——
 * 用户套用之后又手动改了端口，那一项就不该继续亮着。
 */
internal fun EmailConfig.matchingPreset(): MailProviderPreset? = MAIL_PROVIDER_PRESETS.firstOrNull {
    it.host.equals(host.trim(), ignoreCase = true) && it.port == port && it.security == security
}

/** 下拉里「其他」那一项的文字。放在这里，界面与测试引用同一个常量。 */
internal const val OTHER_MAIL_PROVIDER = "其他（自己填）"

/**
 * 下拉里的全部选项：预设按列表顺序，**「其他」永远排最后**。
 *
 * 顺序不是随意的：[mailProviderIndex] 靠"匹配不上就取最后一项"来定位「其他」，
 * 所以往 [MAIL_PROVIDER_PRESETS] 里加一家新邮箱不需要改任何界面代码。
 */
internal fun mailProviderOptions(): List<String> =
    MAIL_PROVIDER_PRESETS.map { it.label } + OTHER_MAIL_PROVIDER

/** 当前配置在下拉里该选中第几项。匹配不上任何预设时落在「其他」。 */
internal fun EmailConfig.mailProviderIndex(): Int =
    matchingPreset()?.let { MAIL_PROVIDER_PRESETS.indexOf(it) } ?: MAIL_PROVIDER_PRESETS.size

/**
 * 用户在下拉里选了第 [index] 项之后的配置。
 *
 * 选「其他」= 把服务器地址清空让用户自己填，**但端口和加密方式留着**：
 * 这两项有合理默认值（SSL 465），清掉只会逼用户凭空想一个数字出来，
 * 而清掉之后界面会把它们露出来，想改随时能改。真正猜不到的只有服务器地址。
 */
internal fun EmailConfig.applyProviderChoice(index: Int): EmailConfig =
    MAIL_PROVIDER_PRESETS.getOrNull(index)?.let { applyPreset(it) } ?: copy(host = "")
