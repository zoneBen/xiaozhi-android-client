package me.xiaozhi.androidclient.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
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
) {
    when (page) {
        SettingsPage.HOME -> SettingsHomeScreen(
            state = state,
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
                icon = "ℹ️",
                title = SettingsPage.ABOUT.title,
                summary = aboutSettingsSummary(state),
                onClick = { onNavigate(SettingsPage.ABOUT) },
            )
        }
    }
}
