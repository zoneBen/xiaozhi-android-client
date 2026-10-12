package me.xiaozhi.androidclient.ui.settings

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.email.EmailConfig
import me.xiaozhi.androidclient.email.EmailConfigValidator
import me.xiaozhi.androidclient.email.EmailSecurity
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.scheduling.nextFireSummary
import java.time.ZoneId

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

/**
 * 「对话与历史」这一行。
 *
 * 条数来自 `UiState.historyTotalCount` / `historyActiveRoleCount`——它们由
 * `MainViewModel.refreshHistoryCounts()` 在**打开设置页时**查一次填入，不是实时值。
 * 所以这里只做读数的格式化，一个查询都不发。
 *
 * 一条都没有时说「还没有对话记录」而不是「共 0 条」：后者读起来像出了错。
 */
internal fun conversationSettingsSummary(state: UiState): String {
    val total = state.historyTotalCount
    if (total <= 0) return "还没有对话记录"
    return "共 $total 条 · 当前角色 ${state.historyActiveRoleCount} 条"
}

/**
 * 「邮件设置」这一行。
 *
 * 三种状态对应三件不同的事，不能合并成"关/开"两态：
 * - 关着：这个能力根本不存在（助手不会被告知会发邮件）；
 * - 开着但没填完：**最该被看见的一种**——用户以为配好了，实际一发就报错，
 *   所以摘要里要明说"还没填完"，把他推回这一页；
 * - 开着且填完：报出发给谁，这是这个能力最常被问到的一件事。
 *
 * 不加密单独点一句：它是唯一一种"能用但不该用"的配置。
 */
internal fun emailSettingsSummary(config: EmailConfig): String {
    if (!config.enabled) return "未启用"
    if (EmailConfigValidator.validate(config) != null) return "已打开，但服务器/账号还没填完"
    val recipient = config.defaultRecipient.trim()
    return if (config.security == EmailSecurity.NONE) {
        "已启用（未加密）· 默认发给 $recipient"
    } else {
        "已启用 · 默认发给 $recipient"
    }
}

/**
 * 「定时任务」这一行。
 *
 * 报**最近一次什么时候响**，而不是"共几条"。用户点进来绝大多数时候是想确认
 * "我设的那个东西还在不在、还响不响"，条数只在"到底建了几条"时才有人关心。
 *
 * 「全都停用了」单独说一句：那种状态下什么都不会发生，而用户最可能的困惑正是
 * "我明明设了"——摘要里点破它，就不用他进去一条条看开关。
 */
internal fun scheduledTasksSummary(
    tasks: List<ScheduledTaskEntity>,
    nowEpochMs: Long,
    zone: ZoneId,
): String {
    if (tasks.isEmpty()) return "还没有定时任务"
    val enabled = tasks.filter { it.enabled }
    if (enabled.isEmpty()) return "共 ${tasks.size} 条 · 全都停用了"
    val soonest = enabled.mapNotNull { it.nextFireAtEpochMs }.minOrNull()
        ?: return "共 ${tasks.size} 条 · ${enabled.size} 条开着，但都没有下一次"
    return "共 ${tasks.size} 条 · 最近一次：${nextFireSummary(soonest, nowEpochMs, zone)}"
}
