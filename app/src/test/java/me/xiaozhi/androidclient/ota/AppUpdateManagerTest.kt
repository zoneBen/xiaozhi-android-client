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
            updateIndexUrl = { "http://127.0.0.1:${serverSocket!!.localPort}/version.json" },
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
            updateIndexUrl = { "http://127.0.0.1:${serverSocket!!.localPort}/version.json" },
        )

        val result = updateManager.checkForUpdate(currentVersionCode = 3)
        assertTrue("Expected UpToDate but got $result", result is UpdateCheckResult.UpToDate)
    }

    /**
     * 更新源是个 `.invalid` 占位时必须**一个请求都不发**就返回
     * [UpdateCheckResult.NotConfigured]。
     *
     * 谁会给它一个占位地址：客户端没配 `appSecret`（见 `MainViewModel.backendUpdateIndexUrl`）。
     * 那种情况下请求头里没有任何签名，发出去只会拿到 401 —— 与其让用户看到一句像网络故障的
     * 「HTTP 401」，不如安静地当作「这个包没接通升级服务」。
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
            updateIndexUrl = { AppUpdateManager.PLACEHOLDER_UPDATE_INDEX_URL },
        )

        val result = updateManager.checkForUpdate(currentVersionCode = 3)
        assertEquals(UpdateCheckResult.NotConfigured, result)
    }

    /**
     * 默认更新源指向自建后端，**不是**占位地址。
     *
     * 这条断言看着像在钉一个字面量，其实钉的是两个常量的关系：`DEFAULT_UPDATE_INDEX_URL`
     * 一旦被谁改回 `.invalid`，上面那个「不发请求」的分支就会在**所有**设备上生效，
     * 表现为「检查更新永远说没接通升级服务」——而且日志里一句话都没有，很难查。
     * 反过来，`isUpdateIndexConfigured` 判定失守（把真实地址也判成占位）同样会这样。
     */
    @Test
    fun defaultUpdateIndexUrl_isARealEndpoint() {
        assertTrue(
            "默认更新源被改成了占位地址：${AppUpdateManager.DEFAULT_UPDATE_INDEX_URL}",
            isUpdateIndexConfigured(AppUpdateManager.DEFAULT_UPDATE_INDEX_URL),
        )
        assertTrue(
            "默认更新源必须指向自建后端的 upgrade.php：${AppUpdateManager.DEFAULT_UPDATE_INDEX_URL}",
            AppUpdateManager.DEFAULT_UPDATE_INDEX_URL.endsWith("/v1/upgrade.php"),
        )
    }
}
