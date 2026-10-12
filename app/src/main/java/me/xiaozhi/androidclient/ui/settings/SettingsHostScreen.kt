package me.xiaozhi.androidclient.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.viewmodel.compose.viewModel
import me.xiaozhi.androidclient.camera.CameraFacing
import me.xiaozhi.androidclient.digitalhuman.UploadTarget
import me.xiaozhi.androidclient.model.DigitalHumanSlot
import me.xiaozhi.androidclient.model.UiState

/**
 * 设置的总入口：按 [page] 决定渲染哪一屏。
 *
 * 用 `when (page)` 直接分发而**不引入 Navigation-Compose**：这个 App 一共就一个 Activity、
 * 两个顶层界面，为七屏设置再拖一套导航库（外加路由字符串、参数序列化、返回栈兼容）
 * 不划算。分页状态是一个 enum，`rememberSaveable` 直接存得下，进程被杀重建也不丢。
 *
 * **顶栏不在这里**——它归 `XiaozhiScreen` 的 `Scaffold` 管，标题跟着
 * [SettingsPage.title] 走。子页自己再画一个标题就会变成一屏两个标题。
 */
@Composable
internal fun SettingsHostScreen(
    page: SettingsPage,
    padding: PaddingValues,
    onNavigate: (SettingsPage) -> Unit,
    state: UiState,
    onSelectRole: (String) -> Unit,
    onPickRoleAvatar: (String) -> Unit,
    onPickRolePortrait: (String) -> Unit,
    onPickRoleVideo: (String, DigitalHumanSlot) -> Unit,
    onAddRole: (String, String) -> Unit,
    onUpdateRole: (String, String, String) -> Unit,
    onDeleteRole: (String) -> Unit,
    onStartUpload: (String, UploadTarget) -> Unit,
    onClearRoleAvatar: (String) -> Unit,
    onClearRolePortrait: (String) -> Unit,
    onClearRoleVideo: (String, DigitalHumanSlot) -> Unit,
    onCheckForUpdate: () -> Unit,
    onStartUpdate: () -> Unit,
    onDeviceControlEnabledChanged: (Boolean) -> Unit,
    onHardwareControlEnabledChanged: (Boolean) -> Unit,
    onCameraFacingChanged: (CameraFacing) -> Unit,
    onRecheckHardware: () -> Unit,
    onClearActiveRoleHistory: () -> Unit,
    onClearAllHistory: () -> Unit,
) {
    // 邮件设置页的 ViewModel **归这里持有**，不下发给 MainActivity：
    // 它只服务设置首页那一行摘要和邮件页自己，跟 `MainViewModel` 的对话/连接状态毫无关系。
    // 放在这一层而不是邮件页内部，是为了让首页那行摘要和邮件页读到**同一份**配置——
    // 各自 `viewModel()` 出来的会是同一个实例，但让摘要去读另一个 VM 的私有状态更绕。
    val emailViewModel: EmailSettingsViewModel = viewModel()
    val emailState by emailViewModel.state.collectAsState()

    when (page) {
        SettingsPage.HOME -> SettingsHomeScreen(
            state = state,
            emailSummary = emailSettingsSummary(emailState.config),
            padding = padding,
            onNavigate = onNavigate,
        )

        SettingsPage.ROLE -> RoleSettingsScreen(
            state = state,
            padding = padding,
            onSelectRole = onSelectRole,
            onPickRoleAvatar = onPickRoleAvatar,
            onPickRolePortrait = onPickRolePortrait,
            onPickRoleVideo = onPickRoleVideo,
            onStartUpload = onStartUpload,
            onAddRole = onAddRole,
            onUpdateRole = onUpdateRole,
            onDeleteRole = onDeleteRole,
            onClearRoleAvatar = onClearRoleAvatar,
            onClearRolePortrait = onClearRolePortrait,
            onClearRoleVideo = onClearRoleVideo,
        )

        SettingsPage.DEVICE -> DeviceSettingsScreen(
            state = state,
            padding = padding,
            onDeviceControlEnabledChanged = onDeviceControlEnabledChanged,
            onHardwareControlEnabledChanged = onHardwareControlEnabledChanged,
            onCameraFacingChanged = onCameraFacingChanged,
            onRecheckHardware = onRecheckHardware,
        )

        SettingsPage.ABOUT -> AboutScreen(
            state = state,
            padding = padding,
            onCheckForUpdate = onCheckForUpdate,
            onStartUpdate = onStartUpdate,
        )

        SettingsPage.CONVERSATION -> ConversationHistoryScreen(
            state = state,
            padding = padding,
            onClearActiveRole = onClearActiveRoleHistory,
            onClearAll = onClearAllHistory,
        )

        SettingsPage.EMAIL -> EmailSettingsScreen(
            state = emailState,
            padding = padding,
            onConfigChange = emailViewModel::updateConfig,
            onPortChange = emailViewModel::updatePort,
            onSave = { emailViewModel.save() },
            onSendTest = emailViewModel::sendTest,
        )
    }
}

/**
 * 设置首页：每一项一行，右边带一句当前状态。
 *
 * **摊开状态摘要**是这个首页存在的理由——设置从一屏变成七屏之后，用户不该为了确认
 * 「拍照到底关没关」而挨个点进去。摘要由 [roleSettingsSummary] 等纯函数算出。
 */
@Composable
private fun SettingsHomeScreen(
    state: UiState,
    emailSummary: String,
    padding: PaddingValues,
    onNavigate: (SettingsPage) -> Unit,
) {
    SettingsPageScaffold(padding) {
        SettingsCard {
            SettingsNavRow(
                icon = "🧑",
                title = SettingsPage.ROLE.title,
                summary = roleSettingsSummary(state),
                onClick = { onNavigate(SettingsPage.ROLE) },
            )
            HorizontalDivider()
            SettingsNavRow(
                icon = "🔧",
                title = SettingsPage.DEVICE.title,
                summary = deviceSettingsSummary(state),
                onClick = { onNavigate(SettingsPage.DEVICE) },
            )
            HorizontalDivider()
            SettingsNavRow(
                icon = "💬",
                title = SettingsPage.CONVERSATION.title,
                summary = conversationSettingsSummary(state),
                onClick = { onNavigate(SettingsPage.CONVERSATION) },
            )
            HorizontalDivider()
            SettingsNavRow(
                icon = "📮",
                title = SettingsPage.EMAIL.title,
                summary = emailSummary,
                onClick = { onNavigate(SettingsPage.EMAIL) },
            )
            HorizontalDivider()
            SettingsNavRow(
                icon = "ℹ️",
                title = SettingsPage.ABOUT.title,
                summary = aboutSettingsSummary(state),
                onClick = { onNavigate(SettingsPage.ABOUT) },
            )
        }
    }
}
