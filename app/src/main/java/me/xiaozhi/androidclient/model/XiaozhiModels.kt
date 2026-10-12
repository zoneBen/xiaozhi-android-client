package me.xiaozhi.androidclient.model

import me.xiaozhi.androidclient.BuildConfig
import me.xiaozhi.androidclient.camera.CameraFacing
import org.json.JSONObject

data class OtaRequest(
    val otaUrl: String,
    val deviceId: String,
    val clientId: String,
    val serialNumber: String?,
    val appVersion: String,
)

data class WebsocketConfig(
    val url: String,
    val token: String?,
    val version: Int,
)

data class ActivationInfo(
    val message: String?,
    val code: String?,
    val challenge: String?,
    val timeoutMs: Int?,
)

data class OtaConfigResult(
    val websocket: WebsocketConfig?,
    val activation: ActivationInfo?,
    val rawBody: String,
)

data class ConnectParams(
    val url: String,
    val token: String?,
    val protocolVersion: Int,
    val deviceId: String,
    val clientId: String,
)

data class ServerHello(
    val sessionId: String?,
    val sampleRate: Int?,
    val frameDuration: Int?,
    val raw: JSONObject,
)

enum class ListeningMode(val wireValue: String) {
    AUTO("auto"),
    MANUAL("manual"),
    REALTIME("realtime"),
}

enum class ConnectionStatus {
    DISCONNECTED,
    FETCHING_CONFIG,
    ACTIVATING,
    CONNECTING,
    CONNECTED,
    FAILED,
}

data class LogLine(
    val timestamp: String,
    val message: String,
)

enum class ChatRole {
    USER,
    ASSISTANT,
    SYSTEM,
}

data class ChatMessage(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val timestamp: String,
    /**
     * 这条消息配的本地图片（目前只有摄像头拍照会产生）。null = 纯文字。
     *
     * 存**路径**而不是字节：`UiState` 每帧都被 Compose 读，把 `ByteArray` 放进来会让
     * 每次状态比较去比几万个字节，而且 Coil 本来就是按路径/File 懒加载的。
     * 文件不存在时界面按纯文字渲染（见 `ChatBubble`），所以路径失效不会崩。
     */
    val imagePath: String? = null,
)

data class ScheduledTaskUi(
    val id: String,
    val kind: String,
    val message: String,
    val status: String,
    val remainingSeconds: Long?,
)

data class UiState(
    val otaUrl: String = "",
    val deviceId: String = "",
    val clientId: String = "",
    val serialNumber: String = "",
    val assistantAvatarPath: String = "",
    val assistantPortraitPath: String = "",
    val idleVideoPath: String = "",
    val greetingVideoPath: String = "",
    val listeningVideoPath: String = "",
    val speakingVideoPath: String = "",
    val activeRoleAvatarPath: String = "",
    val activeRolePortraitPath: String = "",
    val activeRoleDigitalHumanReady: Boolean = false,
    val activeRoleIdleVideoPath: String = "",
    val activeRoleGreetingVideoPath: String = "",
    val activeRoleListeningVideoPath: String = "",
    val activeRoleSpeakingVideoPath: String = "",
    val websocketUrl: String = "",
    val authToken: String = "",
    val protocolVersion: String = "1",
    val mcpPayload: String = "",
    val activationMessage: String = "",
    val activationCode: String = "",
    val activationPending: Boolean = false,
    val activated: Boolean = false,
    val sessionId: String = "",
    val serverSampleRate: String = "",
    val serverFrameDuration: String = "",
    val lastIncomingType: String = "",
    val lastSttText: String = "",
    val lastTtsText: String = "",
    val audioRouteStatus: String = "媒体输出：扬声器 / 输入：机身麦克风",
    val isRecording: Boolean = false,
    val isAssistantSpeaking: Boolean = false,
    val isTurnActive: Boolean = false,
    val activeListeningMode: String = "",
    val wakeWordEnabled: Boolean = true,
    val wakeWords: String = "",
    val roleWakeWords: String = "",
    val activeRoleName: String = "小智",
    val primaryRoleName: String = "小智",
    val activeRoleId: String = "xiaozhi",
    val roleProfiles: List<RoleProfile> = emptyList(),
    val wakeWordStatus: String = "",

    /**
     * 麦克风能不能拾音。**开机自检与底栏常驻提示都用它。**
     *
     * 判据不是"有没有输入设备"——板载采集通路没焊咪头的老板子（RK3568 样机）在系统里
     * 永远存在一个"存在但听不见"的设备，用后者永远发现不了问题。所以由
     * `AudioInputHealth.canCapture` 给出：外接麦在位，或者本机内置麦可用
     * （机型不在"没焊咪头"黑名单里，见 `DeviceAudioProfile`）。
     */
    val microphoneReady: Boolean = true,

    /** 给用户看的一句话：没有麦克风时说明原因和怎么办。 */
    val microphoneMessage: String = "",
    val termuxEnabled: Boolean = false,
    val pythonPath: String = "",
    val pythonScriptPath: String = "",
    val pythonWorkdir: String = "",
    val pythonRuntimeStatus: String = "",
    val termuxApiCommand: String = "",
    val termuxApiArguments: String = "",
    val termuxApiStatus: String = "",
    val debugLoggingEnabled: Boolean = false,
    val debugWavDumpEnabled: Boolean = false,

    /**
     * 通用功能开关：**摄像头拍照 + 媒体音量**。用户要求"可以关闭"。
     *
     * 关掉后 `self.camera.take_photo` 与 `self.audio_speaker.set_volume` 不再向服务端声明，
     * 模型看不到也就调不到；设备自己发起的监督核验也会跳过拍照（见
     * `SupervisionPolicy.skipReasonWhenCameraDisabled`）——不然"关了还在拍"就是个谎。
     */
    val deviceControlEnabled: Boolean = true,

    /**
     * 硬件强相关开关：**云台 / 补光灯 / 红外灯**。
     *
     * 和 [deviceControlEnabled] 分开，是因为"这台机器有没有云台"是硬件事实，
     * 而"用户在不在意被语音控制"是意愿；混在一个开关里，用户为了关云台得连音量一起牺牲。
     */
    val hardwareControlEnabled: Boolean = true,

    /**
     * 本机**认不认得出**云台 / 补光灯（反射探测厂商标商接口的结果）。
     *
     * 为 false 时设置页把这个开关置灰：留着能点等于骗用户，打开也没东西可控制。
     * 注意它证的是"厂商接口在不在"，不是"灯和电机在不在"——一台机器可能接口在、硬件不在。
     */
    val hardwareControlAvailable: Boolean = false,

    /** 多摄像头时用哪一台。本机只有一个朝向时它不起作用。 */
    val cameraFacing: CameraFacing = CameraFacing.BACK,

    /**
     * 本机**内置**摄像头有哪几个朝向可选，用来决定要不要显示"用哪一个"那一行。
     *
     * 少于两项就没什么可选的，不显示。只算内置：外接在 `CameraSelection.choose` 里
     * 是无条件优先的，摆进来会造出"选了后置、实际拍的还是 USB"这种假选项。
     */
    val cameraFacingChoices: List<CameraFacing> = emptyList(),

    /** 设置页给用户看的一句话：本机到底认没认出云台和补光灯。 */
    val deviceControlStatus: String = "",

    /**
     * 上面那句自检结果是**什么时候**得出的，形如「刚刚」「3 小时前」。
     *
     * 硬件自检结果会缓存 24 小时（见 `HardwareProbeRepository`），所以"结论"必须带上"时间"：
     * 只显示结论的话，用户看到一行 8 小时前的判断却以为是刚测的，
     * 而点「重新自检」正是为了改变它。
     */
    val hardwareProbeStatus: String = "",
    val connectionStatus: ConnectionStatus = ConnectionStatus.DISCONNECTED,
    val isSilentTransportRecovery: Boolean = false,
    val draftMessage: String = "",
    val chatMessages: List<ChatMessage> = emptyList(),
    val scheduledTasks: List<ScheduledTaskUi> = emptyList(),
    // 版本号必须从构建信息读，**绝对不能硬编码**。
    //
    // 这里原先是写死的 "1.2.2" / 6，每次发版靠人工记得来改一行；结果 v1.2.3 就漏改了——
    // build.gradle 已经是 7/1.2.3，这里还停在 6/1.2.2，于是：
    //   1. 设置页永远显示"当前版本：v1.2.2 (Build 6)"，和实际装的包对不上；
    //   2. OTA 用的是 appVersionCode 做比较（checkForUpdate(currentCode)），
    //      装完新版后它仍然以为自己是 6，会**反复提示同一个更新**，升了等于没升。
    // 改成读 BuildConfig 之后，版本号只有 build.gradle.kts 一个来源，这类漏改不可能再发生。
    val appVersionName: String = BuildConfig.VERSION_NAME,
    val appVersionCode: Int = BuildConfig.VERSION_CODE,
    val updateCheckStatus: String = "",
    val availableUpdate: me.xiaozhi.androidclient.ota.OtaVersionInfo? = null,
    val isCheckingUpdate: Boolean = false,
    val isDownloadingUpdate: Boolean = false,
    val downloadProgressPercent: Int = 0,
    val logs: List<LogLine> = emptyList(),
)
