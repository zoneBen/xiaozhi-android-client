package me.xiaozhi.androidclient.ui.settings

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import me.xiaozhi.androidclient.data.ConversationRepository
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.ui.theme.DangerRed

/**
 * 「对话与历史」：看有多少条记录，以及清掉它们。
 *
 * 界面上**只有清空，没有别的动作**——历史是自动记的，用户唯一会想干预的就是
 * "把它清掉"。所以这一屏的重点是让「清什么」和「清多少」都摆在明面上：
 * 两个数（总数 / 当前角色）＋ 两个按钮，每个按钮下面写清它的影响范围。
 *
 * 「清当前角色」和「清全部」分开放，而不是只给一个"清空历史"再弹框问范围：
 * 常用的是前者（某个角色聊乱了），而后者代价大得多，不该共用一个按钮。
 */
@Composable
internal fun ConversationHistoryScreen(
    state: UiState,
    padding: PaddingValues,
    onClearActiveRole: () -> Unit,
    onClearAll: () -> Unit,
) {
    var pending by remember { mutableStateOf<ClearTarget?>(null) }

    val activeRoleName = state.roleProfiles
        .firstOrNull { it.id == state.activeRoleId }
        ?.displayName
        ?: "当前角色"

    SettingsPageScaffold(padding) {
        SettingsCard {
            SettingsStatus(
                icon = "💬",
                title = "历史记录",
                // 与设置首页那一行用**同一个**函数：两处显示的条数永远一致，
                // 不会出现首页说 128 条、点进来变成 127 条这种对不上的情况。
                subtitle = conversationSettingsSummary(state),
            )
            HorizontalDivider()
            SettingsAction(
                icon = "🧹",
                title = "清空「$activeRoleName」的历史",
                subtitle = "只删这个角色的对话记录，其他角色的不受影响。",
                actionLabel = "清空",
                enabled = state.historyActiveRoleCount > 0,
                danger = true,
                onClick = { pending = ClearTarget.ACTIVE_ROLE },
            )
            HorizontalDivider()
            SettingsAction(
                icon = "🗑️",
                title = "清空全部历史",
                subtitle = "所有角色的对话记录一起删掉，删了没法恢复。",
                actionLabel = "清空",
                enabled = state.historyTotalCount > 0,
                danger = true,
                onClick = { pending = ClearTarget.ALL },
            )
        }

        SettingsCard {
            SettingsStatus(
                icon = "📥",
                title = "进聊天页会加载多少条",
                subtitle = "默认加载最近 ${ConversationRepository.DEFAULT_LOAD_LIMIT} 条。" +
                    "更早的记录仍然存在库里，只是不在聊天页上显示，" +
                    "所以要留着它们的话不必担心——清空按钮才是真的删。",
            )
        }
    }

    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                Text(
                    if (target == ClearTarget.ALL) {
                        "清空全部角色的对话记录？"
                    } else {
                        "清空「$activeRoleName」的对话记录？"
                    },
                )
            },
            text = {
                Text(
                    if (target == ClearTarget.ALL) {
                        "这会把所有角色的对话记录一起删掉，删了没法恢复。"
                    } else {
                        "这会把「$activeRoleName」的对话记录删掉，删了没法恢复；" +
                            "其他角色的记录不受影响。"
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pending = null
                        if (target == ClearTarget.ALL) onClearAll() else onClearActiveRole()
                    },
                ) {
                    Text("清空", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("取消") }
            },
        )
    }
}

/** 待确认的那种清空。用 enum 而不是两个 `Boolean?`，弹框里的文案才不会组合错。 */
private enum class ClearTarget { ACTIVE_ROLE, ALL }
