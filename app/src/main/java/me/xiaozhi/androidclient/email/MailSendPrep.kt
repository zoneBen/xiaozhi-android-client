package me.xiaozhi.androidclient.email

/**
 * 发信之前那两件"要不要发、发给谁"的判断，**都是纯函数**。
 *
 * 抽出来的理由不是洁癖：这两个判断各有三个调用方——MCP 工具的声明、MCP 工具执行前的
 * 拒绝、以及后面的定时任务动作。三处各写一遍，就会出现"声明时说还没开、执行时说没配好"
 * 这种自相矛盾的回复；而且它们恰恰是"不起真机也能测"的那部分，值得从
 * [PhotoEmailer] 那个必须连着真摄像头和真 SMTP 的类里拿出来。
 */

/**
 * 总开关关着时的拒绝语。
 *
 * **保留"已被用户关闭"这个子串**：它和 `DeviceToolSet` 里那几句是同一个约定，
 * 是"关掉之后真的拦住了"的可观测标记，日志断言和单测都用它。
 */
internal const val EMAIL_DISABLED_MESSAGE = "本机的邮件发送功能已被用户关闭，无法发邮件。"

/**
 * 现在能不能发信：null 表示能，否则给出**回给模型/显示给用户**的中文原因。
 *
 * 总开关和服务器凭据一起看，是因为它们在用户眼里是同一件事（"邮箱能不能用"）。
 * 收件人**不在这里**，理由见 [EmailConfigValidator.validateServer]。
 */
internal fun mailSendProblem(config: EmailConfig): String? {
    if (!config.enabled) return EMAIL_DISABLED_MESSAGE
    val problem = EmailConfigValidator.validateServer(config) ?: return null
    return "本机的邮箱还没配好：$problem 请先到「邮件设置」里补齐。"
}

/**
 * 这封信到底发给谁：模型临时指定的 [requested] 优先，没指定才用设置里的默认收件人。
 *
 * 两者都没有时**不猜**——回一句能让用户动手去填的失败，而不是发给自己或发到一个
 * 编出来的地址。这正是"模型说「已经发到你邮箱了」但其实没发"最可能的来路。
 */
internal fun resolveRecipient(config: EmailConfig, requested: String?): Result<String> {
    val explicit = requested?.trim().orEmpty()
    val resolved = if (explicit.isNotBlank()) explicit else config.defaultRecipient.trim()
    if (resolved.isBlank()) {
        return Result.failure(
            IllegalStateException("没有指定收件人，本机也没有设置默认收件人。请先到「邮件设置」里填写默认收件人。"),
        )
    }
    val problem = EmailConfigValidator.validateRecipient(resolved)
    if (problem != null) {
        val source = if (explicit.isNotBlank()) "模型指定的收件人" else "默认收件人"
        return Result.failure(IllegalStateException("$source 不对：$problem"))
    }
    return Result.success(resolved)
}
