package me.xiaozhi.androidclient.backend

import org.json.JSONArray
import org.json.JSONObject

/**
 * 服务端统一信封 `{code, message, serverTime, data}`，以及三个接口的请求/响应模型。
 *
 * 解析一律走 org.json 手工读 —— 项目里没有 Gson/Moshi/kotlinx.serialization，
 * OtaConfigService、ActivationService 也都是这么写的，保持一致。
 */

/** 服务端 `code` 取值，与 `php/common.php` 的 `ZX_CODE_*` 一一对应。 */
internal object BackendCode {
    const val OK = 0
    const val BAD_REQUEST = 400
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val NOT_FOUND = 404
    const val SERVER_ERROR = 500
}

/** 信封。`data` 缺席是正常的：upgrade.php 把字段平铺在顶层，本来就没有 data。 */
internal class ApiEnvelope(val root: JSONObject) {
    val code: Int = root.optInt("code", -1)
    val message: String = root.optString("message", "")
    val data: JSONObject? = root.optJSONObject("data")
}

// ---- 心跳 ------------------------------------------------------------------

internal data class HeartbeatRequest(
    val deviceUid: String,
    val clientId: String,
    val androidId: String,
    val mac: String,
    val serialNumber: String,
    val installId: String,
    val brand: String,
    val manufacturer: String,
    val model: String,
    val deviceName: String,
    val osVersion: String,
    val sdkInt: Int,
    val appVersionCode: Int,
    val appVersionName: String,
    val screenWidth: Int,
    val screenHeight: Int,
    val netType: String,
    val source: String,
) {
    fun toJson(): String = JSONObject().apply {
        put("deviceUid", deviceUid)
        put("clientId", clientId)
        put("androidId", androidId)
        put("mac", mac)
        put("serialNumber", serialNumber)
        put("installId", installId)
        put("brand", brand)
        put("manufacturer", manufacturer)
        put("model", model)
        put("deviceName", deviceName)
        put("osVersion", osVersion)
        put("sdkInt", sdkInt)
        put("appVersionCode", appVersionCode)
        put("appVersionName", appVersionName)
        put("screenWidth", screenWidth)
        put("screenHeight", screenHeight)
        put("netType", netType)
        put("source", source)
    }.toString()
}

internal data class HeartbeatResult(
    val intervalSec: Int,
    val deviceStatus: Int,
    val splashEnabled: Boolean,
    val latestVersionCode: Int,
) {
    companion object {
        fun parse(data: JSONObject?): HeartbeatResult = HeartbeatResult(
            intervalSec = BackendConfig.clampHeartbeatInterval(
                data?.optInt("intervalSec", BackendConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS)
                    ?: BackendConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS,
            ),
            deviceStatus = data?.optInt("deviceStatus", 1) ?: 1,
            splashEnabled = data?.optBoolean("splashEnabled", true) ?: true,
            latestVersionCode = data?.optInt("latestVersionCode", 0) ?: 0,
        )
    }
}

// ---- 开屏 ------------------------------------------------------------------

internal data class SplashAdItem(
    val id: Int,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val imageSha256: String,
    val linkUrl: String,
    val durationMs: Int,
    val weight: Int,
) {
    companion object {
        fun parse(json: JSONObject): SplashAdItem = SplashAdItem(
            id = json.optInt("id", 0),
            title = json.optString("title", ""),
            subtitle = json.optString("subtitle", ""),
            imageUrl = json.optString("imageUrl", ""),
            imageSha256 = json.optString("imageSha256", "").lowercase(),
            linkUrl = json.optString("linkUrl", ""),
            // 服务端已经钳过一次，这里再钳一次：客户端不该相信外部数据，
            // 一个 0 或者 99999999 都会让开屏变成「一闪而过」或「卡住不放」。
            durationMs = (json.optInt("durationMs", 3000)).coerceIn(500, 15_000),
            weight = json.optInt("weight", 100),
        )
    }
}

internal data class SplashAdFeed(
    val enabled: Boolean,
    val ttlSec: Int,
    val revision: Long,
    val notModified: Boolean,
    val items: List<SplashAdItem>,
) {
    companion object {
        fun parse(data: JSONObject?): SplashAdFeed {
            val array = data?.optJSONArray("items") ?: JSONArray()
            val items = (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(SplashAdItem::parse)
            }

            return SplashAdFeed(
                enabled = data?.optBoolean("enabled", true) ?: true,
                ttlSec = (data?.optInt("ttlSec", 600) ?: 600).coerceAtLeast(60),
                revision = data?.optLong("revision", 0L) ?: 0L,
                notModified = data?.optBoolean("notModified", false) ?: false,
                items = items,
            )
        }
    }
}

// ---- 开屏本地缓存 ----------------------------------------------------------

/**
 * 落盘的开屏缓存。字段比 [SplashAdItem] 多一个 `fetchedAtMillis`：
 * 服务器长期失联时，客户端要靠它判断这份缓存是不是已经太旧、该放弃了。
 */
internal data class SplashAdCache(
    val id: Int,
    val title: String,
    val subtitle: String,
    val imageUrl: String,
    val imageSha256: String,
    val linkUrl: String,
    val durationMs: Int,
    val revision: Long,
    val fetchedAtMillis: Long,
) {
    fun toJson(): String = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("subtitle", subtitle)
        put("imageUrl", imageUrl)
        put("imageSha256", imageSha256)
        put("linkUrl", linkUrl)
        put("durationMs", durationMs)
        put("revision", revision)
        put("fetchedAtMillis", fetchedAtMillis)
    }.toString()

    companion object {
        fun fromJson(json: JSONObject, fallbackFetchedAt: Long): SplashAdCache = SplashAdCache(
            id = json.optInt("id", 0),
            title = json.optString("title", ""),
            subtitle = json.optString("subtitle", ""),
            imageUrl = json.optString("imageUrl", ""),
            imageSha256 = json.optString("imageSha256", "").lowercase(),
            linkUrl = json.optString("linkUrl", ""),
            durationMs = (json.optInt("durationMs", 3000)).coerceIn(500, 15_000),
            revision = json.optLong("revision", 0L),
            fetchedAtMillis = json.optLong("fetchedAtMillis", fallbackFetchedAt),
        )

        fun fromItem(item: SplashAdItem, revision: Long, nowMillis: Long): SplashAdCache = SplashAdCache(
            id = item.id,
            title = item.title,
            subtitle = item.subtitle,
            imageUrl = item.imageUrl,
            imageSha256 = item.imageSha256,
            linkUrl = item.linkUrl,
            durationMs = item.durationMs,
            revision = revision,
            fetchedAtMillis = nowMillis,
        )
    }
}

/**
 * 这一次头屏要把广告盖多久（毫秒）。**0 表示不放广告。**
 *
 * 服务端下发的 `durationMs`（后台填，被服务端钳在 500～15000）是运营的意图，
 * 但不能直接拿来当展示时长：头屏是「进主界面前的一段等待」，而这段等待有多长**不由广告说了算**。
 * 这台 PLQ110 上主界面 0.4 秒就画好了（TV5701 上要 8~9 秒，这个功能当初就是照那个节奏写的），
 * 头屏跟着就收 —— 广告连一帧都留不住。实测在修之前，曝光记了一次又一次，
 * 屏幕上却一次都没出现过。
 *
 * 所以展示时长由客户端兜底，钳进 `[MIN_VISIBLE_MILLIS, MAX_VISIBLE_MILLIS]`：
 * 下限保证广告真的看得见，上限保证一次配置失误（比如后台填了 15000）不会让这台 kiosk
 * 每次冷启动都干等 15 秒。运营填的值只在这个区间内生效。
 *
 * 填 0 或负数当作「不放广告」—— 后台那个字段允许填 0，这是它唯一说得通的含义。
 */
internal val SplashAdCache.visibleMillis: Long
    get() = if (durationMs <= 0) {
        0L
    } else {
        durationMs.toLong().coerceIn(MIN_VISIBLE_MILLIS, MAX_VISIBLE_MILLIS)
    }

/** 广告最少要露多久。低于这个时长等于没放，而它已经开始占用冷启动时间了。 */
internal const val MIN_VISIBLE_MILLIS: Long = 2_000

/** 广告最多能占多久冷启动。后台填得再大也只到这儿。 */
internal const val MAX_VISIBLE_MILLIS: Long = 5_000
