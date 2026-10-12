package me.xiaozhi.androidclient.ui.chat

import me.xiaozhi.androidclient.model.ConnectionStatus
import me.xiaozhi.androidclient.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 顶栏状态的优先级与文案。
 *
 * 这块逻辑原先在 `MainActivity` 里抄了两份、且两份已经不一致，所以这里重点测
 * **两处合并后哪些顺序不能动**——顺序错了不会崩，只会让界面说反话：
 * 正在说话时显示「待机中」，用户就会开口插话，然后被打断。
 */
class ChatStatusTest {

    private fun state(
        connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
        isSpeaking: Boolean = false,
        isRecording: Boolean = false,
        isTurnActive: Boolean = false,
        activationPending: Boolean = false,
        silentRecovery: Boolean = false,
    ) = UiState(
        connectionStatus = connectionStatus,
        isAssistantSpeaking = isSpeaking,
        isRecording = isRecording,
        isTurnActive = isTurnActive,
        activationPending = activationPending,
        isSilentTransportRecovery = silentRecovery,
    )

    @Test
    fun `讲话中压过一切其他状态`() {
        // 连上了、还在收音——但正在播报。用户此刻最该知道的是"先别说话"。
        val s = state(
            connectionStatus = ConnectionStatus.CONNECTED,
            isSpeaking = true,
            isRecording = true,
        )
        assertEquals(ChatStatus.SPEAKING, chatStatusOf(s))
    }

    @Test
    fun `录音和回合进行中都算聆听中`() {
        assertEquals(
            ChatStatus.LISTENING,
            chatStatusOf(state(connectionStatus = ConnectionStatus.CONNECTED, isRecording = true)),
        )
        assertEquals(
            ChatStatus.LISTENING,
            chatStatusOf(state(connectionStatus = ConnectionStatus.CONNECTED, isTurnActive = true)),
        )
    }

    @Test
    fun `连上且空闲是待机中`() {
        assertEquals(
            ChatStatus.ACTIVE,
            chatStatusOf(state(connectionStatus = ConnectionStatus.CONNECTED)),
        )
    }

    /**
     * 正常收尾时 ViewModel 会同时写 `DISCONNECTED` + `isSilentTransportRecovery=true`。
     * 少了这一条，每次收尾都会闪一下「未连接」，看着像掉线。
     */
    @Test
    fun `静默重连算待机中而不是未连接`() {
        val s = state(connectionStatus = ConnectionStatus.DISCONNECTED, silentRecovery = true)
        assertEquals(ChatStatus.ACTIVE, chatStatusOf(s))
    }

    /**
     * 唤醒码还没激活时，连接状态是 `FETCHING_CONFIG` 之后的中间态，
     * 但界面必须说「待激活」——用户要去做的事是去激活，不是等网络。
     */
    @Test
    fun `待激活优先于连接中`() {
        val s = state(connectionStatus = ConnectionStatus.CONNECTING, activationPending = true)
        assertEquals(ChatStatus.ACTIVATING, chatStatusOf(s))
        assertEquals("待激活", chatStatusLabel(ChatStatus.ACTIVATING))
    }

    @Test
    fun `取配置 连接 失败分别有自己的状态`() {
        assertEquals(
            ChatStatus.FETCHING_CONFIG,
            chatStatusOf(state(connectionStatus = ConnectionStatus.FETCHING_CONFIG)),
        )
        assertEquals(
            ChatStatus.CONNECTING,
            chatStatusOf(state(connectionStatus = ConnectionStatus.CONNECTING)),
        )
        assertEquals(
            ChatStatus.FAILED,
            chatStatusOf(state(connectionStatus = ConnectionStatus.FAILED)),
        )
    }

    /** `ConnectionStatus.ACTIVATING` 全项目没有任何地方赋值过，落到未连接即可。 */
    @Test
    fun `没人赋值的激活态落到未连接`() {
        assertEquals(
            ChatStatus.DISCONNECTED,
            chatStatusOf(state(connectionStatus = ConnectionStatus.ACTIVATING)),
        )
    }

    /** 每个状态都得有一句中文，漏一个就会显示成空白（`when` 是穷尽的，这里防的是文案被删空）。 */
    @Test
    fun `每个状态都有非空文案且互不相同`() {
        val labels = ChatStatus.entries.map { chatStatusLabel(it) }
        assertEquals(ChatStatus.entries.size, labels.size)
        labels.forEach { assertEquals(true, it.isNotBlank()) }
        assertEquals(labels.size, labels.toSet().size)
    }

    /** 色彩只用来区分"要不要紧"。三个要紧的状态必须是三种不同的颜色。 */
    @Test
    fun `三种需要被注意的状态颜色两两不同`() {
        val colors = listOf(ChatStatus.SPEAKING, ChatStatus.LISTENING, ChatStatus.FAILED)
            .map { chatStatusColor(it) }
        assertEquals(3, colors.toSet().size)
    }

    @Test
    fun `等待中的几种状态共用同一个灰`() {
        val grays = listOf(
            ChatStatus.ACTIVATING,
            ChatStatus.FETCHING_CONFIG,
            ChatStatus.CONNECTING,
            ChatStatus.DISCONNECTED,
        ).map { chatStatusColor(it) }
        assertEquals(1, grays.toSet().size)
    }
}
