package me.xiaozhi.androidclient.email

import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * 把发信失败的异常翻译成**用户能照着做**的中文。
 *
 * 这个函数的全部意义在于：JavaMail 抛出来的东西对用户毫无用处。最外层通常是一句
 * `Sending failed`，真正的原因（535 认证失败、证书校验不过、域名解析不了）埋在
 * cause 链的第两层，而用户看到的就是最外层那句。所以这里顺着 cause 链走一遍，
 * 按"最具体的那个"给结论。
 *
 * 纯函数、只依赖 JVM 类型，所以能直接单测——**这正是它值得单独存在的原因**：
 * 这一族分支（535/550/超时/找不到域名/证书）没有一个能在真机上方便地复现。
 */
internal fun smtpErrorMessage(error: Throwable): String {
    val chain = generateSequence(error) { it.cause }.take(8).toList()

    // 先判类型，再判错误码：类型是确定的，错误码只能靠文本匹配（各家服务器文案不一）。
    if (chain.any { it is SSLException && it !is SocketTimeoutException }) {
        return "证书校验失败。如果服务器用的是自签证书，请打开「信任自签证书」"
    }
    if (chain.any { it is SocketTimeoutException }) {
        return "连不上服务器（等待超时），请检查服务器地址、端口和加密方式"
    }
    if (chain.any { it is UnknownHostException }) {
        return "找不到这个服务器地址，请检查 SMTP 服务器有没有写错"
    }

    val messages = chain.mapNotNull { it.message }.joinToString(" ")

    // 535：认证被拒。绝大多数是"用了邮箱登录密码而不是授权码"。
    if (messages.contains("535") || messages.contains("Authentication failed", ignoreCase = true)) {
        // 不要在这句话里用 `**强调**` 之类的 Markdown：它会被原样显示在提示框里，
        // 用户看到的是两个星号（真机上验过）。想强调就把话说清楚。
        return "账号或密码/授权码不对。注意：QQ 邮箱、163 这里要填的是「授权码」，不是邮箱登录密码"
    }
    // 530：服务器要求先认证（通常是没开 SMTP 服务或账号未启用）。
    if (messages.contains("530")) {
        return "服务器要求先登录才能发信，请到邮箱设置里确认 SMTP 服务已开启"
    }
    // 550 / 553：收件地址被拒（常见于收件人写错，或被判为垃圾邮件）。
    if (messages.contains("550") || messages.contains("553")) {
        return "收件地址被服务器拒绝了，请确认收件人邮箱是否正确"
    }
    // 554：内容被拒（最常见是主题/正文被判垃圾邮件）。
    if (messages.contains("554")) {
        return "服务器拒绝了这封邮件的内容，换个主题再试"
    }

    val detail = chain.lastOrNull { it.message?.isNotBlank() == true }?.message
    return if (detail.isNullOrBlank()) {
        "发送失败：${error.javaClass.simpleName}"
    } else {
        "发送失败：$detail"
    }
}
