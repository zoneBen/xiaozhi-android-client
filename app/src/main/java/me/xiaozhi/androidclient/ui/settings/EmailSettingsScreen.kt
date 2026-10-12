package me.xiaozhi.androidclient.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.input.KeyboardType
import me.xiaozhi.androidclient.email.EmailConfig
import me.xiaozhi.androidclient.email.EmailSecurity
import me.xiaozhi.androidclient.email.applyProviderChoice
import me.xiaozhi.androidclient.email.applySecurityChange
import me.xiaozhi.androidclient.email.mailProviderIndex
import me.xiaozhi.androidclient.email.mailProviderOptions
import me.xiaozhi.androidclient.email.matchingPreset
import me.xiaozhi.androidclient.ui.theme.DangerRed

/**
 * 「邮件设置」页：SMTP 配置 + 发一封测试邮件。
 *
 * 这一屏的**唯一验收标准是"真的能发出一封信"**，所以最后那个"发送测试邮件"不是陪衬——
 * 用户填完几个字段无从判断对错（填错只会得到一句 SSL 异常或超时），
 * 只有真发一封才知道。它顺带替用户做掉"先保存再测试"，见 [EmailSettingsViewModel.sendTest]。
 *
 * 这一屏**不碰 `UiState`**：邮件配置存在独立的 prefs 文件里，与 `MainViewModel.persist()`
 * 的整包写回互不干扰（理由见 `MailSettingsStore` 的文件头注释）。
 *
 * ## 为什么把字段藏起来
 *
 * 初版把九个字段平铺成四张卡，结果是一屏滚不到底、每项都同等醒目。真正每次都要用的只有
 * 四件事：选哪家邮箱、账号、密码、发给谁。所以这里按"什么时候才需要看见"分了三层：
 *
 * 1. **总开关关着 → 下面全都不要出现**。功能没开的时候摆着七个空框，读不出重点，
 *    还会让人以为自己哪里配错了。开关自己的副标题说明了打开后会出现什么。
 * 2. **选了一家预设 → 服务器/端口/加密压缩成一行**（见 [EmailServerCard]）。这三项是预设
 *    给的，用户要的只是"确认一下填的是什么"，不是逐项编辑。
 * 3. **发件人显示名、信任自签证书 → 折进「更多设置」**。前者留空完全不影响发信，
 *    后者只有在服务器用自签证书时才有意义。
 *
 * 剩下的字段**不藏**：藏了就必须让人猜"还要不要再展开点什么才能发信"。
 */
@Composable
internal fun EmailSettingsScreen(
    state: EmailSettingsUiState,
    padding: PaddingValues,
    onConfigChange: (EmailConfig) -> Unit,
    onPortChange: (String) -> Unit,
    onSave: () -> Unit,
    onSendTest: () -> Unit,
) {
    val config = state.config

    /**
     * 用户主动要自己调服务器（点了「改」）。
     *
     * 停在「其他」时不需要它——那时候 [matchingPreset] 本来就是 null，手填区自然会露出来，
     * 于是"选其他"和"点改"两条路通向同一块 UI，不必各写一份。
     */
    var editingServer by rememberSaveable { mutableStateOf(false) }
    var showAdvanced by rememberSaveable { mutableStateOf(false) }

    /**
     * 刚在下拉里选了哪一家。选中那一刻才把这一家的「还得先做什么」顶上来。
     *
     * 常显是初版的做法，真机上那一段会把「常用邮箱」撑成五行——比它下面三个真正的设置项
     * 加起来还占地方，正好是这一屏要治的毛病。而那句话真正有用的时刻就是"我选定了这家、
     * 正要去填密码"，选完看过就收回去；想再看一眼，再选一次同一家即可（选中同一个序号
     * 照样会回调）。
     */
    var justPicked by remember { mutableStateOf<Int?>(null) }

    SettingsPageScaffold(padding) {
        SettingsCard {
            SettingsSwitch(
                icon = "📮",
                title = "邮件功能",
                // 关着时的副标题顺带解释"设置去哪了"：隐藏配置不是东西不见了，
                // 而是开关本身就说清了它下面有什么。
                subtitle = if (config.enabled) {
                    "助手现在可以把照片或文字发到邮箱；关掉它就彻底没有这个能力"
                } else {
                    "打开后下面才会出现发信服务器和账号，填好再去发一封测试邮件"
                },
                checked = config.enabled,
                onCheckedChange = { onConfigChange(config.copy(enabled = it)) },
            )
        }

        if (config.enabled) {
            EmailServerCard(
                config = config,
                portText = state.portText,
                manual = editingServer || config.matchingPreset() == null,
                // 只有"刚选的那一家"才是当前这家时才提示，见 justPicked。
                note = if (justPicked == config.mailProviderIndex()) config.matchingPreset()?.note else null,
                onConfigChange = onConfigChange,
                onPortChange = onPortChange,
                onEditManually = { editingServer = true },
                onPick = { index -> justPicked = index },
            )

            SettingsCard {
                SettingsTextField(
                    icon = "👤",
                    title = "邮箱账号",
                    value = config.username,
                    onValueChange = { onConfigChange(config.copy(username = it)) },
                    placeholder = "someone@qq.com",
                    helper = "完整的邮箱地址，不是昵称",
                    keyboardType = KeyboardType.Email,
                )
                HorizontalDivider()
                SettingsTextField(
                    icon = "🔑",
                    title = "密码 / 授权码",
                    value = config.password,
                    onValueChange = { onConfigChange(config.copy(password = it)) },
                    // 这里刻意**不写死"必须用授权码"**：189 用的就是登录密码。各家到底要哪个，
                    // 交给上面那行按选中的服务商去说，免得两句话在同一个屏幕上互相打脸。
                    // 末句是安全交代——它原来占着单独一张卡，现在并进这一行。
                    helper = "能拿到「授权码」就用它，它随时能在邮箱后台吊销。和本机其它设置一样，" +
                        "它在本机是明文存的。",
                    isPassword = true,
                )
                HorizontalDivider()
                SettingsTextField(
                    icon = "📥",
                    title = "默认收件人",
                    value = config.defaultRecipient,
                    onValueChange = { onConfigChange(config.copy(defaultRecipient = it)) },
                    placeholder = "someone@qq.com",
                    helper = "说「拍张照发我邮箱」时，没指定收件人就发到这里",
                    keyboardType = KeyboardType.Email,
                )
                HorizontalDivider()
                SettingsAction(
                    icon = "⚙️",
                    title = "更多设置",
                    subtitle = "发件人显示名、信任自签证书",
                    actionLabel = if (showAdvanced) "收起" else "展开",
                    onClick = { showAdvanced = !showAdvanced },
                )
                if (showAdvanced) {
                    HorizontalDivider()
                    SettingsTextField(
                        icon = "✍️",
                        title = "发件人显示名",
                        value = config.fromName,
                        onValueChange = { onConfigChange(config.copy(fromName = it)) },
                        placeholder = "留空就用邮箱地址",
                        helper = "收件人看到的发件人名字",
                    )
                    HorizontalDivider()
                    SettingsSwitch(
                        icon = "🛡️",
                        title = "信任自签证书",
                        subtitle = "只有服务器用自签证书、并且你清楚这意味着什么时才打开；打开会降低安全性",
                        checked = config.trustAllCerts,
                        onCheckedChange = { onConfigChange(config.copy(trustAllCerts = it)) },
                    )
                }
            }

            // 结果与两个按钮同一张卡：结果紧贴在按钮上方，点完顺着一行就能读到回话
            // （放到按钮下面，或者另起一张卡，都得滚动才看得见，而这屏不短）。
            SettingsCard {
                if (state.message.isNotBlank()) {
                    SettingsStatus(
                        icon = if (state.messageIsError) "⚠️" else "✅",
                        title = if (state.messageIsError) "没成功" else "好了",
                        subtitle = state.message,
                        subtitleColor = if (state.messageIsError) DangerRed else null,
                    )
                    HorizontalDivider()
                }
                SettingsAction(
                    icon = "💾",
                    title = "保存设置",
                    subtitle = "不保存的话改动只留在这一屏——助手和定时任务读的是存下来的那份",
                    actionLabel = "保存",
                    onClick = onSave,
                )
                HorizontalDivider()
                SettingsAction(
                    icon = "🧪",
                    title = "发送测试邮件",
                    subtitle = "先保存，再给默认收件人发一封。收到才算配好了",
                    actionLabel = if (state.isSendingTest) "发送中…" else "发送",
                    onClick = onSendTest,
                    enabled = !state.isSendingTest,
                )
            }
        }
    }
}

/**
 * 发信服务器那一块：选服务商 + 服务器三件事。
 *
 * 单独拆出来是因为它有两种形态，而两种形态的差异（一行摘要 vs 三行输入）值得写在
 * 一个函数里对照着看，塞进上面那个大函数只会让人在两处之间来回翻。
 *
 * [manual] 为 true 时露出服务器/端口/加密三个输入；为 false 时压成一行摘要 + 「改」。
 * [note] 是要不要显示"这一家还得先做什么"（非 null 才显示），由调用方按 [justPicked] 决定。
 */
@Composable
private fun EmailServerCard(
    config: EmailConfig,
    portText: String,
    manual: Boolean,
    note: String?,
    onConfigChange: (EmailConfig) -> Unit,
    onPortChange: (String) -> Unit,
    onEditManually: () -> Unit,
    onPick: (Int) -> Unit,
) {
    SettingsCard {
        SettingsDropdown(
            icon = "⚡",
            title = "常用邮箱",
            // 三种副标题按"用户此刻卡在哪"排：刚选完一家 → 说这一家的额外要求（[note]）；
            // 已经配好一家 → 说明为什么下面没有服务器输入框（省得人翻找）；
            // 停在「其他」→ 指路到下面手填。都不是同一句话凑出来的，所以逐条写。
            subtitle = note
                ?: if (config.matchingPreset() != null) {
                    "服务器、端口、加密方式都跟着它填好了，不用再管"
                } else {
                    "选「其他」就自己填服务器；账号和密码在下面填"
                },
            options = mailProviderOptions(),
            selectedIndex = config.mailProviderIndex(),
            onSelect = { index ->
                onPick(index)
                onConfigChange(config.applyProviderChoice(index))
            },
        )
        HorizontalDivider()
        if (manual) {
            SettingsTextField(
                icon = "🌐",
                title = "SMTP 服务器",
                value = config.host,
                onValueChange = { onConfigChange(config.copy(host = it)) },
                placeholder = "smtp.example.com",
            )
            HorizontalDivider()
            SettingsTextField(
                icon = "🔌",
                title = "端口",
                value = portText,
                onValueChange = onPortChange,
                placeholder = "465",
                helper = "自建服务器不在默认端口上时才需要改",
                keyboardType = KeyboardType.Number,
            )
            HorizontalDivider()
            SettingsChoice(
                icon = "🔒",
                title = "加密方式",
                subtitle = config.security.hint,
                options = EmailSecurity.entries.map { it.label to (it == config.security) },
                onSelect = { index ->
                    onConfigChange(applySecurityChange(config, EmailSecurity.entries[index]))
                },
            )
        } else {
            // 预设已经把这三项填好了，用户这里要的是确认而不是编辑。
            SettingsAction(
                icon = "🌐",
                title = "发信服务器",
                subtitle = "${config.host} · ${config.port} · ${config.security.label}",
                actionLabel = "改",
                onClick = onEditManually,
            )
        }
    }
}
