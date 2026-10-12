package me.xiaozhi.androidclient.data

import me.xiaozhi.androidclient.data.db.ConversationMessageEntity
import me.xiaozhi.androidclient.model.ChatMessage
import me.xiaozhi.androidclient.model.ChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 对话消息的实体 ↔ 领域模型映射，以及「最近 N 条」的取出顺序。
 *
 * 这两件事是历史功能最容易静默出错的地方：映射错一个字段（比如 `imagePath` 漏了）
 * 界面不会崩，只是照片消息变成纯文字；顺序搞反则表现为「进聊天页看到的是很久以前的消息」，
 * 而两条路都不会抛异常。所以专门测。
 */
class ConversationMappersTest {

    private fun entity(
        id: Long,
        role: ChatRole = ChatRole.USER,
        text: String = "text-$id",
        imagePath: String? = null,
        createdAt: Long = id,
    ) = ConversationMessageEntity(
        id = id,
        roleId = "role-1",
        role = role.name,
        text = text,
        timestamp = "10:00:0$id",
        imagePath = imagePath,
        createdAtEpochMs = createdAt,
    )

    @Test
    fun `实体转领域模型保留全部字段`() {
        val source = entity(id = 7, role = ChatRole.ASSISTANT, text = "你好", imagePath = "/tmp/a.jpg")

        val message = source.toChatMessage()

        assertEquals(7L, message.id)
        assertEquals(ChatRole.ASSISTANT, message.role)
        assertEquals("你好", message.text)
        assertEquals("10:00:07", message.timestamp)
        assertEquals("/tmp/a.jpg", message.imagePath)
    }

    @Test
    fun `纯文字消息的 imagePath 往返仍是 null`() {
        val message = ChatMessage(
            id = 3,
            role = ChatRole.USER,
            text = "打开云台灯光",
            timestamp = "08:30:00",
        )

        val roundTrip = message.toEntity(roleId = "role-1", nowEpochMs = 111L).toChatMessage()

        assertEquals(message, roundTrip)
        assertNull(roundTrip.imagePath)
    }

    @Test
    fun `写库时带上角色 id 与排序键`() {
        val message = ChatMessage(
            id = 42,
            role = ChatRole.SYSTEM,
            text = "【定时任务】开灯",
            timestamp = "07:00:00",
        )

        val stored = message.toEntity(roleId = "role-9", nowEpochMs = 1_700_000_000_000L)

        assertEquals("role-9", stored.roleId)
        assertEquals(1_700_000_000_000L, stored.createdAtEpochMs)
        assertEquals("SYSTEM", stored.role)
    }

    /**
     * `role` 列是字符串。将来加枚举项、或者有人手工改过库，都会出现认不出来的值。
     * 那时必须退化成一条系统消息，而不是让整个聊天页崩掉。
     */
    @Test
    fun `认不出的角色退回 SYSTEM 而不是抛异常`() {
        val broken = entity(id = 1).copy(role = "SUPERVISOR")

        assertEquals(ChatRole.SYSTEM, broken.toChatMessage().role)
    }

    /**
     * 关键的一条：查询用的是 `DESC`（最新在前），展示需要「最早在上」。
     *
     * 直接用 `asReversed()` 翻过来即可。如果哪天有人把 SQL 改成 `ASC`，这个测试仍然会过
     * （因为这里喂进去的已经是 DESC 顺序），但截取到的会是**最早**的 N 条——
     * 那种改动的防线在 DAO 的注释里，不在这里。
     */
    @Test
    fun `DESC 查询结果反转后是最早在上`() {
        val newestFirst = listOf(entity(30), entity(20), entity(10))
        //                                            ↑ 数据库按 createdAt 倒序吐出来的

        val shown = newestFirst.toChronologicalMessages()

        assertEquals(listOf(10L, 20L, 30L), shown.map { it.id })
    }

    @Test
    fun `空结果反转后还是空`() {
        assertEquals(emptyList<ChatMessage>(), emptyList<ConversationMessageEntity>().toChronologicalMessages())
    }
}
