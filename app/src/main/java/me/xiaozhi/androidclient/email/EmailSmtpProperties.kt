package me.xiaozhi.androidclient.email

import java.util.Properties

/**
 * [EmailConfig] → JavaMail 的 `Properties`。
 *
 * 抽成纯函数单独测，是为了把"三种加密方式各要哪些键"这件事钉死在单测里——
 * 这些键名写错**不会编译报错**，只会让发信在真机上失败，而且失败信息是一句
 * 与配置无关的 SSL/超时异常，从现象根本回不到"某个键拼错了"。
 */

/** 连接/读写超时。**必须显式设**：JavaMail 默认是无限等待，连不上会永久挂住一个协程。 */
private const val CONNECTION_TIMEOUT_MS = "15000"
private const val READ_TIMEOUT_MS = "20000"
private const val WRITE_TIMEOUT_MS = "20000"

internal fun buildSmtpProperties(config: EmailConfig): Properties = Properties().apply {
    setProperty("mail.smtp.host", config.host)
    setProperty("mail.smtp.port", config.port.toString())
    setProperty("mail.smtp.auth", "true")

    setProperty("mail.smtp.connectiontimeout", CONNECTION_TIMEOUT_MS)
    setProperty("mail.smtp.timeout", READ_TIMEOUT_MS)
    setProperty("mail.smtp.writetimeout", WRITE_TIMEOUT_MS)

    when (config.security) {
        EmailSecurity.SSL -> {
            // implicit SSL：套接字从建立那一刻就被 SSL 包住。socketFactory.class 与
            // port 必须成对出现，只设 ssl.enable 在某些实现下会退化成明文连接。
            setProperty("mail.smtp.ssl.enable", "true")
            setProperty("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            setProperty("mail.smtp.socketFactory.port", config.port.toString())
            // 不允许悄悄退回明文：那等于用户以为加密了、其实密码裸奔。
            setProperty("mail.smtp.socketFactory.fallback", "false")
        }

        EmailSecurity.STARTTLS -> {
            setProperty("mail.smtp.starttls.enable", "true")
            // required=true：服务器不支持 STARTTLS 时**直接失败**，而不是退回明文继续发。
            setProperty("mail.smtp.starttls.required", "true")
        }

        // 明文：一个键都不加。用户明确选了这个，界面已经警告过。
        EmailSecurity.NONE -> Unit
    }

    // 自签证书（trustAllCerts）不在这里处理：它要装一个 SSLSocketFactory 实例，
    // 而实例不是字符串，塞不进这份纯配置。由 SmtpClient 在这个 Properties 之上补。
}
