package me.xiaozhi.androidclient.data

import android.content.Context
import kotlinx.coroutines.flow.Flow
import me.xiaozhi.androidclient.data.db.ScheduledTaskDao
import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.data.db.TaskExecutionDao
import me.xiaozhi.androidclient.data.db.TaskExecutionEntity
import me.xiaozhi.androidclient.data.db.XiaozhiDatabase

/**
 * 定时任务的持久化入口。
 *
 * 这一层刻意很薄：**不带缓存、不做状态**。原因是它的两个写入方来自完全不同的地方——
 * 闹钟 receiver（进程可能刚被拉起来，什么都没有）和界面（用户在编辑），
 * 而唯一的真相在库里。任何一侧在内存里留一份副本，都会在另一侧写入后变成陈旧数据，
 * 表现就是"刚改的任务到点还是按老样子响"。
 *
 * 队列语义（`PENDING` 行即待办）也在这一层表达，理由是 `claimPending` 的原子认领
 * 必须走一条 SQL（靠受影响行数判断抢没抢到），不能拆成"先查再改"。
 */
class ScheduledTaskRepository(
    private val taskDao: ScheduledTaskDao,
    private val executionDao: TaskExecutionDao,
    private val now: () -> Long = System::currentTimeMillis,
) {
    constructor(context: Context) : this(
        XiaozhiDatabase.get(context).scheduledTaskDao(),
        XiaozhiDatabase.get(context).taskExecutionDao(),
    )

    fun observeTasks(): Flow<List<ScheduledTaskEntity>> = taskDao.observeAll()

    suspend fun allEnabled(): List<ScheduledTaskEntity> = taskDao.allEnabled()

    suspend fun byId(id: Long): ScheduledTaskEntity? = taskDao.byId(id)

    suspend fun insert(task: ScheduledTaskEntity): Long = taskDao.insert(task)

    suspend fun update(task: ScheduledTaskEntity) =
        taskDao.update(task.copy(updatedAtEpochMs = now()))

    suspend fun delete(task: ScheduledTaskEntity) {
        // 记录留着：任务没了，"它当时响没响"仍然是用户要查的东西（记录里存了 title 副本）。
        taskDao.delete(task)
    }

    suspend fun setEnabled(id: Long, enabled: Boolean) = taskDao.setEnabled(id, enabled, now())

    /** 闹钟到点：记一次触发，并写入下一个发生点（一次性任务传 null 并 [retire]）。 */
    suspend fun recordFired(id: Long, nextFireAtEpochMs: Long?, firedAtEpochMs: Long) =
        taskDao.recordFired(id, nextFireAtEpochMs, firedAtEpochMs, now())

    /** 只重算下一次（改时区 / 改系统时间后重排），不算作一次触发。 */
    suspend fun setNextFire(id: Long, nextFireAtEpochMs: Long?) =
        taskDao.setNextFire(id, nextFireAtEpochMs, now())

    /** 一次性任务用掉之后关掉自己，不删。 */
    suspend fun retire(id: Long) = taskDao.retire(id, now())

    // ---------- 执行记录 / 队列 ----------

    fun observeRecentExecutions(limit: Int = DEFAULT_RECENT_EXECUTIONS): Flow<List<TaskExecutionEntity>> =
        executionDao.observeRecent(limit)

    fun observeExecutionsForTask(taskId: Long, limit: Int = DEFAULT_RECENT_EXECUTIONS): Flow<List<TaskExecutionEntity>> =
        executionDao.observeRecentForTask(taskId, limit)

    fun observeReadyPending(nowEpochMs: Long): Flow<List<TaskExecutionEntity>> =
        executionDao.observeReadyPending(nowEpochMs)

    suspend fun enqueue(execution: TaskExecutionEntity): Long = executionDao.insert(execution)

    /** 这个发生点是不是已经有记录了。补跑幂等靠它，详见 [TaskExecutionDao.countAt]。 */
    suspend fun hasExecutionAt(taskId: Long, scheduledAtEpochMs: Long): Boolean =
        executionDao.countAt(taskId, scheduledAtEpochMs) > 0

    /** 原子认领，返回 true = 抢到了。返回 false 表示别的消费者先拿走了。 */
    suspend fun claim(executionId: Long): Boolean = executionDao.claimPending(executionId) > 0

    suspend fun finish(executionId: Long, status: String, outcome: String?, detail: String?) =
        executionDao.finish(executionId, status, outcome, detail)

    suspend fun requeue(executionId: Long, attempts: Int, nextAttemptAtEpochMs: Long, detail: String?) =
        executionDao.requeue(executionId, attempts, nextAttemptAtEpochMs, detail)

    suspend fun clearExecutionsForTask(taskId: Long) = executionDao.clearForTask(taskId)

    suspend fun pruneExecutions(cap: Int = MAX_EXECUTIONS) = executionDao.prune(cap)

    companion object {
        const val DEFAULT_RECENT_EXECUTIONS = 50

        /** 执行记录的总上限。和对话历史一样是兜底，不是回滚语义。 */
        const val MAX_EXECUTIONS = 2000
    }
}
