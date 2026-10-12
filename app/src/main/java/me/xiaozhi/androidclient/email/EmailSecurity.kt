package me.xiaozhi.androidclient.email

/**
 * SMTP 的加密方式。
 *
 * 三者不是"选一个偏好"，而是**服务器只认其中一种**——选错了握手就失败，
 * 表现是一句含义模糊的 SSL/超时错误。所以界面上要把各家的常见搭配写出来
 * （见 [hint]），让用户照着邮箱服务商的说明对一遍，而不是猜。
 */
enum class EmailSecurity(
    /** 界面上显示的名字。 */
    val label: String,
    /** 选了它之后端口自动填这个值——用户仍然可以改。 */
    val defaultPort: Int,
    /** 一句话说清它是什么。 */
    val hint: String,
) {
    /** 465：一上来就是加密连接。QQ 邮箱、163 用这个。 */
    SSL(
        label = "SSL",
        defaultPort = 465,
        hint = "连上就是加密的，端口一般填 465",
    ),

    /** 587：先明文连上再升级为加密。Gmail 用这个。 */
    STARTTLS(
        label = "STARTTLS",
        defaultPort = 587,
        hint = "先连上再升级加密，端口一般填 587",
    ),

    /**
     * 25：明文。**默认不推荐**，界面上要明说。
     *
     * 密码会以明文过网络；而且移动网络下 25 端口基本被运营商封掉，
     * 用户会得到"连不上"却完全不知道为什么。
     */
    NONE(
        label = "不加密",
        defaultPort = 25,
        hint = "⚠️ 密码明文传输，也不推荐：移动网络下 25 端口通常是被封的",
    ),
}
