package me.xiaozhi.androidclient.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 一条对话消息的长期留存。
 *
 * 内存里的 `UiState.chatMessages` 只保留最近 100 条（[me.xiaozhi.androidclient.ui.MainViewModel]
 * 的 `takeLast(100)`），那是给渲染用的窗口；**真正让消息活过进程重启的是这张表**。
 *
 * **主键直接用应用生成的 `ChatMessage.id`，不是 `autoGenerate`。**
 * 那个 id 是「每进程从当前毫秒起自增」的序列（见 `MainViewModel.newMessageId`），
 * 本来就跨进程唯一。直接拿它当主键，插入就是一次性的、不需要回读数据库生成的 id——
 * 而回读会把消息插入变成异步两段式，还得把新 id 再写回 `UiState`，代价远大于收益。
 *
 * 理论上时钟被 NTP 往回跳之后重启可能撞 id，所以插入一律走 `OnConflictStrategy.IGNORE`：
 * 这种碰撞退化成「这条没落库」，而不是崩掉。
 */
@Entity(
    tableName = "conversation_messages",
    indices = [Index(value = ["roleId", "createdAtEpochMs"])],
)
data class ConversationMessageEntity(
    @PrimaryKey val id: Long,
    /** 所属角色。历史**按角色分组**，切换角色时互相看不到对方的消息。 */
    val roleId: String,
    /** `ChatRole.name`，存字符串而不是 ordinal：加枚举项不会让老数据串位。 */
    val role: String,
    val text: String,
    /** 沿用 `ChatMessage.timestamp` 的展示串，界面上直接拿它显示。 */
    val timestamp: String,
    /** 照片消息才有。 */
    val imagePath: String?,
    /** 排序键。`ChatMessage` 自己没有时间戳字段，插入时补一个。 */
    val createdAtEpochMs: Long,
)
