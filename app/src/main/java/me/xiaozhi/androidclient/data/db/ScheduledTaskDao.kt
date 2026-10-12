package me.xiaozhi.androidclient.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface ScheduledTaskDao {
    @Query("SELECT * FROM scheduled_tasks ORDER BY nextFireAtEpochMs IS NULL, nextFireAtEpochMs ASC, id ASC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks WHERE enabled = 1")
    suspend fun allEnabled(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun byId(id: Long): ScheduledTaskEntity?

    @Insert
    suspend fun insert(task: ScheduledTaskEntity): Long

    @Update
    suspend fun update(task: ScheduledTaskEntity)

    @Delete
    suspend fun delete(task: ScheduledTaskEntity)

    @Query("UPDATE scheduled_tasks SET enabled = :enabled, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun setEnabled(id: Long, enabled: Boolean, now: Long)

    /** 记一次触发：更新下一次发生点与上次触发时刻。 */
    @Query("UPDATE scheduled_tasks SET nextFireAtEpochMs = :next, lastFireAtEpochMs = :fired, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun recordFired(id: Long, next: Long?, fired: Long, now: Long)

    /**
     * 只改下一次发生点，**不碰「上次触发」**。
     *
     * 改时区 / 改系统时间之后要重算所有任务的下一次，但那不是一次触发——
     * 用 [recordFired] 会把 lastFireAt 一并改掉，补跑判定（`lastFireAt < nextFireAt`）
     * 就会跟着错。所以单独一条。
     */
    @Query("UPDATE scheduled_tasks SET nextFireAtEpochMs = :next, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun setNextFire(id: Long, next: Long?, now: Long)

    /** ONE_SHOT 用掉之后自己关掉，不删——用户还能在列表里看到它跑过了。 */
    @Query("UPDATE scheduled_tasks SET enabled = 0, nextFireAtEpochMs = NULL, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun retire(id: Long, now: Long)
}
