package me.xiaozhi.androidclient.audio

import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.SystemClock
import android.util.Log

/**
 * 挑一个"真的能拾音"的输入设备。
 *
 * ## 为什么需要它（2026-09-21 真机事故）
 *
 * 这台 RK3568 工程样机有两个输入设备：
 *  - `card 2` USB 摄像头麦（DECXIN）—— **唯一真正能拾音的**；
 *  - `card 1` 板载 codec 的采集通路 —— **板上没有焊咪头**，ADC 采到的是平坦噪声
 *    （实测 40 秒录音 RMS 恒定 7.4~7.6，毫无起伏）。
 *
 * 原本的写法是"启动时 `getDevices()` 查一次，选完就再也不管"。而 **USB 音频设备常常
 * 在 App 启动之后才被系统枚举出来** —— 实测抓到同一个进程里：
 *
 * ```
 * 16:11:57  [KWS] preferred input device=SSRK3568-HMI.6.1.75 type=15   ← 板载麦，聋的
 * 16:13:08  [KWS] preferred input device=USB-Audio - DECXIN  type=11   ← 一分钟后 USB 才出现
 * ```
 *
 * 于是设备绑死在板载麦上：**唤醒不了、接话也没反应，而界面还写着「正在监听唤醒词」**，
 * 用户完全不知道发生了什么，只能重启。这一版又把底栏的手动麦克风键去掉了，
 * 连"手动点一下"的兜底也没有了，所以必须在这里根治。
 *
 * ## 做法
 *
 * 找不到 USB/有线麦时**等一小会儿**（USB 枚举通常几百毫秒到几秒），仍然没有才退到板载麦，
 * 并且**大声记一条警告**——下次再出这种事，日志里第一眼就能看到，不用再从头查。
 *
 * ## 例外：内置麦本身就能拾音的机器
 *
 * 上面这套"等 USB、退板载、发警告"的前提是**板载通路是聋的**——那是 RK3568 工程样机的
 * 硬件事实，不是通用规律。**绝大多数机器（手机、一体机面板）板上是焊了麦克风的**，
 * 把前者原样搬过去会让它们永远卡在"没有检测到外接麦克风"的自检页上
 * （详见 [DeviceAudioProfile] 的注释，那里记了判据两次翻转的由来）。
 *
 * 所以"等不等 USB""要不要报警"现在按机型分叉：**只有确认没焊咪头的那批老板子**
 * （[DeviceAudioProfile.isDeafBuiltinModel]）才空等、才报警，其余机器直接采用内置麦。
 * 外接设备仍然是**优先级更高**的那条路——采集/唤醒循环每帧都会复查路由
 * （`findPreferredInputDevice` / `recheckInputDevice`），USB 麦晚到也会自动切过去。
 */
object InputDeviceSelector {

    private val identities = java.util.concurrent.ConcurrentHashMap<String, UsbAudioIdentity>()

    fun invalidateIdentities() {
        identities.clear()
    }

    private fun usbIdentity(address: String): UsbAudioIdentity? {
        identities[address]?.let { return it }
        val card = Regex("^card=(\\d+);device=\\d+;?$").matchEntire(address)?.groupValues?.get(1) ?: return null
        return runCatching {
            val parts = java.io.File("/proc/asound/card$card/usbid").readText().trim().split(':')
            UsbAudioIdentity(parts[0].toInt(16), parts[1].toInt(16)).also { identities[address] = it }
        }.getOrNull()
    }

    private const val TAG = "XiaozhiClient"

    /** 找不到 USB/有线麦时，最多等它出现这么久。 */
    private const val USB_WAIT_MS = 2_000L

    /** 等待时的轮询间隔。 */
    private const val POLL_STEP_MS = 200L

    fun isUsbInput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> false
    }

    fun endpoint(device: AudioDeviceInfo): AudioEndpoint = AudioEndpoint(
        name = device.productName.toString(),
        address = device.address,
        usb = isUsbInput(device),
        identity = if (isUsbInput(device)) usbIdentity(device.address) else null,
    )

    fun integratedOutput(manager: AudioManager, input: AudioDeviceInfo?): AudioDeviceInfo? {
        val outputs = manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()
        val selected = AudioHardwareProfilePolicy.pairedOutput(input?.let(::endpoint), outputs.map(::endpoint))
            ?: return null
        return outputs.firstOrNull { endpoint(it) == selected }
    }

    fun isWiredInput(device: AudioDeviceInfo): Boolean = when (device.type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET -> true
        else -> false
    }

    fun inputsOf(manager: AudioManager): List<AudioDeviceInfo> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            manager.getDevices(AudioManager.GET_DEVICES_INPUTS).toList()
        } else {
            emptyList()
        }

    fun preferredExternal(devices: List<AudioDeviceInfo>, manager: AudioManager): AudioDeviceInfo? =
        devices.firstOrNull { integratedOutput(manager, it) != null }
            ?: devices.firstOrNull(::isUsbInput)
            ?: devices.firstOrNull(::isWiredInput)

    /**
     * 选输入设备，**会为 USB 麦短暂等待**。只能在后台线程调用。
     *
     * 唯一的例外是**确认没焊咪头的老板子**（[DeviceAudioProfile.isDeafBuiltinModel]）：
     * 只有那里才需要等 USB 麦；其余机器直接采用内置麦，不等也不报警。
     *
     * @param preferBuiltin 设备端 AEC 要求采集与播放落在同一张声卡上，那种模式下必须钉板载麦
     *   （本机 `ECHO_CANCEL_MODE` 是 NONE，所以走的是 false 这条路）。
     * @param waitMs 等待 USB/有线麦出现的上限。**只是想"看一眼当前路由是什么"时传 0**，
     *   否则光是刷新一下状态栏文案就会卡两秒。
     */
    fun select(
        manager: AudioManager,
        preferBuiltin: Boolean,
        waitMs: Long = USB_WAIT_MS,
    ): AudioDeviceInfo? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
        val devices = inputsOf(manager)
        if (devices.isEmpty()) return null
        val builtin = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }

        if (preferBuiltin) {
            return builtin ?: devices.firstOrNull(::isUsbInput) ?: devices.first()
        }

        val immediate = preferredExternal(devices, manager)
        if (immediate != null) return immediate

        // 默认认为本机内置麦能拾音（只有确认没焊咪头的老板子才走不到这里）：直接采用，
        // 不空等那两秒。等它毫无意义——这台机器不靠 USB 麦也能用，而每次起采都先卡两秒
        // 会变成"叫了它要愣一下才应"。USB 麦真接上时，采集循环每帧复查路由会自动切过去。
        if (builtin != null && DeviceAudioProfile.supportsBuiltinMic()) {
            Log.d(
                TAG,
                "[CAPTURE] 本机内置麦可用（${builtin.productName}），直接采用；" +
                    DeviceAudioProfile.describe(),
            )
            return builtin
        }

        // 走到这里说明"这一刻"没有 USB/有线麦。它很可能只是还没枚举完 —— 等一会儿再看。
        val startedAt = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startedAt < waitMs) {
            SystemClock.sleep(POLL_STEP_MS)
            val again = inputsOf(manager)
            val found = preferredExternal(again, manager)
            if (found != null) {
                Log.d(
                    TAG,
                    "[CAPTURE] 等待 ${SystemClock.elapsedRealtime() - startedAt}ms 后等到 USB/有线麦克风：" +
                        "${found.productName} type=${found.type}",
                )
                return found
            }
        }

        // 走到这里只有两种可能：这台机器压根没有内置麦，或者它是那批"没焊咪头"的老板子
        // ——内置麦能用的机器在上面就 return 了。两种情况都得把话说明白，
        // 别让人对着一块听不见的板子猜。
        if (builtin == null) {
            Log.w(TAG, "[CAPTURE] 等不到 USB/有线麦克风，本机也没有内置麦，设备会完全听不见。")
        } else {
            Log.w(
                TAG,
                "[CAPTURE] 等不到 USB/有线麦克风，只能退回板载麦（${builtin.productName}）。" +
                    "⚠ 本机是「板载采集通路没焊咪头」的机型（${DeviceAudioProfile.describe()}），" +
                    "退到它等于听不见，界面上也不会有提示 —— 请检查 USB 摄像头是否插好。",
            )
        }
        return builtin
            ?: devices.firstOrNull(::isUsbInput)
            ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
            ?: devices.first()
    }
}

/**
 * 输入设备的健康状态。用于**开机自检**和**常驻提示**。
 *
 * 为什么要单独判"有没有 USB/有线麦"、而不是"有没有任何输入设备"：
 * RK3568 那台板子的板载采集通路**没有焊咪头**，它在系统里永远是一个"存在但听不见"的
 * 设备，所以"有输入设备"这个判据会永远为真、什么也发现不了。
 * 产品形态上摄像头麦是标配，因此在那个硬件上**它的在位与否才是真正的健康判据**。
 *
 * 但"板载麦是聋的"是**那台机器的硬件事实**，不是通用规律：TV5701 一体机的内置麦是真的
 * 能拾音的，拿前一条判据去套它，会把一台好机器报成"坏了"（2026-10-11 实测卡在自检页）。
 * 所以判据现在是 [canCapture]：外接麦在位，**或者**本机内置麦本身可用。
 */
data class AudioInputHealth(
    /** USB / 有线麦克风是否在位。 */
    val externalMicPresent: Boolean,
    /**
     * 本机内置麦这次检查**确实可用**：机型不在"没焊咪头"的黑名单里（见 [DeviceAudioProfile]）
     * 且系统里真有 `TYPE_BUILTIN_MIC` 设备。两个条件缺一不可——机型默认可信但设备不在，
     * 照样是听不见。
     */
    val builtinMicUsable: Boolean,
    /** 当前实际选中的设备名，给用户看。 */
    val label: String,
    /** 是否只剩板载麦、而本机板载采集通路不可用（这时设备是真的听不见）。 */
    val onlyBuiltin: Boolean,
) {
    /** 这台设备现在**能不能拾音**。开机自检、常驻提示、底栏文案都看它。 */
    val canCapture: Boolean
        get() = externalMicPresent || builtinMicUsable

    /** 一句话描述，可以直接显示给用户。 */
    val message: String
        get() = when {
            externalMicPresent -> "麦克风：$label"
            builtinMicUsable -> "麦克风：$label（内置）"
            onlyBuiltin -> "没有检测到外接麦克风，请检查 USB 音频连接"
            else -> "没有检测到任何麦克风"
        }
}

/**
 * 查一次输入设备健康状态，**不等**（waitMs = 0）——它会被定期调用，不能阻塞。
 */
fun InputDeviceSelector.health(manager: AudioManager, preferBuiltin: Boolean): AudioInputHealth {
    val devices = inputsOf(manager)
    val external = preferredExternal(devices, manager)
    val builtin = devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
    val builtinUsable = builtin != null && DeviceAudioProfile.supportsBuiltinMic()
    val chosen = if (preferBuiltin) builtin ?: external else external ?: builtin
    return AudioInputHealth(
        externalMicPresent = external != null,
        builtinMicUsable = builtinUsable,
        label = chosen?.productName?.toString().orEmpty().ifBlank { "未知设备" },
        onlyBuiltin = external == null && builtin != null && !builtinUsable,
    )
}
