package me.xiaozhi.androidclient.email

import android.content.Context

/**
 * 邮件设置的落盘处，**独立一个 prefs 文件**（`xiaozhi_mail`）。
 *
 * 为什么不塞进 `AppPreferences`（`xiaozhi_android_client`）：那个文件的写入方式是
 * 「从 `UiState` 整体重建 `StoredConfig` 再整包写回」，而 `MainViewModel` 持有的
 * `StoredConfig` 是**构造时的一次性快照**。邮件设置页若直接写同一份 prefs，
 * 主 VM 下一次 `persist()` 就会用陈旧快照把刚存进去的 SMTP 密码抹掉——
 * 而且是不报错的静默失败。独立文件 + 发信时现场 `load()`，根本不存在这个竞态。
 *
 * `ReminderScheduler` 自带 `xiaozhi_reminders` 就是这个做法的先例。
 *
 * **密码是明文存的**，这是用户明确的决定（与本机现有的几十项设置、
 * 以及服务端的 `auth_token` 一致），换来的是零新依赖。取舍写在这里而不是藏着：
 * 如果要加密，正确做法是换 `EncryptedSharedPreferences`（注意
 * `androidx.security:security-crypto` 已被官方废弃，且自带密钥管理的故障模式，
 * 不要未经评估就上）。
 */
class MailSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun load(): EmailConfig = EmailConfig(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        host = prefs.getString(KEY_HOST, "").orEmpty(),
        // 端口存成 String 再解析：`getInt` 在键不存在时只能给一个默认值，
        // 而"没填过"和"填成 465"应当区分开——前者该跟加密方式的默认端口走。
        port = prefs.getString(KEY_PORT, "")?.toIntOrNull() ?: EmailSecurity.SSL.defaultPort,
        security = prefs.getString(KEY_SECURITY, "")?.let { stored ->
            EmailSecurity.entries.firstOrNull { it.name == stored }
        } ?: EmailSecurity.SSL,
        username = prefs.getString(KEY_USERNAME, "").orEmpty(),
        password = prefs.getString(KEY_PASSWORD, "").orEmpty(),
        defaultRecipient = prefs.getString(KEY_DEFAULT_RECIPIENT, "").orEmpty(),
        fromName = prefs.getString(KEY_FROM_NAME, "").orEmpty(),
        trustAllCerts = prefs.getBoolean(KEY_TRUST_ALL, false),
    )

    fun save(config: EmailConfig) {
        prefs.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_HOST, config.host)
            .putString(KEY_PORT, config.port.toString())
            .putString(KEY_SECURITY, config.security.name)
            .putString(KEY_USERNAME, config.username)
            .putString(KEY_PASSWORD, config.password)
            .putString(KEY_DEFAULT_RECIPIENT, config.defaultRecipient)
            .putString(KEY_FROM_NAME, config.fromName)
            .putBoolean(KEY_TRUST_ALL, config.trustAllCerts)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "xiaozhi_mail"

        private const val KEY_ENABLED = "enabled"
        private const val KEY_HOST = "host"
        private const val KEY_PORT = "port"
        private const val KEY_SECURITY = "security"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_DEFAULT_RECIPIENT = "default_recipient"
        private const val KEY_FROM_NAME = "from_name"
        private const val KEY_TRUST_ALL = "trust_all_certs"
    }
}
