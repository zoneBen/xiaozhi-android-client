package me.xiaozhi.androidclient.network

/**
 * 一次 OTA 刷新回来时，**要不要拆掉当前这条连接重连**。
 *
 * ## 为什么需要判断
 *
 * 刷新 OTA 和建立连接是两条并行的路：
 *
 *  - [me.xiaozhi.androidclient.ui.MainViewModel.fetchOfficialConfig] 去服务端拿
 *    WebSocket 地址/令牌；
 *  - 而 [me.xiaozhi.androidclient.ui.MainViewModel.ensureReadyForConversation] 只要
 *    本地还存着上次的地址就**不等 OTA**，直接 `connect()`。
 *
 * 于是 OTA 回来的时候，一条连接往往已经握完手了。刷新逻辑如果无条件地
 * 「清空状态 → 重连」，就会把这条正在用的连接拆掉——而刚发出去的那句话恰好发在它上面。
 * 服务端确实收到了、也回答了，只是回答发向了一个已经关闭的 session。
 *
 * 现象很难归因：**冷启动后第一次发文字，小智一声不吭；第二次开始一切正常。**
 * 因为第一次被拆了，第二次时 OTA 早就刷完了，没人再来拆。
 *
 * ## 判断规则
 *
 * 地址和令牌都没变，就没有任何理由重连——服务端能告诉我们的信息这次刷新已经给全了。
 * 令牌允许缺省：OTA 不下发时 `connect()` 本来就沿用旧令牌，所以「没下发」等于「没变」。
 */
internal fun canReuseLiveConnection(
    incomingUrl: String?,
    incomingToken: String?,
    liveUrl: String,
    liveToken: String,
    isConnected: Boolean,
): Boolean {
    // 没连上就没有"正在用的连接"可言，该连还得连。
    if (!isConnected) return false
    // 没拿到地址说明这次刷新没成（比如设备还没激活），谈不上复用。
    if (incomingUrl.isNullOrBlank()) return false
    if (incomingUrl != liveUrl) return false
    return (incomingToken ?: liveToken) == liveToken
}
