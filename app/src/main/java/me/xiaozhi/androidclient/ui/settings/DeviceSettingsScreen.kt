package me.xiaozhi.androidclient.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import me.xiaozhi.androidclient.camera.CameraFacing
import me.xiaozhi.androidclient.model.UiState

/**
 * 设备控制页：本机硬件的开关与自检结论。
 *
 * 从 [me.xiaozhi.androidclient.MainActivity] 原样搬来，只多了外层的 [SettingsPageScaffold]。
 *
 * ## 为什么是两个开关（2026-10-12 拆分）
 *
 * 原先只有一个总开关，把"硬件事实"和"用户意愿"混在了一起。实测暴露两件事：
 *
 *  1. 在没认出云台和补光灯的机器上（比如现在这台 OnePlus），它照样显示为**打开**，
 *     可实际它只等于"能调音量"——**开关在撒谎**；
 *  2. 用户为了关掉云台，得连媒体音量一起牺牲，而音量任何 Android 设备都有。
 *
 * 所以按「通用 / 硬件强相关」分成两组，并让硬件那个开关在**本机没这个硬件时置灰**——
 * 留着能点等于骗用户，打开也没东西可控制。
 *
 * ## 为什么要把自检结果摆到界面上
 *
 * 云台和补光灯都是靠厂商挂在 `PowerManager` 上的扩展方法反射调用的（见
 * `BodyGuargzController`），这台机器认不认只能实测。把结论写在这儿，现场的人一眼就能
 * 分清"是功能没开"还是"这台机器压根没有这个硬件"——否则只会看到"叫它转它不转"，
 * 然后以为 App 坏了。
 *
 * **后台上报不在这里**：它是纯旁路，失败不影响任何本地功能，给它一块界面只会让人
 * 以为"这里有东西要管"。基址是编译期写死的，见 `BackendConfig`。
 */
@Composable
internal fun DeviceSettingsScreen(
    state: UiState,
    padding: PaddingValues,
    onDeviceControlEnabledChanged: (Boolean) -> Unit,
    onHardwareControlEnabledChanged: (Boolean) -> Unit,
    onCameraFacingChanged: (CameraFacing) -> Unit,
    onRecheckHardware: () -> Unit,
) {
    SettingsPageScaffold(padding) {
        SettingsCard {
            SettingsSwitch(
                icon = "📷",
                title = "拍照与音量",
                subtitle = "允许小智拍照看画面、调节媒体音量，比如「看看这是什么」「声音小一点」。" +
                    "关掉后小智不能拍照，定时监督也不再拍照核对，只会照常提醒你。",
                checked = state.deviceControlEnabled,
                onCheckedChange = onDeviceControlEnabledChanged,
            )

            // 前置 / 后置。本机只有一个朝向时不显示——没得选的选择器只是在浪费用户的时间。
            if (state.cameraFacingChoices.size > 1) {
                HorizontalDivider()
                SettingsChoice(
                    icon = "🔄",
                    title = "用哪个摄像头",
                    subtitle = "本机有${state.cameraFacingChoices.joinToString("和") { it.label }}摄像头。" +
                        "接上 USB 摄像头时优先用它。",
                    options = state.cameraFacingChoices.map { it.label to (it == state.cameraFacing) },
                    onSelect = { index -> onCameraFacingChanged(state.cameraFacingChoices[index]) },
                )
            }

            HorizontalDivider()
            SettingsSwitch(
                icon = "🕹️",
                title = "云台与补光灯",
                // 说「没认出」而不是「没有」：这里证的是**厂商标商接口在不在**，
                // 不是灯和电机在不在——一台机器可能接口在、硬件不在。写「没有」是把话说满了。
                subtitle = if (state.hardwareControlAvailable) {
                    "允许小智转动摄像头云台、开关补光灯和红外灯，比如「左转」「打开灯光」。" +
                        "关掉后会立刻关灯并把云台停住。"
                } else {
                    "本机没认出云台和补光灯，这个开关在这里用不上。"
                },
                // 没硬件时显示的是**实际生效状态**（关），不是用户存的那个值（开）；
                // 存的值不动，将来插到真有云台的板子上照样是开的。
                checked = state.hardwareControlEnabled && state.hardwareControlAvailable,
                onCheckedChange = onHardwareControlEnabledChanged,
                enabled = state.hardwareControlAvailable,
            )

            HorizontalDivider()
            SettingsStatus(
                icon = if (state.deviceControlEnabled || state.hardwareControlEnabled) "🔧" else "🚫",
                title = "本机硬件自检",
                subtitle = state.deviceControlStatus.ifBlank { "正在检测本机的云台、补光灯和音量…" },
            )

            // 自检结论会缓存 24 小时（见 HardwareProbeRepository），所以必须给一个当场重测的出口，
            // 否则用户插上 USB 摄像头、或者换了块板子之后，看到的一直是昨天那个结论。
            HorizontalDivider()
            SettingsAction(
                icon = "🧪",
                title = "重新自检",
                // 两句之间必须断开：状态串本身没有句号（形如「上次自检：3 小时前」），
                // 紧跟着接下一句会读成「3 小时前换过硬件」，正好把意思读反。
                subtitle = state.hardwareProbeStatus.ifBlank { "本机还没做过硬件自检。" } +
                    "\n换过硬件、或者结果看着不对时点一下重测。",
                actionLabel = "重测",
                onClick = onRecheckHardware,
            )
        }
    }
}
