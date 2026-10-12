package me.xiaozhi.androidclient.backend

import android.content.Context
import java.util.concurrent.TimeUnit
import me.xiaozhi.androidclient.data.AppPreferences
import me.xiaozhi.androidclient.data.DeviceIdentityStore
import okhttp3.OkHttpClient

/**
 * 把后端这几个组件装起来。项目里没有 DI 容器（没有 Hilt/Koin），照旧手工 new。
 *
 * 存在的理由是 [HeartbeatWorker]：WorkManager 会在没有任何 Activity / ViewModel 的
 * 情况下把进程拉起来跑一次心跳，那条路径没法从 `MainViewModel` 拿 `deviceId` /
 * `clientId` / `serialNumber`，只能自己从 prefs 里读。同一套装配逻辑写两遍迟早会分叉。
 */
internal object BackendFactory {

    /**
     * 没有可复用的 [OkHttpClient] 时用的基客户端（Worker 那条路径）。
     *
     * 和 `MainViewModel` 里那个客户端是**两个连接池**，这是刻意的取舍：把
     * MainViewModel 的客户端做成全局单例，意味着为了 15 分钟一次的心跳去动
     * 语音长连接那条链路的所有权，代价远大于一个空闲连接池。
     */
    private val fallbackHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(BackendConfig.CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(BackendConfig.READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(BackendConfig.WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build()
    }

    fun apiClient(baseHttpClient: OkHttpClient? = null): BackendApiClient =
        BackendApiClient(baseHttpClient ?: fallbackHttpClient)

    fun settings(context: Context): BackendSettings = BackendSettings(context)

    /**
     * 装一个心跳上报器。
     *
     * ## deviceUid 用的是「物理设备标识」，不是当前角色的
     *
     * 角色（[me.xiaozhi.androidclient.model.RoleProfile]）各自带一串自己的 `deviceId`，
     * 切角色就会换一个 —— 那是用来在**小智官方**那边当独立设备注册的。要是这里也跟角色走，
     * 用户每切一次角色，后台设备列表就多一台设备，而那其实是同一台机器，设备台账直接废掉。
     *
     * 所以这里取的是 prefs 里那串不随角色变动的伪 MAC（也就是主角色 `xiaozhi` 的 deviceId），
     * 它对一台机器是恒定的。
     */
    fun heartbeatReporter(context: Context, baseHttpClient: OkHttpClient? = null): HeartbeatReporter {
        val appContext = context.applicationContext
        // load() 会在首次运行时补出 deviceId / clientId；下面那句同理补出 SN。
        // 这两步有写盘副作用，但它们本来就是「首次运行初始化身份」，谁先跑到都一样。
        val config = AppPreferences(appContext).load()
        val identity = DeviceIdentityStore(appContext).loadOrCreate(config.deviceId)
        return HeartbeatReporter(
            context = appContext,
            apiClient = apiClient(baseHttpClient),
            settings = settings(appContext),
            deviceUid = { config.deviceId },
            clientId = { config.clientId },
            serialNumber = { identity.serialNumber },
        )
    }
}
