package me.xiaozhi.androidclient.data

import android.content.Context
import me.xiaozhi.androidclient.data.db.ConversationDao
import me.xiaozhi.androidclient.data.db.XiaozhiDatabase
import me.xiaozhi.androidclient.model.ChatMessage

/**
 * 对话历史的读写入口。
 *
 * 上层（`MainViewModel`）只认 [ChatMessage]，实体和 SQL 都锁在这个文件背后。
 */
class ConversationRepository(private val dao: ConversationDao) {

    constructor(context: Context) : this(XiaozhiDatabase.get(context).conversationDao())

    /** 进聊天页时载入某个角色的最近 [limit] 条，已是展示顺序（最早在上）。 */
    suspend fun recentForRole(roleId: String, limit: Int = DEFAULT_LOAD_LIMIT): List<ChatMessage> =
        dao.recentForRole(roleId, limit).toChronologicalMessages()

    /**
     * 追加一条。
     *
     * [createdAtEpochMs] 由调用方传，而不是在这里取 `System.currentTimeMillis()`——
     * 消息 id 是「每进程从当前毫秒起自增」的，排序键和它同源，两者的相对顺序才稳定。
     */
    suspend fun append(roleId: String, message: ChatMessage, createdAtEpochMs: Long) {
        dao.insert(message.toEntity(roleId, createdAtEpochMs))
    }

    suspend fun clearForRole(roleId: String) = dao.clearForRole(roleId)

    suspend fun clearAll() = dao.clearAll()

    suspend fun countForRole(roleId: String): Int = dao.countForRole(roleId)

    suspend fun countAll(): Int = dao.countAll()

    suspend fun pruneRole(roleId: String, cap: Int = MAX_MESSAGES_PER_ROLE) = dao.pruneRole(roleId, cap)

    companion object {
        /** 冷启动 / 切角色时载入的条数。用户明确要的就是「最近 10 条」。 */
        const val DEFAULT_LOAD_LIMIT = 10

        /**
         * 每个角色的留存上限。
         *
         * 这是「别让库无限长」的兜底，**不是**业务语义——设置页会显示总条数并提供手动清空，
         * 用户看得见的东西就不该偷偷删。到 1000 条才收一次，正常运行几个月都碰不到。
         */
        const val MAX_MESSAGES_PER_ROLE = 1000

        /** 每写这么多条顺手跑一次 [pruneRole]，不必每条都查一次库。 */
        const val PRUNE_EVERY = 50
    }
}
