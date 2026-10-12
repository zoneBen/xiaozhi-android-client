package me.xiaozhi.androidclient.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 这组用例守的是一个很难复现的现象：冷启动后第一次发文字没有任何回应。
 *
 * 详见 [canReuseLiveConnection] 的注释——判错的代价不是多一次重连，
 * 而是把那句话连同它所在的连接一起丢掉。
 */
class OtaConnectionPolicyTest {

    private val url = "wss://api.tenclass.net/xiaozhi/v1/"

    private fun canReuse(
        incomingUrl: String? = url,
        incomingToken: String? = "tok",
        liveUrl: String = url,
        liveToken: String = "tok",
        isConnected: Boolean = true,
    ) = canReuseLiveConnection(incomingUrl, incomingToken, liveUrl, liveToken, isConnected)

    @Test
    fun `地址令牌全同且已连接时复用这条连接`() {
        assertTrue(canReuse())
    }

    @Test
    fun `没连上就不谈复用`() {
        assertFalse(canReuse(isConnected = false))
    }

    @Test
    fun `地址变了必须重连`() {
        assertFalse(canReuse(incomingUrl = "wss://other.example.com/v1/"))
    }

    @Test
    fun `地址为空说明这次刷新没成，不能复用`() {
        assertFalse(canReuse(incomingUrl = null))
        assertFalse(canReuse(incomingUrl = ""))
        assertFalse(canReuse(incomingUrl = "   "))
    }

    @Test
    fun `令牌没下发等于没变——connect 本来就沿用旧令牌`() {
        assertTrue(canReuse(incomingToken = null))
    }

    @Test
    fun `令牌变了必须重连`() {
        assertFalse(canReuse(incomingToken = "tok2"))
    }

    @Test
    fun `本地没有令牌、OTA 也没下发时算没变`() {
        assertTrue(canReuse(incomingToken = null, liveToken = ""))
    }

    @Test
    fun `本地本来没有令牌、这次下发了，必须重连带上它`() {
        assertFalse(canReuse(incomingToken = "tok", liveToken = ""))
    }
}
