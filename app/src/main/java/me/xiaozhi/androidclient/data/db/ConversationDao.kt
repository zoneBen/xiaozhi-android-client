package me.xiaozhi.androidclient.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ConversationDao {
    /**
     * 一律 IGNORE。主键是应用侧生成的 id，正常路径下不会撞；
     * 真撞了（时钟被 NTP 往回跳之后重启）就退化成「这条没落库」，比抛异常好。
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: ConversationMessageEntity)

    /**
     * 进聊天页时载入某个角色的最近 [limit] 条。
     *
     * 按 `DESC` 取（取的是**最新的**那批，不是最早的），调用方拿回去之后要
     * `asReversed()` 才是从上到下的展示顺序——直接改成 ASC + LIMIT 会取到最早的 N 条。
     */
    @Query(
        "SELECT * FROM conversation_messages WHERE roleId = :roleId " +
            "ORDER BY createdAtEpochMs DESC, id DESC LIMIT :limit",
    )
    suspend fun recentForRole(roleId: String, limit: Int): List<ConversationMessageEntity>

    @Query("DELETE FROM conversation_messages WHERE roleId = :roleId")
    suspend fun clearForRole(roleId: String)

    @Query("DELETE FROM conversation_messages")
    suspend fun clearAll()

    @Query("SELECT COUNT(*) FROM conversation_messages WHERE roleId = :roleId")
    suspend fun countForRole(roleId: String): Int

    @Query("SELECT COUNT(*) FROM conversation_messages")
    suspend fun countAll(): Int

    /**
     * 保留策略：每个角色只留最新 [cap] 条。
     *
     * 这是「别让库无限长」的兜底，不是回滚语义——设置页会同时显示总条数并提供手动清空，
     * 让用户看得见，比静默删除诚实。
     */
    @Query(
        "DELETE FROM conversation_messages WHERE roleId = :roleId AND id NOT IN (" +
            "SELECT id FROM conversation_messages WHERE roleId = :roleId " +
            "ORDER BY createdAtEpochMs DESC, id DESC LIMIT :cap)",
    )
    suspend fun pruneRole(roleId: String, cap: Int)
}
