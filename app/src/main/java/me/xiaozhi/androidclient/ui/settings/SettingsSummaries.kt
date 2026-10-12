package me.xiaozhi.androidclient.ui.settings

import me.xiaozhi.androidclient.model.UiState

/**
 * 设置首页每一行右边那句状态摘要。
 *
 * 抽成纯函数而不是内联在 Compose 里，是因为这几句话各有几个容易写错的分支
 * （当前角色不在列表里、本机没有云台硬件、硬件开着但硬件不存在……），
 * 放在这里就能用 JVM 单测逐条钉住，不必起真机去点。
 */

/**
 * 「角色设置」这一行。
 *
 * 显示的是**当前正在用的角色**，不是列表里的第一个——用户扫一眼首页想知道的是
 * "我现在的助手是谁"，不是"一共有几条记录"。
 */
internal fun roleSettingsSummary(state: UiState): String {
    val count = state.roleProfiles.size
    if (count == 0) return "还没有角色"
    val current = state.roleProfiles.firstOrNull { it.id == state.activeRoleId }?.displayName
    return if (current == null) "共 $count 个角色" else "当前：$current · 共 $count 个角色"
}

/**
 * 「设备控制」这一行。
 *
 * 硬件那一半必须报**实际生效**的状态，而不是用户存的那个值：
 * 开关在本机没有云台时是灰的、实际不生效，摘要若照着存的值念「开」，
 * 用户就会带着一个假印象去点进去，然后发现开关是灰的。
 */
internal fun deviceSettingsSummary(state: UiState): String {
    val camera = if (state.deviceControlEnabled) "拍照与音量 开" else "拍照与音量 关"
    val hardware = when {
        !state.hardwareControlAvailable -> "云台与补光灯 本机不支持"
        state.hardwareControlEnabled -> "云台与补光灯 开"
        else -> "云台与补光灯 关"
    }
    return "$camera · $hardware"
}

/** 「关于与更新」这一行：显示当前装的是哪一版。 */
internal fun aboutSettingsSummary(state: UiState): String =
    "v${state.appVersionName} (Build ${state.appVersionCode})"
