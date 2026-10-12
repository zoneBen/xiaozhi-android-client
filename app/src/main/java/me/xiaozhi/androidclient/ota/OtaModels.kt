package me.xiaozhi.androidclient.ota

data class OtaVersionInfo(
    val versionCode: Int,
    val versionName: String,
    val downloadUrl: String,
    val sha256: String,
    val releaseNotes: String = "",
    val forceUpdate: Boolean = false,
)

sealed interface UpdateCheckResult {
    data object UpToDate : UpdateCheckResult
    data class HasUpdate(val info: OtaVersionInfo) : UpdateCheckResult
    data class Error(val message: String) : UpdateCheckResult

    /**
     * 更新源压根没配（见 [isUpdateIndexConfigured]）。
     *
     * 现在只有在**客户端没配 appSecret** 时才会出现：那种情况下 `MainViewModel` 会把
     * 更新源给成 `.invalid` 占位，免得往后端发一个注定 401 的请求。
     *
     * 刻意**不复用 [Error]**：`Error` 会被界面渲染成「检查更新失败: …」+ 一句网络层
     * 的英文原文，而这里根本不是失败——是这个版本没有这个功能。两者的用户动作完全不同
     * （一个是查网络，一个是别查了），所以分成两个分支，让编译器逼着每个调用点表态。
     */
    data object NotConfigured : UpdateCheckResult
}

sealed interface DownloadProgressState {
    data object Idle : DownloadProgressState
    data class Downloading(val progressPercent: Int, val bytesDownloaded: Long, val totalBytes: Long) : DownloadProgressState
    data object Verifying : DownloadProgressState
    data object ReadyToInstall : DownloadProgressState
    data class Failed(val error: String) : DownloadProgressState
}
