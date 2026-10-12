package me.xiaozhi.androidclient.backend

import android.content.Context
import java.util.UUID

private const val PREFS_NAME = "xiaozhi_android_client_backend"
private const val KEY_INSTALL_ID = "install_id"
private const val KEY_LAST_HEARTBEAT_AT = "last_heartbeat_at"
private const val KEY_LAST_REPORTED_AT = "last_heartbeat_reported_at"
private const val KEY_DEVICE_STATUS = "device_status"
private const val KEY_HEARTBEAT_INTERVAL_SEC = "heartbeat_interval_sec"
private const val KEY_LAST_UPDATE_CHECK_AT = "last_update_check_at"

/**
 * 自建后端的运行期状态（心跳水位、设备状态、安装标识）。
 *
 * **基址不在这里**：它是 `BackendConfig.DEFAULT_BASE_URL` 这个编译期常量，客户端不给
 * 任何运行期覆盖的入口（曾经在「设备控制」页有个输入框，2026-10-12 撤掉了 —— 它是运维
 * 级的东西，让用户能改只会多出一种「这台机器跟别人不一样」的排查分支）。所以本类里
 * 只剩水位，一个比一个短命。
 *
 * ## 为什么不放进 [me.xiaozhi.androidclient.data.AppPreferences]
 *
 * 那边存的是一份完整的用户配置 [me.xiaozhi.androidclient.data.StoredConfig]，
 * 而 `MainViewModel.persist()` 是**整份重写**的：字段加进 StoredConfig 却忘了同步
 * 加进 `uiState`，用户改过的值就会被下一次任意设置变更悄悄写回默认值 ——
 * 这种 bug 只会在「改完、过一会儿又变回去了」的时候被发现，很难查。
 * 这里的值全是运行期水位（时间戳、状态），跟用户配置本来就不是一类东西，
 * 单独一个 prefs 文件两边都干净。
 */
internal class BackendSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 本次安装的标识，用于在后台看出「同一台设备重装过几次」。
     *
     * 注意它**不是**可靠的卸载信号：`AndroidManifest` 里 `allowBackup="true"`，
     * 重装时系统可能把 prefs 一起还原回来。只当参考值看，别拿它做去重主键 ——
     * 主键是 device_uid。
     */
    val installId: String
        get() {
            prefs.getString(KEY_INSTALL_ID, null)?.takeIf { it.isNotBlank() }?.let { return it }
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_INSTALL_ID, generated).apply()
            return generated
        }

    /** 最近一次**成功**心跳的时刻（epoch millis）。0 表示从没成功过。 */
    var lastHeartbeatAt: Long
        get() = prefs.getLong(KEY_LAST_HEARTBEAT_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_HEARTBEAT_AT, value).apply()

    /**
     * 最近一次**送达**心跳的时刻 —— 成功和被停用（403）都算，失败不算。
     *
     * 两条上报路径（App 内的协程循环、WorkManager 兜底）靠它去重，避免同一轮写两行日志。
     * 失败刻意不更新：服务器挂着的时候循环要能退避重试，不能让去重把自己的重试挡住。
     */
    var lastHeartbeatReportedAt: Long
        get() = prefs.getLong(KEY_LAST_REPORTED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_REPORTED_AT, value).apply()

    /** 1 正常 / 0 已被服务端停用。停用后停止上报，见 [HeartbeatOutcome.Disabled]。 */
    var deviceStatus: Int
        get() = prefs.getInt(KEY_DEVICE_STATUS, 1)
        set(value) = prefs.edit().putInt(KEY_DEVICE_STATUS, value).apply()

    /** 服务端下发的心跳间隔。Worker 那条路径也要按它去重，所以落盘。 */
    var heartbeatIntervalSec: Int
        get() = BackendConfig.clampHeartbeatInterval(
            prefs.getInt(KEY_HEARTBEAT_INTERVAL_SEC, BackendConfig.DEFAULT_HEARTBEAT_INTERVAL_SECONDS),
        )
        set(value) = prefs.edit()
            .putInt(KEY_HEARTBEAT_INTERVAL_SEC, BackendConfig.clampHeartbeatInterval(value))
            .apply()

    /** 最近一次静默检查更新的时刻，用来把冷启动检查降到「每 24 小时最多一次」。 */
    var lastUpdateCheckAt: Long
        get() = prefs.getLong(KEY_LAST_UPDATE_CHECK_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_UPDATE_CHECK_AT, value).apply()
}
