package me.xiaozhi.androidclient.ota

import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AppUpdateManagerTest {

    private var serverSocket: ServerSocket? = null
    private val executor = Executors.newCachedThreadPool()
    private lateinit var httpClient: OkHttpClient
    private var mockResponseBody = ""

    @Before
    fun setUp() {
        httpClient = OkHttpClient.Builder().build()
        serverSocket = ServerSocket(0)
        executor.execute {
            while (serverSocket?.isClosed == false) {
                try {
                    val client = serverSocket?.accept() ?: break
                    executor.execute { handleClient(client) }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    private fun handleClient(client: Socket) {
        client.use {
            val input = it.getInputStream().bufferedReader()
            while (true) {
                val line = input.readLine() ?: break
                if (line.isEmpty()) break
            }
            val bodyBytes = mockResponseBody.toByteArray(Charsets.UTF_8)
            val response = "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bodyBytes.size}\r\nConnection: close\r\n\r\n"
            it.getOutputStream().write(response.toByteArray())
            it.getOutputStream().write(bodyBytes)
            it.getOutputStream().flush()
        }
    }

    @After
    fun tearDown() {
        serverSocket?.close()
        serverSocket = null
    }

    @Test
    fun checkForUpdate_hasNewVersion_returnsHasUpdate() = runBlocking {
        mockResponseBody = """
            {
              "versionCode": 4,
              "versionName": "1.2.0",
              "downloadUrl": "http://127.0.0.1:${serverSocket!!.localPort}/app.apk",
              "sha256": "fake-hash",
              "releaseNotes": "新功能更新",
              "forceUpdate": false
            }
        """.trimIndent()

        val dummyContext = android.app.Application()
        val updateManager = AppUpdateManager(
            context = dummyContext,
            baseHttpClient = httpClient,
            updateIndexUrl = "http://127.0.0.1:${serverSocket!!.localPort}/version.json",
        )

        val result = updateManager.checkForUpdate(currentVersionCode = 3)
        assertTrue("Expected HasUpdate but got $result", result is UpdateCheckResult.HasUpdate)
        val hasUpdate = result as UpdateCheckResult.HasUpdate
        assertEquals(4, hasUpdate.info.versionCode)
        assertEquals("1.2.0", hasUpdate.info.versionName)
        assertEquals("新功能更新", hasUpdate.info.releaseNotes)
    }

    @Test
    fun checkForUpdate_alreadyLatest_returnsUpToDate() = runBlocking {
        mockResponseBody = """
            {
              "versionCode": 3,
              "versionName": "1.1.0",
              "downloadUrl": "http://127.0.0.1:${serverSocket!!.localPort}/app.apk",
              "sha256": "fake-hash"
            }
        """.trimIndent()

        val dummyContext = android.app.Application()
        val updateManager = AppUpdateManager(
            context = dummyContext,
            baseHttpClient = httpClient,
            updateIndexUrl = "http://127.0.0.1:${serverSocket!!.localPort}/version.json",
        )

        val result = updateManager.checkForUpdate(currentVersionCode = 3)
        assertTrue("Expected UpToDate but got $result", result is UpdateCheckResult.UpToDate)
    }

    /**
     * 本定制版的默认更新源是 `.invalid` 占位。它必须**一个请求都不发**就返回
     * [UpdateCheckResult.NotConfigured]——既省掉三个注定失败的往返（每次冷启动的
     * 静默检查也要走一遍），也让用户看到的是「没有这个功能」而不是一句英文 DNS 报错。
     *
     * 这里的客户端被塞了一个「一旦发请求就抛错」的拦截器：拦截器抛的是 [AssertionError]
     * （不是 Exception），`checkForUpdate` 内部的 `runCatching` 拦不住它，所以只要真的
     * 发了网络请求，这个测试就会红——「没发请求」是被证明的，不是被假设的。
     */
    @Test
    fun checkForUpdate_placeholderUrl_returnsNotConfiguredWithoutAnyRequest() = runBlocking {
        val noNetworkClient = OkHttpClient.Builder()
            .addInterceptor { throw AssertionError("占位更新源不该发出任何网络请求") }
            .build()

        val updateManager = AppUpdateManager(
            context = android.app.Application(),
            baseHttpClient = noNetworkClient,
            updateIndexUrl = AppUpdateManager.DEFAULT_UPDATE_INDEX_URL,
        )

        val result = updateManager.checkForUpdate(currentVersionCode = 3)
        assertEquals(UpdateCheckResult.NotConfigured, result)
    }
}
