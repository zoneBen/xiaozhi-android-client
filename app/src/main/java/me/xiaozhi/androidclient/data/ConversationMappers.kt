package me.xiaozhi.androidclient.data

import me.xiaozhi.androidclient.data.db.ConversationMessageEntity
import me.xiaozhi.androidclient.model.ChatMessage
import me.xiaozhi.androidclient.model.ChatRole

/**
 * 对话消息的实体 ↔ 领域模型映射。
 *
 * 抽成顶层纯函数、**不放进 Repository**，就是为了能单测——Repository 要拿
 * `Context` 去开 Room，而这里两个方向都不碰 Android。
 */

/** 领域模型 → 实体。[roleId] 是所属角色，[nowEpochMs] 是排序键。 */
fun ChatMessage.toEntity(roleId: String, nowEpochMs: Long): ConversationMessageEntity =
    ConversationMessageEntity(
        id = id,
        roleId = roleId,
        role = role.name,
        text = text,
        timestamp = timestamp,
        imagePath = imagePath,
        createdAtEpochMs = nowEpochMs,
    )

/**
 * 实体 → 领域模型。
 *
 * [ChatRole] 认不出来时退回 `SYSTEM` 而不是抛异常：这张表的 `role` 是字符串，
 * 从旧版本数据或人工改库来的值不该把整个历史页搞崩——一条消息显示成系统提示，
 * 比进聊天页直接白屏好得多。
 */
fun ConversationMessageEntity.toChatMessage(): ChatMessage =
    ChatMessage(
        id = id,
        role = runCatching { ChatRole.valueOf(role) }.getOrDefault(ChatRole.SYSTEM),
        text = text,
        timestamp = timestamp,
        imagePath = imagePath,
    )

/**
 * 把 `DESC`（最新在前）的查询结果翻成展示顺序（最早在上）。
 *
 * 之所以不直接在 SQL 里写 `ASC` + `LIMIT`：那样取到的是**最早**的 N 条，
 * 而需求要的是**最近** N 条。必须先用 `DESC` 取，再翻过来。
 */
fun List<ConversationMessageEntity>.toChronologicalMessages(): List<ChatMessage> =
    asReversed().map { it.toChatMessage() }
