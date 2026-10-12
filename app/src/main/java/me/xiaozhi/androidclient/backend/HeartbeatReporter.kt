package me.xiaozhi.androidclient.backend

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import me.xiaozhi.androidclient.BuildConfig

/**
 * 心跳结果。
 *
 * 刻意做成密封类型而不是 `Result<HeartbeatResult>`：调用方必须区分
 * 「送不出去」和「服务端说这台设备被停用」—— 后者要停掉上报并记进水位，
 * 前者只是又一次静默失败。混在一个 IOException 里迟早会被 catch 成同一件事。
 */
internal sealed interface HeartbeatOutcome {
    data class Reported(val result: HeartbeatResult) : HeartbeatOutcome

    /** 服务端明确停用了这台设备（code=403）。 */
    data object Disabled : HeartbeatOutcome

    /** 本轮无事可做：没配密钥、距上次送达太近、或还在停用探测的冷却期内。 */
    data object Skipped : HeartbeatOutcome

    data class Failed(val error: Throwable) : HeartbeatOutcome
}

/**
 * 接口① 心跳上报。
 *
 * 这条链路是纯旁路：**它的任何结果都不影响 App 的任何本地功能**。服务器是自托管的、
 * 随时可能挂，所以失败只记日志，不弹 UI、不打断语音会话、不阻塞启动。
 *
 * 上报的唯一标识是 `deviceUid`（客户端那串伪 MAC，同时也是 OTA 请求里的 `Device-Id`），
 * `androidId` / `serialNumber` / `clientId` / `installId` 都作为交叉核对字段一起带上 ——
 * 伪 MAC 是本地生成的，换台设备重算一次就对不上了，多几列才有得比。
 */
internal class HeartbeatReporter(
    context: Context,
    private val apiClient: BackendApiClient,
    private val settings: BackendSettings,
    private val deviceUid: () -> String,
    private val clientId: () -> String,
    private val serialNumber: () -> String,
) {
    private val appContext = context.applicationContext

    /**
     * @param source 见服务端 `heartbeat_log.source`：`app` 协程循环 / `worker` 兜底 /
     *   `boot` **App 冷启动**（不是设备开机 —— 服务端拿它累加 `app_open_count`）。
     */
    suspend fun report(source: String): HeartbeatOutcome {
        if (!BackendConfig.isConfigured) return HeartbeatOutcome.Skipped

        val now = System.currentTimeMillis()

        // 去重：协程循环和 WorkManager 兜底会撞车，窗口取间隔的一半。
        // 没有「强制跳过这一步」的入口 —— 设置页里那个「立即上报一次」按钮曾经是，
        // 2026-10-12 随那一整块界面一起撤掉了：撞车的两轮报的都是同一件事，
        // 少报一轮没有任何代价。
        if (now - settings.lastHeartbeatReportedAt < settings.heartbeatIntervalSec * 1000L / 2) {
            return HeartbeatOutcome.Skipped
        }

        // 被停用后停止上报，但不能永远不问：运营在后台把设备重新启用时，客户端
        // 得能自己发现，而不是等用户去清应用数据。所以留一个低频探测。
        if (settings.deviceStatus == 0 &&
            now - settings.lastHeartbeatReportedAt < DISABLED_PROBE_INTERVAL_MS
        ) {
            return HeartbeatOutcome.Disabled
        }

        val uid = deviceUid()
        if (uid.isBlank()) {
            return HeartbeatOutcome.Failed(IllegalStateException("deviceUid 为空，跳过心跳"))
        }

        val payload = buildPayload(uid, source).toJson()
        val envelope = apiClient.post(BackendConfig.DEFAULT_BASE_URL, BackendConfig.PATH_HEARTBEAT, payload)
            .getOrElse { return HeartbeatOutcome.Failed(it) }

        return when (envelope.code) {
            BackendCode.OK -> {
                val result = HeartbeatResult.parse(envelope.data)
                settings.lastHeartbeatReportedAt = now
                settings.lastHeartbeatAt = now
                settings.deviceStatus = result.deviceStatus
                // 服务端下发间隔：以后改频率只要改数据库，不用重新发包。
                settings.heartbeatIntervalSec = result.intervalSec
                HeartbeatOutcome.Reported(result)
            }

            // 403 也是「送达」了 —— 更新去重水位，免得两条上报路径对着服务器连撞两轮。
            BackendCode.FORBIDDEN -> {
                settings.lastHeartbeatReportedAt = now
                settings.deviceStatus = 0
                HeartbeatOutcome.Disabled
            }

            else -> HeartbeatOutcome.Failed(BackendApiException(envelope.code, envelope.message))
        }
    }

    private fun buildPayload(uid: String, source: String): HeartbeatRequest {
        val metrics = appContext.resources.displayMetrics
        return HeartbeatRequest(
            deviceUid = uid,
            clientId = clientId(),
            androidId = androidId(),
            // 服务端的 mac_address 是 device_uid 的冗余副本，只为人工核对，不是另一种唯一码。
            mac = uid,
            serialNumber = serialNumber(),
            installId = settings.installId,
            brand = Build.BRAND.orEmpty(),
            manufacturer = Build.MANUFACTURER.orEmpty(),
            model = Build.MODEL.orEmpty(),
            // 和 model 刻意分开：MODEL 是商品名（PLQ110），DEVICE 是板子名（rk3568）。
            // 排查「哪一批硬件有问题」时，后者比前者有用。
            deviceName = Build.DEVICE.orEmpty(),
            osVersion = Build.VERSION.RELEASE.orEmpty(),
            sdkInt = Build.VERSION.SDK_INT,
            appVersionCode = BuildConfig.VERSION_CODE,
            appVersionName = BuildConfig.VERSION_NAME,
            screenWidth = metrics.widthPixels,
            screenHeight = metrics.heightPixels,
            netType = netType(),
            source = source,
        )
    }

    private fun androidId(): String = runCatching {
        Settings.Secure.getString(appContext.contentResolver, Settings.Secure.ANDROID_ID)
    }.getOrNull().orEmpty()

    /**
     * 网络类型标签，和服务端 `net_type` 的取值域（wifi/cellular/ethernet/none）对齐。
     *
     * 全部包在 runCatching 里：这是个纯装饰性字段，任何异常都不值得让一次心跳失败。
     */
    private fun netType(): String = runCatching {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return@runCatching "unknown"
        val capabilities = manager.activeNetwork
            ?.let { manager.getNetworkCapabilities(it) }
            ?: return@runCatching "none"
        when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH) -> "bluetooth"
            else -> "other"
        }
    }.getOrDefault("unknown")

    companion object {
        const val SOURCE_APP = "app"
        const val SOURCE_WORKER = "worker"
        const val SOURCE_BOOT = "boot"

        /** 被停用后的探测周期。够久，不至于骚扰服务器；够短，运营重新启用当天就能自己恢复。 */
        private const val DISABLED_PROBE_INTERVAL_MS = 6L * 60 * 60 * 1000
    }
}
