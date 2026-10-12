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
     * 用 [recordFired] 会顺手把 lastFireAt 记成「刚刚」，账本上就凭空多出一次
     * 从没发生过的触发。所以单独一条。
     *
     * 注意：**补跑判定并不读 lastFireAt**，它用的键是「这个发生点在 `task_executions`
     * 里有没有行」（见 `TaskCatchUpPolicy`）——那个键能堵住 receiver 落完行还没推进
     * nextFireAt 就被杀的窗口，比比大小可靠。这里保持字段诚实是为了记录本身读得通，
     * 不是喂给某个条件。别照着这一行去反推一条已经不存在的判定规则。
     */
    @Query("UPDATE scheduled_tasks SET nextFireAtEpochMs = :next, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun setNextFire(id: Long, next: Long?, now: Long)

    /** ONE_SHOT 用掉之后自己关掉，不删——用户还能在列表里看到它跑过了。 */
    @Query("UPDATE scheduled_tasks SET enabled = 0, nextFireAtEpochMs = NULL, updatedAtEpochMs = :now WHERE id = :id")
    suspend fun retire(id: Long, now: Long)
}
