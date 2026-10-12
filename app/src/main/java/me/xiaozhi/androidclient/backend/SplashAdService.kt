package me.xiaozhi.androidclient.backend

import org.json.JSONObject

/**
 * 接口② 开屏：拉取素材 + 上报曝光/点击。
 *
 * 拉取用 GET（带 query），上报用 POST。**失败一律不算错**：服务器是自托管的，
 * 「拿不到新素材」的正常表现就是继续用手上那张缓存图，或者干脆走原动画。
 */
internal class SplashAdService(
    private val apiClient: BackendApiClient,
) {

    /**
     * @param lastRevision 本地缓存的 revision。和服务端一致时只回一个 `notModified`，
     *   连 items 都不下发 —— 省掉每次冷启动的那份清单。
     * @param width/height 目前不参与筛选，带上是为了将来做多分辨率时不用改这里的调用点。
     */
    suspend fun fetch(
        deviceUid: String,
        lastRevision: Long,
        width: Int,
        height: Int,
    ): Result<SplashAdFeed> = apiClient.get(
        baseUrl = BackendConfig.DEFAULT_BASE_URL,
        path = BackendConfig.PATH_SPLASH,
        query = mapOf(
            "deviceUid" to deviceUid,
            "lastRevision" to lastRevision.toString(),
            "width" to width.toString(),
            "height" to height.toString(),
        ),
    ).mapCatching { envelope ->
        if (envelope.code != BackendCode.OK) {
            throw BackendApiException(envelope.code, envelope.message)
        }
        SplashAdFeed.parse(envelope.data)
    }

    /** 下开屏图。[expectedSha256] 非空时校验，不匹配直接当失败。 */
    suspend fun downloadImage(imageUrl: String, expectedSha256: String): Result<ByteArray> =
        apiClient.downloadBytes(imageUrl, expectedSha256)

    /** 上报一次曝光或点击。纯统计用途，失败只记日志。 */
    suspend fun report(
        deviceUid: String,
        splashId: Int,
        action: String,
    ): Result<Unit> {
        if (splashId <= 0) return Result.success(Unit)

        val body = JSONObject()
            .put("deviceUid", deviceUid)
            .put("splashId", splashId)
            .put("action", action)
            .toString()

        return apiClient.post(BackendConfig.DEFAULT_BASE_URL, BackendConfig.PATH_SPLASH, body).mapCatching { envelope ->
            if (envelope.code != BackendCode.OK) {
                throw BackendApiException(envelope.code, envelope.message)
            }
        }
    }
}
