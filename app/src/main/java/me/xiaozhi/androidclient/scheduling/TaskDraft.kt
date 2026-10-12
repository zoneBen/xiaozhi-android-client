package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.email.EmailConfigValidator
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

/**
 * 表单里那条任务：**用户敲进去的原文**，还没规整成一行库记录。
 *
 * ## 为什么不让界面直接造 [ScheduledTaskEntity]
 *
 * 界面上的输入天生是字符串（"30"、"08:30"、"2026-03-01"），而且**在用户敲到一半时是残缺的**。
 * 若界面边敲边往实体上塞，就必须到处写 `toIntOrNull() ?: 0` 这种兜底——而兜底值一旦落库，
 * 表现就是"任务建好了但一次都不响"（掩码是 0、间隔是 0、月份日期是空）。
 *
 * 所以中间隔一层：界面只负责改这个 data class 的字段（每个字段都是可以随便输的字符串），
 * 点保存时统一走 [validate] + [toEntity]。**校验和落库读的是同一份逻辑**，
 * 不会出现"校验说没问题、存进去却算不出下次触发"这种最难查的分叉。
 *
 * ## 编辑时不能丢的那几个字段
 *
 * [id] / [anchorEpochMs] 之外，`createdAtEpochMs` 与 `lastFireAtEpochMs` **不在这个类里**。
 * 它们是记账字段、不是用户能编辑的东西，[toEntity] 从传进来的旧行上原样搬过去。
 * 把它们塞进表单，等于给"编辑一次任务就重置一次历史"留了个口子。
 */
data class TaskDraft(
    val id: Long = 0L,
    val title: String = "",
    val kind: TaskKind = TaskKind.DAILY,
    /** INTERVAL：分钟数。字符串，因为用户可能正敲到一半（"3" 想改成 "30"）。 */
    val intervalMinutesText: String = DEFAULT_INTERVAL_MINUTES_TEXT,
    /** DAILY / WEEKLY / MONTHLY：`"08:30"`。 */
    val clockText: String = DEFAULT_CLOCK_TEXT,
    /** WEEKLY：勾了哪几天，周一 = [DayOfWeek.MONDAY]。 */
    val weekdays: Set<DayOfWeek> = emptySet(),
    /** MONTHLY：`"1,15"`。 */
    val daysOfMonthText: String = DEFAULT_DAYS_OF_MONTH_TEXT,
    /** ONE_SHOT：`"2026-03-01"`。 */
    val oneShotDateText: String = "",
    val oneShotTimeText: String = DEFAULT_CLOCK_TEXT,
    val action: TaskAction = TaskAction.INJECT_TEXT,
    /** INJECT_TEXT：要注入对话的原文。 */
    val text: String = "",
    /** PHOTO_TO_EMAIL：收件人。**留空是有意义的**——表示用邮件设置里的默认收件人。 */
    val emailTo: String = "",
    val emailSubject: String = "",
    val allowCatchUp: Boolean = true,
    val enabled: Boolean = true,
    /**
     * INTERVAL 的网格锚点。新建时是 0，[toEntity] 会填成"现在"。
     *
     * 编辑时**要传下去**，理由见 [toEntity]：锚点变了等于整套间隔的相位被重置。
     */
    val anchorEpochMs: Long = 0L,
) {
    /** 新建时 `id` 是 0（[ScheduledTaskEntity.id] 的默认值）。 */
    val isNew: Boolean get() = id == 0L

    /**
     * 存下去之前先问这一句，**通过时返回 null**，否则返回一句中文原因。
     *
     * 最后那一问是关键：前面每条都在替具体的输入形态把门，而这一条是把**整条任务**
     * 交给 [NextFireTimeCalculator] 真算一次。任何前面漏掉的非法组合，都会在这里
     * 变成"算不出下次触发"——而这正是用户最不想要、也最查不出来的那种失败
     * （任务列在那里，一次都不响）。
     */
    fun validate(zone: ZoneId, nowEpochMs: Long): String? {
        val trimmedTitle = title.trim()
        if (trimmedTitle.isEmpty()) return "给这条任务起个名字"
        if (trimmedTitle.length > MAX_TITLE_LENGTH) return "名字最多 $MAX_TITLE_LENGTH 个字"

        when (kind) {
            TaskKind.INTERVAL -> {
                val minutes = intervalMinutesText.trim().toIntOrNull() ?: return "间隔要填数字，比如 30"
                if (minutes !in 1..MAX_INTERVAL_MINUTES) {
                    return "间隔要填 1 分钟到 ${MAX_INTERVAL_MINUTES / MINUTES_PER_DAY} 天之间"
                }
            }

            TaskKind.DAILY, TaskKind.WEEKLY, TaskKind.MONTHLY -> {
                if (parseClockTime(clockText) == null) return "时间要填成 08:30 这样"
                if (kind == TaskKind.WEEKLY && weekdays.isEmpty()) return "至少要选一个星期"
                if (kind == TaskKind.MONTHLY && parseDaysOfMonth(daysOfMonthText).isEmpty()) {
                    return "要填每月几号，比如 1,15"
                }
            }

            TaskKind.ONE_SHOT -> {
                if (parseClockTime(oneShotTimeText) == null) return "时间要填成 08:30 这样"
                // 日期与时刻**只要有一个不是空的就得填对**，停用也一样——这里说的不是"能不能用"，
                // 而是"填进去的东西得是个日子"。下面那条时效性才分启停。
                val at = oneShotEpochMs(zone) ?: return "日期要填成 2026-03-01 这样"
                // 等于"现在"也不行：计算器要求严格晚于当下，否则它返回 null，任务一建出来就是死的。
                // 停用的不查：一次性任务用掉之后是"停用但不删"的，时间必然在过去，
                // 此时逼用户先改个未来的时间才让改名，等于把一个历史记录变成不能碰的东西。
                if (enabled && at <= nowEpochMs) return "这个时刻已经过去了，换个未来的时间"
            }
        }

        when (action) {
            TaskAction.INJECT_TEXT -> if (text.isBlank()) return "要给助手说的话不能为空"
            // 留空 = 用默认收件人，是合法的；填了才校验格式。
            TaskAction.PHOTO_TO_EMAIL -> {
                val to = emailTo.trim()
                if (to.isNotEmpty()) EmailConfigValidator.validateRecipient(to)?.let { return it }
            }
        }

        if (enabled && toEntity(null, zone, nowEpochMs).nextFireAtEpochMs == null) {
            return "按这个设置算不出下次触发时间，检查一下周期"
        }
        return null
    }

    /**
     * 变成要落库的那一行。
     *
     * [existing] 传旧行（新建传 null），有两个作用：
     *
     * 1. **保住记账字段**：`createdAtEpochMs` / `lastFireAtEpochMs` 从旧行原样搬过来。
     * 2. **保住间隔的锚点**：改名字、改内容都不该动锚点——一动，
     *    「每 30 分钟」这条任务的相位就漂了（本来是 :00/:30 响，改完变 :07/:37）。
     *    但**改了间隔本身就要重锚**：7 分钟的格子套在半小时的网格上，第一个点会落在
     *    一个谁也说不清的位置。这两件事看着像，语义差很远。
     *
     * 出来的行一定带着算好的 [ScheduledTaskEntity.nextFireAtEpochMs]——调用方拿到就能直接挂闹钟，
     * 不需要自己再算一遍（也不该：那就有两份算法了）。
     */
    fun toEntity(existing: ScheduledTaskEntity?, zone: ZoneId, nowEpochMs: Long): ScheduledTaskEntity {
        val time = parseClockTime(clockText) ?: LocalTime.MIDNIGHT
        val clocked = kind == TaskKind.DAILY || kind == TaskKind.WEEKLY || kind == TaskKind.MONTHLY
        val interval = if (kind == TaskKind.INTERVAL) intervalMinutesText.trim().toIntOrNull() else null
        val anchor = if (existing != null && existing.intervalMinutes == interval) {
            existing.anchorEpochMs
        } else {
            nowEpochMs
        }
        val injectsText = action == TaskAction.INJECT_TEXT
        val sendsPhoto = action == TaskAction.PHOTO_TO_EMAIL

        val base = ScheduledTaskEntity(
            id = id,
            title = title.trim(),
            kind = kind.name,
            intervalMinutes = interval,
            hourOfDay = if (clocked) time.hour else null,
            minuteOfHour = if (clocked) time.minute else null,
            daysOfWeekMask = if (kind == TaskKind.WEEKLY) weekMaskOf(weekdays) else null,
            daysOfMonthCsv = if (kind == TaskKind.MONTHLY) {
                formatDaysOfMonth(parseDaysOfMonth(daysOfMonthText))
            } else {
                null
            },
            fireAtEpochMs = if (kind == TaskKind.ONE_SHOT) oneShotEpochMs(zone) else null,
            anchorEpochMs = anchor,
            action = action.name,
            text = text.trim().takeIf { injectsText && it.isNotEmpty() },
            emailTo = emailTo.trim().takeIf { sendsPhoto && it.isNotEmpty() },
            emailSubject = emailSubject.trim().takeIf { sendsPhoto && it.isNotEmpty() },
            allowCatchUp = allowCatchUp,
            enabled = enabled,
            lastFireAtEpochMs = existing?.lastFireAtEpochMs,
            nextFireAtEpochMs = null,
            createdAtEpochMs = existing?.createdAtEpochMs ?: nowEpochMs,
            updatedAtEpochMs = nowEpochMs,
        )

        // 换了 kind / 改了间隔之后，旧行的字段不该留着——留着的话，将来把 kind 改回去，
        // 会看到几个用户早就不记得自己填过的值。上面每个字段都按 kind 现算，天然做完了这件事，
        // 只有 nextFireAt 要单独算：它得先有实体才能拿到 spec。
        val spec = base.recurrenceSpec() ?: return base
        return base.copy(nextFireAtEpochMs = NextFireTimeCalculator.nextFireAt(spec, nowEpochMs, zone))
    }

    companion object {
        const val MAX_TITLE_LENGTH = 40

        /**
         * 间隔上限 30 天。下界 1 分钟是"再密就没有意义了"——一次完整对话本身要十几秒，
         * 加上退避重试能到 46 秒；上界 30 天则是因为再长就不像"周期任务"而像"一次性"了，
         * 用一次性任务表达更清楚。
         */
        const val MAX_INTERVAL_MINUTES = 30 * MINUTES_PER_DAY
    }
}

/** 新建表单的初值：一次性任务的日期默认填**今天**（用户多半是想定今天或明天）。 */
fun newTaskDraft(zone: ZoneId): TaskDraft =
    TaskDraft(oneShotDateText = LocalDate.now(zone).toString())

/**
 * 旧行 → 表单。**认不出的 kind 退回 DAILY**，而不是抛异常：真遇到更新的版本写下的行，
 * 用户至少还能打开看一眼、改掉，而不是点一下就崩。
 */
fun ScheduledTaskEntity.toDraft(): TaskDraft {
    val parsedKind = parseTaskKind(kind) ?: TaskKind.DAILY
    val time = runCatching { LocalTime.of(hourOfDay ?: 0, minuteOfHour ?: 0) }.getOrNull()
    return TaskDraft(
        id = id,
        title = title,
        kind = parsedKind,
        intervalMinutesText = intervalMinutes?.toString() ?: DEFAULT_INTERVAL_MINUTES_TEXT,
        clockText = time?.let(::formatClockTime) ?: DEFAULT_CLOCK_TEXT,
        weekdays = daysOfWeekFrom(daysOfWeekMask ?: 0).toSet(),
        daysOfMonthText = formatDaysOfMonth(parseDaysOfMonth(daysOfMonthCsv))
            .ifEmpty { DEFAULT_DAYS_OF_MONTH_TEXT },
        oneShotDateText = fireAtEpochMs?.let { epochMsToDateText(it, ZoneId.systemDefault()) }.orEmpty(),
        oneShotTimeText = fireAtEpochMs?.let { epochMsToClockText(it, ZoneId.systemDefault()) }
            ?: DEFAULT_CLOCK_TEXT,
        action = TaskAction.fromName(action) ?: TaskAction.INJECT_TEXT,
        text = text.orEmpty(),
        emailTo = emailTo.orEmpty(),
        emailSubject = emailSubject.orEmpty(),
        allowCatchUp = allowCatchUp,
        enabled = enabled,
        anchorEpochMs = anchorEpochMs,
    )
}

/**
 * 一次性任务的时刻。日期或时刻只要有一个解析不出来就返回 null——**不兜底成"今天此刻"**：
 * 那会让一个日期填错了的任务照样存下去，然后在用户完全没预期的时刻响起来。
 * 返回 null 时 [TaskDraft.validate] 已经先拦过一道了。
 */
private fun TaskDraft.oneShotEpochMs(zone: ZoneId): Long? {
    val time = parseClockTime(oneShotTimeText) ?: return null
    val date = parseDate(oneShotDateText) ?: return null
    return date.atTime(time).atZone(zone).toInstant().toEpochMilli()
}

private fun parseDate(text: String): LocalDate? =
    runCatching { LocalDate.parse(text.trim()) }.getOrNull()

private fun epochMsToDateText(epochMs: Long, zone: ZoneId): String =
    java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate().toString()

private fun epochMsToClockText(epochMs: Long, zone: ZoneId): String =
    formatClockTime(java.time.Instant.ofEpochMilli(epochMs).atZone(zone).toLocalTime())

private const val MINUTES_PER_DAY = 24 * 60
private const val DEFAULT_INTERVAL_MINUTES_TEXT = "60"
private const val DEFAULT_CLOCK_TEXT = "08:30"
private const val DEFAULT_DAYS_OF_MONTH_TEXT = "1"
