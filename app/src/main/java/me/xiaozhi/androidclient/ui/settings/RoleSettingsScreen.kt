package me.xiaozhi.androidclient.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File
import me.xiaozhi.androidclient.digitalhuman.UploadTarget
import me.xiaozhi.androidclient.model.DigitalHumanSlot
import me.xiaozhi.androidclient.model.RoleProfile
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.model.hasCompleteDigitalHuman
import me.xiaozhi.androidclient.model.videoPath
import me.xiaozhi.androidclient.ui.chat.AssistantAvatar
import me.xiaozhi.androidclient.ui.theme.DangerRed

/**
 * 角色设置页：角色列表 + 增删改。
 *
 * 从 [me.xiaozhi.androidclient.MainActivity] 原样搬来，逻辑一行没动，只是把外层的
 * 滚动容器换成了 [SettingsPageScaffold]，好让几屏设置共用同一套边距。
 */
@Composable
internal fun RoleSettingsScreen(
    state: UiState,
    padding: PaddingValues,
    onSelectRole: (String) -> Unit,
    onPickRoleAvatar: (String) -> Unit,
    onPickRolePortrait: (String) -> Unit,
    onPickRoleVideo: (String, DigitalHumanSlot) -> Unit,
    onStartUpload: (String, UploadTarget) -> Unit,
    onAddRole: (String, String) -> Unit,
    onUpdateRole: (String, String, String) -> Unit,
    onDeleteRole: (String) -> Unit,
    onClearRoleAvatar: (String) -> Unit,
    onClearRolePortrait: (String) -> Unit,
    onClearRoleVideo: (String, DigitalHumanSlot) -> Unit,
) {
    var editingRoleId by remember { mutableStateOf<String?>(null) }
    var addingRole by remember { mutableStateOf(false) }
    // 待确认删除的角色。删除是**不可撤销**的，而且会连带丢掉这个角色的
    // 唤醒词、立绘、头像和四段形象视频——所以不能一点就走。
    // 2026-09-21 用户模拟测试的原话：「删除和编辑并排、同样大小、同样样式，
    // 只差文字——误触成本不对等」，实测一次点击角色就没了。
    var pendingDelete by remember { mutableStateOf<RoleProfile?>(null) }
    val editingRole = state.roleProfiles.firstOrNull { it.id == editingRoleId }

    SettingsPageScaffold(padding) {
        SettingsCard {
            state.roleProfiles.forEachIndexed { index, role ->
                RoleProfileRow(
                    role = role,
                    active = role.id == state.activeRoleId,
                    onSelect = { onSelectRole(role.id) },
                    onEdit = { editingRoleId = role.id },
                    onDelete = { pendingDelete = role },
                )
                if (index < state.roleProfiles.lastIndex) HorizontalDivider()
            }
            HorizontalDivider()
            TextButton(
                onClick = { addingRole = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text("添加角色")
            }
        }
    }

    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除角色「${target.displayName}」？") },
            text = {
                Text(
                    "这个角色的唤醒词、立绘、头像和形象视频都会一起删掉，删了没法恢复。" +
                        "如果只是暂时不想用它，可以留着不切换。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDeleteRole(target.id)
                    },
                ) {
                    Text("删除", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            },
        )
    }

    if (editingRole != null || addingRole) {
        RoleEditorDialog(
            role = editingRole,
            onPickAvatar = { editingRole?.id?.let(onPickRoleAvatar) },
            onPickPortrait = { editingRole?.id?.let(onPickRolePortrait) },
            onClearAvatar = { editingRole?.id?.let(onClearRoleAvatar) },
            onClearPortrait = { editingRole?.id?.let(onClearRolePortrait) },
            onClearVideo = { slot -> editingRole?.id?.let { onClearRoleVideo(it, slot) } },
            onPickVideo = { slot -> editingRole?.id?.let { onPickRoleVideo(it, slot) } },
            onStartUpload = { target ->
                val targetRole = editingRole
                android.util.Log.d("LanUpload", "RoleEditorDialog click 扫码: target=${target.label}, editingRole=$targetRole")
                targetRole?.id?.let { id ->
                    onStartUpload(id, target)
                } ?: run {
                    android.util.Log.e("LanUpload", "editingRole is null when clicking 扫码")
                }
            },
            onDismiss = {
                editingRoleId = null
                addingRole = false
            },
            onSave = { name, wakeWords ->
                val role = editingRole
                if (role == null) onAddRole(name, wakeWords)
                else onUpdateRole(role.id, name, wakeWords)
                editingRoleId = null
                addingRole = false
            },
        )
    }
}

@Composable
private fun RoleProfileRow(
    role: RoleProfile,
    active: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AssistantAvatar(
                avatarPath = role.avatarPath,
                fallbackText = role.displayName.takeLast(1),
                modifier = Modifier.size(46.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(role.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    text = when {
                        active -> "当前使用 · ${role.wakeWords.joinToString("、")}"
                        role.isBound -> "已绑定 · ${role.wakeWords.joinToString("、")}"
                        else -> "未绑定 · ${role.wakeWords.joinToString("、")}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (active) StatusTag(text = "当前")
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onEdit) { Text("编辑") }
            OutlinedButton(onClick = onDelete) { Text("删除") }
        }
    }
}

@Composable
private fun RoleEditorDialog(
    role: RoleProfile?,
    onPickAvatar: () -> Unit,
    onPickPortrait: () -> Unit,
    onPickVideo: (DigitalHumanSlot) -> Unit,
    onStartUpload: (UploadTarget) -> Unit,
    onClearAvatar: () -> Unit,
    onClearPortrait: () -> Unit,
    onClearVideo: (DigitalHumanSlot) -> Unit,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(role?.id) { mutableStateOf(role?.displayName.orEmpty()) }
    var wakeWords by remember(role?.id) { mutableStateOf(role?.wakeWords?.joinToString(", ").orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (role == null) "添加角色" else "编辑角色") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (role != null) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistantAvatar(
                            avatarPath = role.avatarPath,
                            fallbackText = role.displayName.takeLast(1),
                            modifier = Modifier.size(64.dp),
                        )
                        OutlinedButton(onClick = onPickAvatar) { Text("更换头像") }
                        // 客户没有 ADB、也不方便用设备上的文件选择器，「扫码」才是他们唯一能走通的入口。
                        OutlinedButton(onClick = { onStartUpload(UploadTarget.Avatar) }) { Text("扫码") }
                        if (role.avatarPath.isNotBlank() && File(role.avatarPath).exists()) {
                            TextButton(onClick = onClearAvatar) {
                                Text("清除", color = DangerRed)
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (role.portraitPath.isNotBlank() && File(role.portraitPath).exists()) {
                                "角色立绘 · 已配置"
                            } else {
                                "角色立绘 · 未配置"
                            },
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(onClick = onPickPortrait) { Text("选择立绘") }
                            // 同头像：立绘也必须能扫码传，否则客户端根本用不了这个功能。
                            OutlinedButton(onClick = { onStartUpload(UploadTarget.Portrait) }) { Text("扫码") }
                            if (role.portraitPath.isNotBlank() && File(role.portraitPath).exists()) {
                                TextButton(onClick = onClearPortrait) {
                                    Text("删除", color = DangerRed)
                                }
                            }
                        }
                    }
                    Text(
                        text = "四段形象视频没配齐时，用这张立绘当角色形象",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("名称") },
                    singleLine = true,
                )
                if (role != null) {
                    Text("角色形象视频", fontWeight = FontWeight.SemiBold)
                    DigitalHumanSlot.entries.forEach { slot ->
                        val configured = role.videoPath(slot).isNotBlank() && File(role.videoPath(slot)).exists()
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (configured) "${slot.label} · 已配置" else "${slot.label} · 未配置")
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { onPickVideo(slot) }) { Text("本机") }
                                OutlinedButton(onClick = { onStartUpload(UploadTarget.Video(slot)) }) { Text("扫码") }
                                // 逐段删除。原来只有一个"清空全部"的垃圾桶入口，
                                // 一次把四段视频 + 头像 + 立绘全删掉，用户说"成本太大"。
                                if (configured) {
                                    TextButton(onClick = { onClearVideo(slot) }) {
                                        Text("删除", color = DangerRed)
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        text = if (role.hasCompleteDigitalHuman()) "四段视频已齐全，将启用角色形象" else "需要配置完整四段视频才会启用角色形象",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                OutlinedTextField(
                    value = wakeWords,
                    onValueChange = { wakeWords = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("唤醒词") },
                    supportingText = { Text("多个唤醒词用逗号分隔") },
                    singleLine = false,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onSave(name, wakeWords) },
                enabled = name.isNotBlank() && wakeWords.isNotBlank(),
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
