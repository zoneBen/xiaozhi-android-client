package me.xiaozhi.androidclient.backend

import me.xiaozhi.androidclient.BuildConfig

/**
 * 自建后端（`http://api.mrcong.cn:300/apps/xiaozhi_android_client`）的常量与地址拼装。
 *
 * ## 关于密钥的一点实话
 *
 * [appSecret] 来自 `.backend.local` → `BuildConfig.BACKEND_APP_SECRET`，不进源码。
 * 那一步防的是「密钥被提交进这个公开仓库」，**不是**「密钥被逆向」—— 它最终会
 * 编译进 APK，谁都提得出来。
 *
 * 所以接口签名只能挡住「随手抓包刷接口」，不是安全边界。真正的防护是服务端
 * device 表的 `status` 字段（可停用设备）加上服务器层面的限流。
 *
 * ## 为什么全用 http 而不是 https
 *
 * 这台服务器只开在 `:300` 上没有证书，客户端发的又是不涉密的设备元数据、
 * 且校验依赖的是签名而不是传输保密，所以明文可接受。`AndroidManifest` 里
 * `usesCleartextTraffic="true"` 本来就开着。
 */
object BackendConfig {

    /** 默认接口基址。编译期写死，没有运行期覆盖入口。 */
    const val DEFAULT_BASE_URL: String = "http://api.mrcong.cn:300/apps/xiaozhi_android_client"

    /** 不参与保密，只用于服务端识别来源。与 php/config.example.php 的 sign.app_key 一致。 */
    const val APP_KEY: String = "xiaozhi-android-client"

    /** 为空表示没配置密钥：此时所有后端请求直接跳过，而不是发一堆注定 401 的包。 */
    val appSecret: String get() = BuildConfig.BACKEND_APP_SECRET

    val isConfigured: Boolean get() = appSecret.isNotBlank()

    // ---- 超时 --------------------------------------------------------------
    //
    // 刻意短。心跳和开屏拉取都是「能拿到就赚，拿不到就算了」，而服务器是自托管的、
    // 随时可能挂 —— 共用 MainViewModel 那个 connect 20s / read 30s 的客户端意味着
    // 服务器挂掉时每一轮都要白等 20 秒。没有任何理由等那么久。
    const val CONNECT_TIMEOUT_SECONDS: Long = 5
    const val READ_TIMEOUT_SECONDS: Long = 8
    const val WRITE_TIMEOUT_SECONDS: Long = 8

    // ---- 心跳 --------------------------------------------------------------

    /** 服务端没下发时用的间隔。服务端可通过 heartbeat_interval_sec 覆盖。 */
    const val DEFAULT_HEARTBEAT_INTERVAL_SECONDS: Int = 900

    /** 服务端下发的值会被钳到这个区间：太短是骚扰服务器，太长等于没心跳。 */
    const val MIN_HEARTBEAT_INTERVAL_SECONDS: Int = 60
    const val MAX_HEARTBEAT_INTERVAL_SECONDS: Int = 3600

    // ---- 路径 --------------------------------------------------------------

    const val PATH_HEARTBEAT: String = "/v1/heartbeat.php"
    const val PATH_SPLASH: String = "/v1/splash.php"
    const val PATH_UPGRADE: String = "/v1/upgrade.php"

    /** 拼出完整地址。容忍基址末尾多一个斜杠。 */
    fun endpoint(baseUrl: String, path: String): String {
        val base = baseUrl.trim().trimEnd('/')
        return if (base.isEmpty()) path else base + path
    }

    /** 把服务端下发的间隔钳到合法区间。 */
    fun clampHeartbeatInterval(seconds: Int): Int =
        seconds.coerceIn(MIN_HEARTBEAT_INTERVAL_SECONDS, MAX_HEARTBEAT_INTERVAL_SECONDS)
}
