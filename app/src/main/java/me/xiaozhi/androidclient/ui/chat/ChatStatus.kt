package me.xiaozhi.androidclient.ui.chat

import androidx.compose.ui.graphics.Color
import me.xiaozhi.androidclient.model.ConnectionStatus
import me.xiaozhi.androidclient.model.UiState
import me.xiaozhi.androidclient.ui.theme.Brand
import me.xiaozhi.androidclient.ui.theme.DangerRed
import me.xiaozhi.androidclient.ui.theme.MutedGray
import me.xiaozhi.androidclient.ui.theme.PendingAmber
import me.xiaozhi.androidclient.ui.theme.ReadyGreen

/**
 * 顶栏状态灯上那**一个**状态。
 *
 * ## 为什么要有这个枚举
 *
 * 这套优先级判定原先在 `MainActivity` 里**抄了两份**——聊天页的 `ChatHeaderCard`
 * 和数字人页的 `digitalHumanStatusLabel()`。两份的写法就已经不一样了（数字人那份少了
 * 「待激活」和「静默重连」两条），也就是说同一时刻切两个界面会看到不同的状态。
 * 合并成一处之后，两者不可能再漂移。
 *
 * ## 为什么把「连接中」拆成三个
 *
 * 「获取配置中」「待激活」「连接中」在界面上是**三句不同的话**，用户看到它们要做的事
 * 也完全不同（等着 / 去激活 / 多半是网络问题）。合成一个 `CONNECTING` 就得把文案函数
 * 变成 `(status, state) -> String`，正好丢掉「纯函数、可单测」这个好处。
 */
enum class ChatStatus {
    SPEAKING,
    LISTENING,
    ACTIVE,
    ACTIVATING,
    FETCHING_CONFIG,
    CONNECTING,
    FAILED,
    DISCONNECTED,
}

/**
 * 由界面状态推出该显示哪一个。
 *
 * 顺序即优先级，**不要重排**。
 *
 * 注：`ConnectionStatus.ACTIVATING` 全项目没有任何地方赋值过（它只出现在几个已无调用点的
 * 辅助函数里），所以这里和拆分前一样落到 `DISCONNECTED`。真要让激活态显示成「激活中」，
 * 得先在 ViewModel 里真的写这个值，而不是在这里补一条 `when` 分支假装它会出现。
 */
fun chatStatusOf(state: UiState): ChatStatus = when {
    // 出声和收音排最前：它们是**正在发生**的事，比连接层状态更该被一眼看到。
    state.isAssistantSpeaking -> ChatStatus.SPEAKING
    state.isRecording || state.isTurnActive -> ChatStatus.LISTENING
    state.connectionStatus == ConnectionStatus.CONNECTED -> ChatStatus.ACTIVE
    // 这一条只在上面那条不成立时才有意义，因为 isSilentTransportRecovery=true 一定是
    // 和 connectionStatus=DISCONNECTED 一起写下的（见 MainViewModel 的 Socket 关闭分支）。
    // 它的含义是"这条连接是我们自己正常收尾的，马上会连回来"。没有这一条，
    // 每次正常收尾都会闪一下「未连接」，看着像掉线。
    state.isSilentTransportRecovery -> ChatStatus.ACTIVE
    state.activationPending -> ChatStatus.ACTIVATING
    state.connectionStatus == ConnectionStatus.FETCHING_CONFIG -> ChatStatus.FETCHING_CONFIG
    state.connectionStatus == ConnectionStatus.CONNECTING -> ChatStatus.CONNECTING
    state.connectionStatus == ConnectionStatus.FAILED -> ChatStatus.FAILED
    else -> ChatStatus.DISCONNECTED
}

fun chatStatusLabel(status: ChatStatus): String = when (status) {
    ChatStatus.SPEAKING -> "讲话中"
    ChatStatus.LISTENING -> "聆听中"
    ChatStatus.ACTIVE -> "待机中"
    ChatStatus.ACTIVATING -> "待激活"
    ChatStatus.FETCHING_CONFIG -> "获取配置中"
    ChatStatus.CONNECTING -> "连接中"
    ChatStatus.FAILED -> "连接失败"
    ChatStatus.DISCONNECTED -> "未连接"
}

/**
 * 状态灯与状态文字的用色。
 *
 * 只有 `SPEAKING` / `LISTENING` / `FAILED` 三个是**彩色**的——它们分别对应
 * "现在别插话""现在说话它听得见""坏了，要看一眼"。其余一律灰：
 * 一台常年开着的设备上，谁都需要一眼扫过去，色彩一旦到处都是就等于没有色彩。
 */
fun chatStatusColor(status: ChatStatus): Color = when (status) {
    ChatStatus.SPEAKING -> PendingAmber
    ChatStatus.LISTENING -> Brand
    ChatStatus.ACTIVE -> ReadyGreen
    ChatStatus.FAILED -> DangerRed
    ChatStatus.ACTIVATING,
    ChatStatus.FETCHING_CONFIG,
    ChatStatus.CONNECTING,
    ChatStatus.DISCONNECTED,
    -> MutedGray
}
