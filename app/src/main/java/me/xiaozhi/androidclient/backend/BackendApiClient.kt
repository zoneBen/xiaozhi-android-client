package me.xiaozhi.androidclient.backend

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** 后端没配密钥。调用方通常已经先查过 [BackendConfig.isConfigured]，走到这里说明漏了一处。 */
internal class BackendNotConfiguredException :
    IllegalStateException("后端未配置：.backend.local 里没有 appSecret")

/** 服务端答上来了，但信封里的 code 不是 0（400 参数错 / 401 签名错 / 500 服务端出错）。 */
internal class BackendApiException(
    val code: Int,
    val serverMessage: String,
) : IOException("后端返回 code=$code: $serverMessage")

/**
 * 三个后端接口的统一出入口：签头、发请求、拆信封。
 *
 * 这里**不共用** [MainViewModel] 那个 connect 20s / read 30s 的客户端，而是
 * `newBuilder()` 出一份短超时的 —— 心跳和开屏拉取都是「能拿到就赚，拿不到就算了」，
 * 而服务器是自托管的、随时可能挂。共用那份意味着服务器挂掉时每一轮都要白等 20 秒，
 * 冷启动时那 20 秒是能感觉到的。连接池和线程池仍然共享，只是超时被压短。
 */
internal class BackendApiClient(baseHttpClient: OkHttpClient) {

    private val apiClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(BackendConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(BackendConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(BackendConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /** 下开屏图专用：图片比 JSON 慢得多，读超时单独放宽，但仍然不无限等。 */
    private val downloadClient: OkHttpClient = baseHttpClient.newBuilder()
        .connectTimeout(BackendConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(DOWNLOAD_READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(BackendConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    suspend fun get(
        baseUrl: String,
        path: String,
        query: Map<String, String> = emptyMap(),
    ): Result<ApiEnvelope> = withContext(Dispatchers.IO) {
        runCatching {
            requireConfigured()
            val url = buildUrl(baseUrl, path, query)
            execute(
                Request.Builder()
                    .url(url)
                    .get()
                    .signed(GET, url, EMPTY_BODY)
                    .build(),
            )
        }
    }

    suspend fun post(
        baseUrl: String,
        path: String,
        jsonBody: String,
    ): Result<ApiEnvelope> = withContext(Dispatchers.IO) {
        runCatching {
            requireConfigured()
            val url = buildUrl(baseUrl, path)
            // 同一份字节既当请求体又当签名输入，见 BackendSigner.sign 的说明。
            val payload = jsonBody.toByteArray(Charsets.UTF_8)
            execute(
                Request.Builder()
                    .url(url)
                    .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                    .signed(POST, url, payload)
                    .build(),
            )
        }
    }

    /**
     * 下图片（开屏素材）。校验 sha256 后才返回字节 —— 半截的响应、被中间设备塞的
     * 错误页都会在这一步被挡掉，而不是等到 [android.graphics.BitmapFactory] 解不出来
     * 才发现。写盘由调用方做（临时文件 + 原子重命名），这里不碰文件系统。
     */
    suspend fun downloadBytes(
        url: String,
        expectedSha256: String = "",
        maxBytes: Long = MAX_DOWNLOAD_BYTES,
    ): Result<ByteArray> = withContext(Dispatchers.IO) {
        runCatching {
            requireConfigured()
            val parsed = url.toHttpUrlOrNull() ?: throw IOException("开屏图地址非法: $url")
            downloadClient.newCall(Request.Builder().url(parsed).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("下载开屏图失败: HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("下载开屏图失败: 响应为空")

                // 先看 Content-Length。真正危险的是一张几百 MB 的图把内存吃光，
                // 等到 bytes() 读完才发现就晚了。
                val declared = body.contentLength()
                if (declared > maxBytes) throw IOException("开屏图过大: $declared 字节")

                val bytes = body.bytes()
                if (bytes.size > maxBytes) throw IOException("开屏图过大: ${bytes.size} 字节")
                if (expectedSha256.isNotBlank() &&
                    !BackendSigner.sha256Hex(bytes).equals(expectedSha256, ignoreCase = true)
                ) {
                    throw IOException("开屏图校验失败: sha256 与服务端不一致")
                }
                bytes
            }
        }
    }

    private fun requireConfigured() {
        if (!BackendConfig.isConfigured) throw BackendNotConfiguredException()
    }

    private fun execute(request: Request): ApiEnvelope {
        val response = apiClient.newCall(request).execute()
        return response.use {
            val raw = it.body?.string().orEmpty()
            // 不按 HTTP 状态码判成败：服务端的 403（设备停用）、401（签名错）
            // 都是带信封的正常响应，调用方需要拿到里面的 code 才能区分处理。
            // 解析不出来的才当失败 —— 那多半是 nginx 的 HTML 错误页或空响应。
            val json = runCatching { org.json.JSONObject(raw) }.getOrNull()
                ?: throw IOException("HTTP ${it.code} ${it.message}: ${raw.take(SNIPPET_LENGTH)}")
            ApiEnvelope(json)
        }
    }

    private fun buildUrl(baseUrl: String, path: String, query: Map<String, String> = emptyMap()): HttpUrl {
        val full = BackendConfig.endpoint(baseUrl, path)
        val url = full.toHttpUrlOrNull() ?: throw IOException("后端地址非法: $full")
        if (query.isEmpty()) return url

        val builder = url.newBuilder()
        query.forEach { (key, value) -> builder.addQueryParameter(key, value) }
        return builder.build()
    }

    /**
     * 签名里那个 `$path` 是**从真实 URL 里取的**，不是拿常量拼的。
     *
     * 服务端算的是 `parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH)`，而线上地址带着
     * `/apps/xiaozhi_android_client` 这一段前缀 —— 也就是说客户端必须签
     * `/apps/xiaozhi_android_client/v1/heartbeat.php` 而不是 `/v1/heartbeat.php`。
     * 取 encodedPath 保证两边的口径天然一致，改基址也不用改签名代码。
     */
    private fun Request.Builder.signed(method: String, url: HttpUrl, rawBody: ByteArray): Request.Builder {
        signatureHeaders(method, url, rawBody).forEach { (name, value) -> header(name, value) }
        return header("Accept", "application/json")
    }

    companion object {
        private const val GET = "GET"
        private const val POST = "POST"
        private const val SNIPPET_LENGTH = 200
        private const val DOWNLOAD_READ_TIMEOUT_SECONDS = 20L

        /** 开屏图体积上限。超过这个大小的「图片」不该出现在开屏位上。 */
        const val MAX_DOWNLOAD_BYTES: Long = 8L * 1024 * 1024

        private val EMPTY_BODY = ByteArray(0)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /**
         * 算好三个签名头。返回空 map 表示「后端没配密钥」—— 那种情况下**不加头**，
         * 而不是加一堆假值：更新源可能指向 GitHub，那边根本不需要签名。
         *
         * 做成静态函数是为了让 [me.xiaozhi.androidclient.ota.AppUpdateManager] 也用同一份口径 ——
         * 升级走的是它自己那套「多候选源 + 下载进度」的客户端，套不进这里的 OkHttpClient。
         */
        fun signatureHeaders(
            method: String,
            url: HttpUrl,
            rawBody: ByteArray = EMPTY_BODY,
        ): Map<String, String> {
            if (!BackendConfig.isConfigured) return emptyMap()

            val appKey = BackendConfig.APP_KEY
            val timestamp = System.currentTimeMillis() / 1000
            return mapOf(
                "X-App-Key" to appKey,
                "X-Timestamp" to timestamp.toString(),
                "X-Sign" to BackendSigner.sign(
                    method = method,
                    path = url.encodedPath,
                    appKey = appKey,
                    timestampSeconds = timestamp,
                    rawBody = rawBody,
                    appSecret = BackendConfig.appSecret,
                ),
            )
        }
    }
}
