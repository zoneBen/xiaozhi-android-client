package me.xiaozhi.androidclient.ui.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.xiaozhi.androidclient.email.EmailConfig
import me.xiaozhi.androidclient.email.EmailConfigValidator
import me.xiaozhi.androidclient.email.MailSettingsStore
import me.xiaozhi.androidclient.email.SmtpClient

/** 「邮件设置」页的全部状态。 */
data class EmailSettingsUiState(
    val config: EmailConfig = EmailConfig(),
    /**
     * 端口输入框里的原始文字。`null` 表示"跟着 [config] 的端口走"。
     *
     * 为什么需要它：端口在 [EmailConfig] 里是 `Int`，如果输入框直接显示
     * `config.port.toString()`，用户想清空重打时会立刻被填回一个数字（清空 → 0 → 显示 "0"），
     * 光标也跳。所以编辑期间保留用户敲的原文，只有一键填充/换加密方式这类
     * "从别处改了端口"的动作才把它丢回 null、让输入框重新对齐配置。
     */
    val portDraft: String? = null,
    val isSendingTest: Boolean = false,
    /** 就地显示的一句话结果（保存成功 / 校验失败 / 测试邮件成败），空串表示还没有话说。 */
    val message: String = "",
    /** [message] 是不是一条错误——决定它的颜色，也决定用户是否该去改东西。 */
    val messageIsError: Boolean = false,
) {
    /** 端口输入框该显示什么。 */
    val portText: String get() = portDraft ?: config.port.toString()
}

/**
 * 邮件设置页自己的 ViewModel。
 *
 * **故意不复用 `MainViewModel`**：邮件设置存在独立的 prefs 文件里（见 [MailSettingsStore] 的
 * 说明），读写都与 `UiState` 无关，塞进 `MainViewModel` 只会让那个已经三千多行的类再长一截。
 * 它也不碰对话、连接、角色——这一屏需要的全部东西就在这一个文件里。
 */
class EmailSettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = MailSettingsStore(application)

    private val _state = MutableStateFlow(EmailSettingsUiState(config = store.load()))
    val state: StateFlow<EmailSettingsUiState> = _state.asStateFlow()

    /** 界面上的改动先进这里，点「保存」才落盘——避免边打字边写盘、也避免半截配置被别的页面读到。 */
    fun updateConfig(config: EmailConfig) {
        _state.update { it.copy(config = config, portDraft = null, message = "") }
    }

    /**
     * 端口输入框。**只收数字**、最多 5 位，符合配置里 `port` 的取值上限。
     *
     * 拿不出数字（用户正在清空重打）时保留上一份端口值不动作——[EmailSettingsUiState.portDraft]
     * 负责让输入框显示用户敲的原文，两者不冲突。
     */
    fun updatePort(text: String) {
        val digits = text.filter(Char::isDigit).take(5)
        _state.update { current ->
            current.copy(
                config = current.config.copy(port = digits.toIntOrNull() ?: current.config.port),
                portDraft = digits,
                message = "",
            )
        }
    }

    /**
     * 校验并落盘。
     *
     * **只有在总开关打开时才强制校验**：开关关着的时候允许存下一份半截配置
     * （用户可能只是先填服务器地址，回头再补授权码）；开关打开就意味着"要真的发信了"，
     * 这时配置必须完整，否则用户会带着一份"看起来开了、其实发不出去"的设置离开这一页。
     *
     * @return 落盘成功返回 true；被校验拦下返回 false（原因已经在 [EmailSettingsUiState.message]）。
     */
    fun save(): Boolean {
        val config = _state.value.config
        val error = if (config.enabled) EmailConfigValidator.validate(config) else null
        if (error != null) {
            _state.update { it.copy(message = error, messageIsError = true) }
            return false
        }
        store.save(config)
        _state.update {
            it.copy(
                message = if (config.enabled) "已保存，可以发信了" else "已保存（邮件功能当前是关的）",
                messageIsError = false,
            )
        }
        return true
    }

    /**
     * 发一封测试邮件给默认收件人。
     *
     * **先保存再发**：用户在输入框里改完就点测试是最自然的动作，
     * 若只发不存，他会收到一封"用旧配置发出去的"邮件，然后以为新配置没问题。
     */
    fun sendTest() {
        if (!save()) return
        val config = _state.value.config
        if (!config.enabled) {
            _state.update { it.copy(message = "请先打开邮件功能的总开关", messageIsError = true) }
            return
        }
        _state.update { it.copy(isSendingTest = true, message = "正在发送…", messageIsError = false) }
        viewModelScope.launch {
            val result = SmtpClient.send(
                config = config,
                to = config.defaultRecipient,
                subject = "小智测试邮件",
                body = "这是一封测试邮件。\n\n收到它说明 SMTP 配置可用。\n发送时间：${timestamp()}",
            )
            _state.update {
                it.copy(
                    isSendingTest = false,
                    message = result.fold(
                        onSuccess = { "测试邮件已发出，请查收 ${config.defaultRecipient}" },
                        onFailure = { reason -> "发送失败：${reason.message.orEmpty()}" },
                    ),
                    messageIsError = result.isFailure,
                )
            }
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
}
