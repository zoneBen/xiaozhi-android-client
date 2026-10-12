package me.xiaozhi.androidclient.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskExecutionDao {
    @Insert
    suspend fun insert(execution: TaskExecutionEntity): Long

    @Query("SELECT * FROM task_executions WHERE taskId = :taskId ORDER BY scheduledAtEpochMs DESC LIMIT :limit")
    fun observeRecentForTask(taskId: Long, limit: Int): Flow<List<TaskExecutionEntity>>

    @Query("SELECT * FROM task_executions ORDER BY scheduledAtEpochMs DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<TaskExecutionEntity>>

    /**
     * 待办队列。
     *
     * `nextAttemptAtEpochMs IS NULL` 那部分是**首次尝试**（刚被 receiver 落行，还没退避过）；
     * 有值的则是重试排期，到点了才会被取出来。少了这个条件，重试中的行会被立刻捞起来空转。
     */
    @Query(
        "SELECT * FROM task_executions WHERE status = 'PENDING' " +
            "AND (nextAttemptAtEpochMs IS NULL OR nextAttemptAtEpochMs <= :now) " +
            "ORDER BY scheduledAtEpochMs ASC",
    )
    fun observeReadyPending(now: Long): Flow<List<TaskExecutionEntity>>

    /**
     * 原子认领：靠**受影响行数**判断有没有抢到。
     *
     * 返回 1 = 抢到了，返回 0 = 别人先动了手。抽成一条 UPDATE 而不是
     * 「先查再改」，就是为了一次 SQL 里把判定和写入做完，避免两个消费者同时看到 PENDING。
     */
    @Query("UPDATE task_executions SET status = 'RUNNING' WHERE id = :id AND status = 'PENDING'")
    suspend fun claimPending(id: Long): Int

    @Query("UPDATE task_executions SET status = :status, outcome = :outcome, detail = :detail WHERE id = :id")
    suspend fun finish(id: Long, status: String, outcome: String?, detail: String?)

    /** 退回到 PENDING 并排下一次重试。`attempts` 由调用方累加。 */
    @Query(
        "UPDATE task_executions SET status = 'PENDING', attempts = :attempts, " +
            "nextAttemptAtEpochMs = :nextAttemptAt, detail = :detail WHERE id = :id",
    )
    suspend fun requeue(id: Long, attempts: Int, nextAttemptAt: Long, detail: String?)

    @Query("DELETE FROM task_executions WHERE taskId = :taskId")
    suspend fun clearForTask(taskId: Long)

    /** 保留策略：只留最新 [cap] 条。与对话历史同理，是兜底不是回滚。 */
    @Query(
        "DELETE FROM task_executions WHERE id NOT IN (" +
            "SELECT id FROM task_executions ORDER BY scheduledAtEpochMs DESC, id DESC LIMIT :cap)",
    )
    suspend fun prune(cap: Int)
}
