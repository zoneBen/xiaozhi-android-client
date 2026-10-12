package me.xiaozhi.androidclient.ui.settings

import android.app.Application
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import me.xiaozhi.androidclient.data.ScheduledTaskRepository
import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.data.db.TaskExecutionEntity
import me.xiaozhi.androidclient.scheduling.NextFireTimeCalculator
import me.xiaozhi.androidclient.scheduling.TaskAlarmScheduler
import me.xiaozhi.androidclient.scheduling.TaskDraft
import me.xiaozhi.androidclient.scheduling.newTaskDraft
import me.xiaozhi.androidclient.scheduling.pendingExecutionOf
import me.xiaozhi.androidclient.scheduling.recurrenceSpec
import me.xiaozhi.androidclient.scheduling.toDraft
import java.time.ZoneId

/** 「定时任务」页的全部状态。[draft] 非空 = 正在新建或编辑，为空 = 看列表。 */
data class ScheduledTasksUiState(
    val tasks: List<ScheduledTaskEntity> = emptyList(),
    /** 正在编辑的那条任务的执行记录。新建时为空。 */
    val executions: List<TaskExecutionEntity> = emptyList(),
    val draft: TaskDraft? = null,
    /** 就地显示的一句话结果，空串表示还没有话说。 */
    val message: String = "",
    val messageIsError: Boolean = false,
    /** 系统有没有给精确闹钟的授权。false 时界面要提示去授权（任务仍会响，只是可能晚几分钟）。 */
    val exactAlarmAllowed: Boolean = true,
    /**
     * 该去申请通知权限了。**一次性信号**：界面处理完必须调 [ScheduledTasksViewModel.onNotificationPromptShown]，
     * 否则每次重组都会再弹一次。
     */
    val askNotificationPermission: Boolean = false,
    val notificationsAllowed: Boolean = true,
) {
    val isEditing: Boolean get() = draft != null
}

/**
 * 定时任务页自己的 ViewModel。
 *
 * ## 它只做两件事，但这两件事必须一起做
 *
 * **写库**和**挂闹钟**。分开做的话，早晚会出现"存进去了但没挂闹钟"——那条任务会安静地
 * 躺在列表里一次都不响，而且**没有任何报错**。所以 [save] 里这两步挨着写，中间不隔任何
 * 可能提前返回的分支。
 *
 * ## 它为什么可以完全绕开 MainViewModel
 *
 * 执行不在这里：到点之后由 receiver 落 `PENDING` 行，MainViewModel 的
 * `ScheduledTaskRunner` 认领并执行。这一屏**只负责"什么时候该响"**，不负责"响了做什么"。
 * 唯一的例外是 [runNow]——它往同一个队列里塞一行，让跑步机自己把它捞走，
 * 而不是在这里直接调用执行逻辑（那样就有两条执行路径了）。
 */
class ScheduledTasksViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ScheduledTaskRepository(application)
    private val scheduler = TaskAlarmScheduler(application)

    private val _state = MutableStateFlow(ScheduledTasksUiState())
    val state: StateFlow<ScheduledTasksUiState> = _state.asStateFlow()

    /** 编辑中的那条任务的记录订阅。换任务/退出编辑时要取消，否则上一个任务的记录会一直写进来。 */
    private var executionJob: Job? = null

    /**
     * 每次用都重新问一次系统，**不要缓存成字段**：时区是可以在设置里随时改的，
     * 缓存住会让"改完时区回来看到的下次时间还是按旧时区算的"。
     */
    private val zone: ZoneId get() = ZoneId.systemDefault()

    init {
        viewModelScope.launch {
            repository.observeTasks().collect { tasks ->
                _state.update { it.copy(tasks = tasks) }
            }
        }
        refreshPermissions()
    }

    /**
     * 重新问一遍系统权限。
     *
     * 界面在 `ON_RESUME` 时也要调一次——用户从"精确闹钟授权"那个系统页面回来，
     * 这一屏不会自己重组，不主动问就会一直挂着"去授权"的提示。
     */
    fun refreshPermissions() {
        _state.update {
            it.copy(
                exactAlarmAllowed = scheduler.canScheduleExactAlarms(),
                notificationsAllowed = notificationsAllowed(),
            )
        }
    }

    // ---------- 进 / 出编辑 ----------

    fun startCreate() {
        observeExecutions(null)
        _state.update { it.copy(draft = newTaskDraft(zone), message = "", messageIsError = false) }
    }

    fun startEdit(task: ScheduledTaskEntity) {
        observeExecutions(task.id)
        _state.update { it.copy(draft = task.toDraft(), message = "", messageIsError = false) }
    }

    /** 表单里任何一处改动都走这里。原文原样收下，规整留给 [TaskDraft.toEntity]。 */
    fun updateDraft(draft: TaskDraft) {
        _state.update { it.copy(draft = draft, message = "", messageIsError = false) }
    }

    fun cancelEdit() {
        observeExecutions(null)
        _state.update { it.copy(draft = null, message = "", messageIsError = false) }
    }

    fun onNotificationPromptShown() {
        _state.update { it.copy(askNotificationPermission = false) }
    }

    // ---------- 落盘 ----------

    /**
     * 校验、落库、挂闹钟。
     *
     * [TaskDraft.toEntity] 出来就带着算好的 `nextFireAtEpochMs`，所以这里不用再算一遍——
     * 算两遍就会有两份结果，迟早对不上。
     */
    fun save() {
        val draft = _state.value.draft ?: return
        val nowMs = System.currentTimeMillis()
        val currentZone = zone

        val problem = draft.validate(currentZone, nowMs)
        if (problem != null) {
            _state.update { it.copy(message = problem, messageIsError = true) }
            return
        }

        viewModelScope.launch {
            // 编辑时要拿旧行：createAt / lastFireAt / 间隔锚点都得从它上面搬。任务在这期间被删了
            // 也不打紧——byId 返回 null 就是"当成新建"，最坏结果是把一次编辑变成一次重建。
            val existing = draft.id.takeIf { it != 0L }?.let { repository.byId(it) }
            val entity = draft.toEntity(existing = existing, zone = currentZone, nowEpochMs = nowMs)
            val taskId = if (draft.isNew) repository.insert(entity) else entity.id.also { repository.update(entity) }

            armOrCancel(taskId, entity.enabled, entity.nextFireAtEpochMs)

            _state.update {
                it.copy(
                    draft = null,
                    executions = emptyList(),
                    message = if (draft.isNew) "已新建，到点会通知你" else "已保存",
                    messageIsError = false,
                    // 通知权限在**保存之后**才问：用户这一刻才真的关心"它到点会不会提醒我"，
                    // 比冷启动一进来就弹窗合适得多。只在新建那次问，编辑时反复问很烦。
                    askNotificationPermission = draft.isNew && !notificationsAllowed(),
                )
            }
            observeExecutions(null)
            refreshPermissions()
        }
    }

    /**
     * 开关一条任务。
     *
     * **打开时一定要重算下一次**，不能直接拿行里存的那个：停用期间那个时刻早就过去了，
     * 拿它去挂闹钟，系统会立刻触发一次——用户会觉得"我一开它就响"。
     */
    fun setEnabled(task: ScheduledTaskEntity, enabled: Boolean) {
        viewModelScope.launch {
            if (!enabled) {
                scheduler.cancel(task.id)
                repository.setEnabled(task.id, false)
                return@launch
            }

            val spec = task.recurrenceSpec()
            val next = spec?.let { NextFireTimeCalculator.nextFireAt(it, System.currentTimeMillis(), zone) }
            if (next == null) {
                // 一次性任务用掉之后再打开就是这种情况。它已经被 [ScheduledTaskRecovery] 停用了，
                // 光打开开关并不会让它重新有下一次——与其静默失败，不如说清楚。
                _state.update {
                    it.copy(message = "这条任务的时间已经过去了，改个时间再打开", messageIsError = true)
                }
                return@launch
            }

            repository.setEnabled(task.id, true)
            repository.setNextFire(task.id, next)
            scheduler.scheduleAt(task.id, next)
        }
    }

    fun delete(task: ScheduledTaskEntity) {
        viewModelScope.launch {
            // 先撤闹钟再删行：反过来的话，删完到点那一下仍会响，而 receiver 找不到任务，
            // 用户会收到一条不知道从哪来的通知。
            scheduler.cancel(task.id)
            repository.delete(task)
            observeExecutions(null)
            _state.update { it.copy(message = "已删除，执行记录还留着", messageIsError = false) }
        }
    }

    /**
     * 立刻做一次，不等闹钟。
     *
     * 走的是**和到点完全同一条路**：往队列里落一行 `PENDING`，由 `ScheduledTaskRunner` 认领。
     * 在这里直接调执行逻辑会多出第二条路径，而那条路径没有忙门控、没有重试、不写记录。
     */
    fun runNow(task: ScheduledTaskEntity) {
        viewModelScope.launch {
            val nowMs = System.currentTimeMillis()
            repository.enqueue(
                pendingExecutionOf(
                    task = task,
                    scheduledAtEpochMs = nowMs,
                    nowEpochMs = nowMs,
                    isCatchUp = false,
                ),
            )
            _state.update {
                it.copy(message = "已排进待办，几秒后看执行记录", messageIsError = false)
            }
        }
    }

    fun clearRecords(taskId: Long) {
        viewModelScope.launch {
            repository.clearExecutionsForTask(taskId)
            _state.update { it.copy(message = "执行记录已清空", messageIsError = false) }
        }
    }

    private fun observeExecutions(taskId: Long?) {
        executionJob?.cancel()
        executionJob = null
        if (taskId == null) {
            _state.update { it.copy(executions = emptyList()) }
            return
        }
        executionJob = viewModelScope.launch {
            repository.observeExecutionsForTask(taskId, RECENT_EXECUTIONS_LIMIT).collect { records ->
                _state.update { it.copy(executions = records) }
            }
        }
    }

    private fun armOrCancel(taskId: Long, enabled: Boolean, nextFireAtEpochMs: Long?) {
        scheduler.cancel(taskId)
        if (enabled && nextFireAtEpochMs != null) scheduler.scheduleAt(taskId, nextFireAtEpochMs)
    }

    private fun notificationsAllowed(): Boolean =
        NotificationManagerCompat.from(getApplication()).areNotificationsEnabled()

    private companion object {
        /** 表单下面那一块最多列几条。再多就该去做全文检索了，不是这一屏的事。 */
        const val RECENT_EXECUTIONS_LIMIT = 20
    }
}
