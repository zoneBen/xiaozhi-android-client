package me.xiaozhi.androidclient.ui.settings

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.scheduling.TaskDraft
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「那两张权限提示什么时候摆出来」这一条策略。
 *
 * 它的两个分支都出过事：一开始只在列表页摆，于是**只要编辑表单还开着，那张卡就再也见不到**
 * （草稿存在 ViewModel 里，会一直开着）。用户按提示去系统设置授权，回来发现根本没那张卡，
 * 只能怀疑是自己找错了地方。
 */
class PermissionHintsVisibleTest {

    @Test
    fun `列表页一条任务都没有时不摆`() {
        // 还不知道这个功能是什么的人，先看到两个「去授权」只会以为这东西要折腾。
        assertFalse(permissionHintsVisible(ScheduledTasksUiState(tasks = emptyList())))
    }

    @Test
    fun `列表页有任务时摆`() {
        assertTrue(permissionHintsVisible(ScheduledTasksUiState(tasks = listOf(task()))))
    }

    @Test
    fun `表单里一条任务都没有也摆`() {
        // 第一条任务正是在表单里建出来的，而"到点会不会准"就是这一刻要回答的问题。
        val state = ScheduledTasksUiState(tasks = emptyList(), draft = TaskDraft())
        assertTrue(permissionHintsVisible(state))
    }

    @Test
    fun `表单里当然也摆`() {
        val state = ScheduledTasksUiState(tasks = listOf(task()), draft = TaskDraft(id = 1L))
        assertTrue(permissionHintsVisible(state))
    }

    private fun task() = ScheduledTaskEntity(
        id = 1L,
        title = "测试任务",
        kind = "DAILY",
        intervalMinutes = null,
        hourOfDay = 8,
        minuteOfHour = 30,
        daysOfWeekMask = null,
        daysOfMonthCsv = null,
        fireAtEpochMs = null,
        anchorEpochMs = 0L,
        action = "INJECT_TEXT",
        text = "该吃药了",
        emailTo = null,
        emailSubject = null,
        allowCatchUp = true,
        enabled = true,
        lastFireAtEpochMs = null,
        nextFireAtEpochMs = null,
        createdAtEpochMs = 0L,
        updatedAtEpochMs = 0L,
    )
}
