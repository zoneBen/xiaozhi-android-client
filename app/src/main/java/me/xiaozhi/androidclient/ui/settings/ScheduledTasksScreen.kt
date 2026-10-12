package me.xiaozhi.androidclient.ui.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.scheduling.TaskAction
import me.xiaozhi.androidclient.scheduling.TaskDraft
import me.xiaozhi.androidclient.scheduling.TaskKind
import me.xiaozhi.androidclient.scheduling.TaskOutcome
import me.xiaozhi.androidclient.scheduling.TaskRunStatus
import me.xiaozhi.androidclient.scheduling.executionSummary
import me.xiaozhi.androidclient.scheduling.recurrenceSummary
import me.xiaozhi.androidclient.scheduling.recurrenceSpec
import me.xiaozhi.androidclient.scheduling.taskRowSummary
import me.xiaozhi.androidclient.ui.theme.DangerRed
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 「定时任务」：列表 → 新建/编辑表单 → 这条任务的执行记录。
 *
 * ## 三层其实只有两个状态
 *
 * [ScheduledTasksUiState.draft] 为空是列表，非空是表单；执行记录**长在表单里**。
 * 不给它单独第三层，是因为记录永远只对一个任务有意义（"这条到底响没响过"），
 * 拆成独立一页就得先问"看谁的"，多一步还多一个"我现在在看谁"的状态。
 *
 * ## 这一屏唯一一次"主动打扰用户"
 *
 * 两处系统权限——精确闹钟和通知——都做成**按需出现的一行提示**，而不是一进来就弹窗：
 * 没有任务时它们根本不该出现（用户还不知道这个功能是什么，先给他看两个"去授权"），
 * 有了任务（或保存了第一条）之后才出现，那时他正好关心"它到点会不会提醒我"。
 */
@Composable
internal fun ScheduledTasksScreen(
    state: ScheduledTasksUiState,
    padding: PaddingValues,
    onStartCreate: () -> Unit,
    onStartEdit: (ScheduledTaskEntity) -> Unit,
    onDraftChange: (TaskDraft) -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
    onDelete: (ScheduledTaskEntity) -> Unit,
    onToggleEnabled: (ScheduledTaskEntity, Boolean) -> Unit,
    onRunNow: (ScheduledTaskEntity) -> Unit,
    onClearRecords: (Long) -> Unit,
    onRefreshPermissions: () -> Unit,
    onNotificationPromptShown: () -> Unit,
) {
    val context = LocalContext.current

    // 回到前台就重新问一次权限：用户可能是刚从"精确闹钟授权"那个系统页面回来的，
    // 这一屏不会自己重组，不主动问就会一直挂着一句已经过时的"去授权"。
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) onRefreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notificationLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { onRefreshPermissions() }

    LaunchedEffect(state.askNotificationPermission) {
        if (!state.askNotificationPermission) return@LaunchedEffect
        // 33 以下没有这条运行时权限，直接销号，否则这个信号会一直挂着。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        onNotificationPromptShown()
    }

    // 「下次：3 分钟后」这种相对时间放着不动会自己变旧。30 秒重算一次足够——下面最短的
    // 粒度是"不到 1 分钟"，再密也只是白重组。
    val nowMs by produceState(initialValue = System.currentTimeMillis()) {
        while (true) {
            delay(TICK_MS)
            value = System.currentTimeMillis()
        }
    }
    val zone = remember { ZoneId.systemDefault() }

    val draft = state.draft
    if (draft == null) {
        TaskListView(
            state = state,
            nowMs = nowMs,
            zone = zone,
            padding = padding,
            context = context,
            onStartCreate = onStartCreate,
            onStartEdit = onStartEdit,
            onToggleEnabled = onToggleEnabled,
        )
    } else {
        TaskFormView(
            state = state,
            draft = draft,
            nowMs = nowMs,
            zone = zone,
            padding = padding,
            onDraftChange = onDraftChange,
            onCancelEdit = onCancelEdit,
            onSave = onSave,
            onDelete = onDelete,
            onRunNow = onRunNow,
            onClearRecords = onClearRecords,
        )
    }
}

// ---------- 列表 ----------

@Composable
private fun TaskListView(
    state: ScheduledTasksUiState,
    nowMs: Long,
    zone: ZoneId,
    padding: PaddingValues,
    context: Context,
    onStartCreate: () -> Unit,
    onStartEdit: (ScheduledTaskEntity) -> Unit,
    onToggleEnabled: (ScheduledTaskEntity, Boolean) -> Unit,
) {
    SettingsPageScaffold(padding) {
        // 一条任务都没有时不提权限：用户还不知道这个功能是什么，先给他看两个"去授权"
        // 只会让他以为这是个要折腾的东西。有了任务之后才出现，那时他正好关心
        // "它到点会不会提醒我"。
        if (state.tasks.isNotEmpty()) PermissionCard(state, context)

        SettingsCard {
            if (state.tasks.isEmpty()) {
                SettingsStatus(
                    icon = "⏰",
                    title = "还没有定时任务",
                    subtitle = "建一条试试：到点让助手说句话，或者拍张照片发到邮箱。" +
                        "周期任务每响一次会自动排下一次，手机关机也会在开机后补上。",
                )
            } else {
                state.tasks.forEachIndexed { index, task ->
                    if (index > 0) HorizontalDivider()
                    // 整行可点 = 进编辑，右边那个开关 = 立刻停用/启用。开关自己会吃掉点击，
                    // 所以两种手势不会互相误触。
                    //
                    // "临时关掉它"是列表上最常用的动作，藏在"点进去 → 翻开关 → 保存"后面
                    // 就成三步了；而这一行的摘要本来就写着"已停用"，状态是看得见的。
                    Box(modifier = Modifier.clickable { onStartEdit(task) }) {
                        SettingsSwitch(
                            icon = if (task.enabled) "⏰" else "⏸️",
                            title = task.title,
                            subtitle = taskRowSummary(task, nowMs, zone),
                            checked = task.enabled,
                            onCheckedChange = { onToggleEnabled(task, it) },
                        )
                    }
                }
            }
        }

        if (state.tasks.isNotEmpty()) {
            SettingsCard {
                SettingsStatus(
                    icon = "🔔",
                    title = "任务到点会做什么",
                    subtitle = "先在这台设备的对话里说一句「【定时任务】名字」——用户得知道它为什么突然开口——" +
                        "然后把那句话走一遍完整对话，助手会照常朗读回复。" +
                        "拍照片那一类不打扰对话，安静拍完就发信。",
                )
            }
        }

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
                icon = "➕",
                title = "新建定时任务",
                subtitle = if (state.tasks.isEmpty()) {
                    "选个周期，再选到点做什么"
                } else {
                    "共 ${state.tasks.size} 条，其中 ${state.tasks.count { it.enabled }} 条开着"
                },
                actionLabel = "新建",
                onClick = onStartCreate,
            )
        }
    }
}

// ---------- 表单 ----------

@Composable
private fun TaskFormView(
    state: ScheduledTasksUiState,
    draft: TaskDraft,
    nowMs: Long,
    zone: ZoneId,
    padding: PaddingValues,
    onDraftChange: (TaskDraft) -> Unit,
    onCancelEdit: () -> Unit,
    onSave: () -> Unit,
    onDelete: (ScheduledTaskEntity) -> Unit,
    onRunNow: (ScheduledTaskEntity) -> Unit,
    onClearRecords: (Long) -> Unit,
) {
    var pendingDelete by remember { mutableStateOf<ScheduledTaskEntity?>(null) }
    var pendingClearRecords by remember { mutableStateOf(false) }

    // 实时预览：只显示"周期"这一半，不显示"下次"。下次要等存下来才作数，
    // 在表单里报一个还没生效的时间只会让人以为已经建好了。
    val previewSpec = draft.toEntity(existing = null, zone = zone, nowEpochMs = nowMs).recurrenceSpec()

    SettingsPageScaffold(padding) {
        SettingsCard {
            SettingsStatus(
                icon = if (draft.enabled) "⏰" else "⏸️",
                title = draft.action.label,
                subtitle = previewSpec?.let { recurrenceSummary(it, zone) } ?: "周期还认不出来",
            )
        }

        SettingsCard {
            SettingsTextField(
                icon = "✏️",
                title = "名字",
                value = draft.title,
                onValueChange = { onDraftChange(draft.copy(title = it)) },
                placeholder = "例如：提醒吃药",
                helper = "执行时会在对话里显示成「【定时任务】" +
                    "${draft.title.ifBlank { "名字" }}」，所以写清楚一点",
            )
            HorizontalDivider()
            SettingsDropdown(
                icon = "🔁",
                title = "重复方式",
                subtitle = "",
                options = TaskKind.entries.map(::kindLabel),
                selectedIndex = TaskKind.entries.indexOf(draft.kind),
                onSelect = { index -> onDraftChange(draft.copy(kind = TaskKind.entries[index])) },
            )

            when (draft.kind) {
                TaskKind.INTERVAL -> {
                    HorizontalDivider()
                    SettingsTextField(
                        icon = "⏱️",
                        title = "每隔多少分钟",
                        value = draft.intervalMinutesText,
                        onValueChange = { onDraftChange(draft.copy(intervalMinutesText = it)) },
                        placeholder = "60",
                        helper = "从保存这一刻开始算。填 60 就是整点响；" +
                            "最长 ${TaskDraft.MAX_INTERVAL_MINUTES / (24 * 60)} 天",
                        keyboardType = KeyboardType.Number,
                    )
                }

                TaskKind.DAILY, TaskKind.WEEKLY, TaskKind.MONTHLY -> {
                    HorizontalDivider()
                    SettingsTextField(
                        icon = "🕗",
                        title = "几点",
                        value = draft.clockText,
                        onValueChange = { onDraftChange(draft.copy(clockText = it)) },
                        placeholder = "08:30",
                        helper = "24 小时制，填成 08:30 这样",
                    )
                }

                TaskKind.ONE_SHOT -> Unit
            }

            if (draft.kind == TaskKind.WEEKLY) {
                HorizontalDivider()
                SettingsMultiChoice(
                    icon = "📅",
                    title = "哪几天",
                    subtitle = "至少选一天。七天才填了「每周一三五」这样的说法",
                    options = WEEKDAYS.mapIndexed { index, day ->
                        WEEKDAY_LABELS[index] to (day in draft.weekdays)
                    },
                    onToggle = { index ->
                        val day = WEEKDAYS[index]
                        val next = if (day in draft.weekdays) draft.weekdays - day else draft.weekdays + day
                        onDraftChange(draft.copy(weekdays = next))
                    },
                )
            }

            if (draft.kind == TaskKind.MONTHLY) {
                HorizontalDivider()
                SettingsTextField(
                    icon = "📆",
                    title = "每月几号",
                    value = draft.daysOfMonthText,
                    onValueChange = { onDraftChange(draft.copy(daysOfMonthText = it)) },
                    placeholder = "1,15",
                    helper = "用逗号分开可以填多个。填 29~31 时，没有这天的月份会顺延到月底，不会跳过",
                )
            }

            if (draft.kind == TaskKind.ONE_SHOT) {
                HorizontalDivider()
                SettingsTextField(
                    icon = "📆",
                    title = "哪一天",
                    value = draft.oneShotDateText,
                    onValueChange = { onDraftChange(draft.copy(oneShotDateText = it)) },
                    placeholder = "2026-03-01",
                    helper = "填成 2026-03-01 这样。只响这一次，响过之后任务会自己停用（不删）",
                )
                HorizontalDivider()
                SettingsTextField(
                    icon = "🕗",
                    title = "几点",
                    value = draft.oneShotTimeText,
                    onValueChange = { onDraftChange(draft.copy(oneShotTimeText = it)) },
                    placeholder = "08:30",
                )
            }
        }

        SettingsCard {
            SettingsChoice(
                icon = "🎯",
                title = "到点做什么",
                subtitle = "「拍张照片发邮箱」不往对话里插话，你正在说话的时候也照拍不误",
                options = TaskAction.entries.map { it.label to (it == draft.action) },
                onSelect = { index -> onDraftChange(draft.copy(action = TaskAction.entries[index])) },
            )

            if (draft.action == TaskAction.INJECT_TEXT) {
                HorizontalDivider()
                SettingsTextField(
                    icon = "💬",
                    title = "要对助手说的话",
                    value = draft.text,
                    onValueChange = { onDraftChange(draft.copy(text = it)) },
                    placeholder = "例如：提醒我该吃药了",
                    helper = "这句话会当成你说的发出去，助手照常回答并朗读。" +
                        "设备正忙时会退避重试，试满 6 次还没插进去就记为失败",
                )
            } else {
                HorizontalDivider()
                SettingsTextField(
                    icon = "📥",
                    title = "发到哪个邮箱",
                    value = draft.emailTo,
                    onValueChange = { onDraftChange(draft.copy(emailTo = it)) },
                    placeholder = "留空就用邮件设置里的默认收件人",
                    keyboardType = KeyboardType.Email,
                )
                HorizontalDivider()
                SettingsTextField(
                    icon = "🏷️",
                    title = "邮件主题",
                    value = draft.emailSubject,
                    onValueChange = { onDraftChange(draft.copy(emailSubject = it)) },
                    placeholder = "留空就用任务名字「${draft.title.ifBlank { "…" }}」",
                )
            }
        }

        SettingsCard {
            SettingsSwitch(
                icon = "🔁",
                title = "允许补跑",
                subtitle = "关机或休眠期间错过了这一次，开机后补做一遍。" +
                    "只补最近这一回，不会把错过的十几次挨个补上",
                checked = draft.allowCatchUp,
                onCheckedChange = { onDraftChange(draft.copy(allowCatchUp = it)) },
            )
            HorizontalDivider()
            SettingsSwitch(
                icon = "✅",
                title = "启用",
                subtitle = "关掉只是不再响，任务和执行记录都留着",
                checked = draft.enabled,
                onCheckedChange = { onDraftChange(draft.copy(enabled = it)) },
            )
        }

        // 保存与回话同一张卡：结果紧贴在按钮上方，点完顺着一行就能读到（这屏不短）。
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
                title = if (draft.isNew) "新建这条任务" else "保存改动",
                subtitle = if (draft.isNew) {
                    "保存之后它就交给系统闹钟了，这个页面关掉也照响"
                } else {
                    "改完不保存的话，到点还是按老样子响"
                },
                actionLabel = "保存",
                onClick = onSave,
            )
            HorizontalDivider()
            SettingsAction(
                icon = "↩️",
                title = "放弃改动",
                subtitle = "这一屏上改的东西都不会保存",
                actionLabel = "返回",
                onClick = onCancelEdit,
            )
        }

        // 执行记录只在编辑已有任务时才有意义：还没存过的任务一次都没跑过。
        state.editingTask?.let { task ->
            SettingsCard {
                SettingsAction(
                    icon = "▶️",
                    title = "立刻做一次",
                    subtitle = "不等闹钟，现在就排进待办。走的是和到点完全同一条路，" +
                        "所以忙门控、重试、执行记录都算数",
                    actionLabel = "执行",
                    onClick = { onRunNow(task) },
                )
                HorizontalDivider()
                SettingsAction(
                    icon = "🧹",
                    title = "清空这条任务的执行记录",
                    subtitle = "只清记录，不影响任务本身",
                    actionLabel = "清空",
                    enabled = state.executions.isNotEmpty(),
                    danger = true,
                    onClick = { pendingClearRecords = true },
                )
                HorizontalDivider()
                SettingsAction(
                    icon = "🗑️",
                    title = "删除这条任务",
                    subtitle = "任务没了，执行记录还留着——「它当时响没响」仍然查得到",
                    actionLabel = "删除",
                    danger = true,
                    onClick = { pendingDelete = task },
                )
            }

            SettingsCard {
                SettingsStatus(
                    icon = "📜",
                    title = "执行记录",
                    subtitle = if (state.executions.isEmpty()) {
                        "还没执行过。到点之后这里会记下每一次：什么时候、成没成、为什么没成"
                    } else {
                        "最近 ${state.executions.size} 次"
                    },
                )
                state.executions.forEach { execution ->
                    HorizontalDivider()
                    SettingsStatus(
                        icon = executionIcon(execution.status, execution.outcome),
                        title = fireAtSummary(execution.firedAtEpochMs ?: execution.scheduledAtEpochMs, zone),
                        subtitle = executionSummary(execution),
                        subtitleColor = if (TaskRunStatus.fromName(execution.status) == TaskRunStatus.FAILED) {
                            DangerRed
                        } else {
                            null
                        },
                    )
                }
            }
        }
    }

    pendingDelete?.let { task ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除「${task.title}」？") },
            text = { Text("删了之后不会再响，也找不回来。已经产生的执行记录会留着。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDelete = null
                        onDelete(task)
                        onCancelEdit()
                    },
                ) {
                    Text("删除", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text("取消") } },
        )
    }

    if (pendingClearRecords) {
        val taskId = state.editingTask?.id
        AlertDialog(
            onDismissRequest = { pendingClearRecords = false },
            title = { Text("清空执行记录？") },
            text = { Text("记录删了没法恢复，任务本身不受影响。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingClearRecords = false
                        if (taskId != null) onClearRecords(taskId)
                    },
                ) {
                    Text("清空", color = DangerRed, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { pendingClearRecords = false }) { Text("取消") } },
        )
    }
}

// ---------- 权限 ----------

/**
 * 两张权限提示。
 *
 * 都写成"说明 + 一个按钮"而不是弹窗：它们不是一次性的关卡，而是**一直存在的状态**
 * （用户随时可以去系统设置里收回），而用户需要能随时看见自己现在处于哪种状态。
 */
@Composable
private fun PermissionCard(state: ScheduledTasksUiState, context: Context) {
    if (state.exactAlarmAllowed && state.notificationsAllowed) return

    SettingsCard {
        if (!state.exactAlarmAllowed) {
            SettingsAction(
                icon = "⏰",
                title = "闹钟不能精确到分钟",
                subtitle = "系统没给这个应用精确闹钟的权限，任务到点会晚几分钟到十几分钟不等。" +
                    "去系统设置里允许「闹钟和提醒」就正常了",
                actionLabel = "去授权",
                onClick = { openExactAlarmSettings(context) },
            )
        }
        if (!state.exactAlarmAllowed && !state.notificationsAllowed) {
            HorizontalDivider()
        }
        if (!state.notificationsAllowed) {
            SettingsAction(
                icon = "🔕",
                title = "通知权限没开",
                subtitle = "任务到点会尝试直接把界面拉起来；被系统拦下时，" +
                    "那条通知是唯一的兜底入口。建议打开",
                actionLabel = "去开启",
                onClick = { openNotificationSettings(context) },
            )
        }
    }
}

/** 编辑中的那条任务，从列表里按 id 取回。取不到（刚被删）时返回 null，那一块整体不渲染。 */
private val ScheduledTasksUiState.editingTask: ScheduledTaskEntity?
    get() = draft?.id?.takeIf { it != 0L }?.let { id -> tasks.firstOrNull { it.id == id } }

private fun kindLabel(kind: TaskKind): String = when (kind) {
    TaskKind.INTERVAL -> "每隔一段时间"
    TaskKind.DAILY -> "每天"
    TaskKind.WEEKLY -> "每周"
    TaskKind.MONTHLY -> "每月"
    TaskKind.ONE_SHOT -> "只一次"
}

/**
 * 记录行左边那个表情：一眼分出"成了""没成""还在排队"。
 *
 * 判定走枚举的 [TaskRunStatus.fromName] / [TaskOutcome.fromName]，不比对字符串字面量——
 * 写错一个字的字面量不会编译失败，只会让每一行都悄悄掉进 `else` 那个"成功"里。
 */
private fun executionIcon(status: String, outcome: String?): String {
    val run = TaskRunStatus.fromName(status)
    val result = TaskOutcome.fromName(outcome)
    return when {
        run == TaskRunStatus.PENDING -> "⏳"
        run == TaskRunStatus.RUNNING -> "▶️"
        run == TaskRunStatus.FAILED -> "⚠️"
        result == TaskOutcome.SKIPPED_MISSED || result == TaskOutcome.SKIPPED_BUSY -> "🚫"
        else -> "✅"
    }
}

private fun fireAtSummary(epochMs: Long, zone: ZoneId): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(FIRE_AT_FORMAT)

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    // 有的 ROM 没这个页面，startActivity 会抛 —— 那就是"没得点"，本来也不该崩。
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM)
                .setData(Uri.fromParts("package", context.packageName, null)),
        )
    }
}

private fun openNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}

private val WEEKDAYS = listOf(
    DayOfWeek.MONDAY,
    DayOfWeek.TUESDAY,
    DayOfWeek.WEDNESDAY,
    DayOfWeek.THURSDAY,
    DayOfWeek.FRIDAY,
    DayOfWeek.SATURDAY,
    DayOfWeek.SUNDAY,
)

/** 「一」「二」……单字，和列表里的「每周一三五」用同一套说法。 */
private val WEEKDAY_LABELS = listOf("一", "二", "三", "四", "五", "六", "日")

private val FIRE_AT_FORMAT = DateTimeFormatter.ofPattern("M 月 d 日 HH:mm")

private const val TICK_MS = 30_000L
