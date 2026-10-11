package me.xiaozhi.androidclient.device

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.util.Log
import java.io.File
import java.lang.reflect.Method
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "XiaozhiClient"

/**
 * BodyGuargz（TV5701 一体机）的云台 + 补光灯驱动，实现见 [PanelHardware]。
 *
 * ## 为什么不直接写 sysfs（2026-10-11）
 *
 * 这台机器的 `/sys/bus/platform/drivers/aw9963e/aw9963e/aw9963eled1`、`/sys/devices/`
 * `platform/infrared/infrared_debug` 这些节点**连 shell 都读不了**（SELinux 拦着，
 * `adb root` 也降级回 shell）。参考实现 `MeBgCamCtrl` 的做法是绕过去：厂商在
 * `android.os.PowerManager` 上挂了几个扩展方法
 *
 * ```
 * setSysClassStringValue(String path, String value)   // 往任意 sysfs 节点写字符串
 * setRotationAngleDir(int)  setRotationAngleEnable(bool)  getRotationAngleStatus()
 * ```
 *
 * 本机固件里这四个方法**都在**（在 `/system/framework/boot-framework.vdex` 里各出现两次：
 * 方法名与签名），所以照抄参考实现，用反射调它们，App 自己不需要文件权限。
 *
 * ## 线程
 *
 * 步进电机一次只认一条指令：两次语音指令挨得太近（"左转"接着"右转"）如果用不同线程写下去，
 * 会互相踩。所以云台所有动作都排在 [worker] 这一条线程上，和参考实现一致。
 * 灯的写入是同步的——它不进电机队列，也没有先后约束。
 *
 * ## 可用性判断
 *
 * 老实话：**只能证明厂商接口在，不能证明灯焊了、电机装了**。
 * [gimbalAvailable] / [lightAvailable] 看的就是那四个反射方法在不在；写失败会如实返回
 * false，工具层会把它翻成"写入失败"而不是假装成功。[describe] 额外把 sysfs 节点的存在性
 * 记进日志，方便真机排查（注意：节点那几项为 false 也可能是没权限看，不代表不存在）。
 */
class BodyGuargzController(context: Context) : PanelHardware {

    private val powerManager = context.applicationContext
        .getSystemService(Context.POWER_SERVICE) as? PowerManager

    private val writeMethod: Method? = findMethod(
        "setSysClassStringValue",
        String::class.java,
        String::class.java,
    )

    private val rotationDirectionMethod: Method? = findMethod(
        "setRotationAngleDir",
        Integer.TYPE,
    )

    private val rotationEnableMethod: Method? = findMethod(
        "setRotationAngleEnable",
        java.lang.Boolean.TYPE,
    )

    private val rotationStatusMethod: Method? = findMethod("getRotationAngleStatus")

    private fun findMethod(name: String, vararg parameterTypes: Class<*>): Method? =
        powerManager?.javaClass?.methods?.firstOrNull {
            it.name == name && it.parameterTypes.contentEquals(parameterTypes)
        }

    /**
     * 云台动作的执行线程。参考实现用的是主线程 Handler，这里换成自己的线程——
     * 电机响应慢，等它的那段不该占着 UI 线程。
     */
    private val worker = HandlerThread("bodyguargz-panel").apply { start() }
    private val handler = Handler(worker.looper)

    /** 我们记着的云台角度。硬件只报"转完了没有"，不报角度，所以只能自己记账。 */
    @Volatile
    private var currentAngleDegrees = 0

    private val angleLock = Any()
    private var pendingAngleDegrees: Int? = null

    /**
     * sysfs 只写不读时（`read()` 拿到空串或抛异常）拿这里的值兜底，
     * 免得设置页永远显示"读不到"。参考实现也是这么干的（`lastWrittenValues`）。
     */
    private val lastWrittenValues = ConcurrentHashMap<String, String>()

    /**
     * 转到位之后再接受下一条指令：电机忙的时候不能打断它。
     *
     * 等待是**非阻塞**的（只是把这次 runnable 再 post 一次），所以调用方发完就走，
     * 不会卡住 MCP 应答。
     */
    private val applyPendingAngle = object : Runnable {
        override fun run() {
            if (isRotating()) {
                handler.postDelayed(this, ANGLE_RETRY_MS)
                return
            }
            val target = synchronized(angleLock) {
                pendingAngleDegrees.also { pendingAngleDegrees = null }
            } ?: return
            val delta = PanelAngles.toMotorUnits(target - currentAngleDegrees)
            currentAngleDegrees = target
            if (delta != 0) {
                invoke(rotationDirectionMethod, delta)
                invoke(rotationEnableMethod, true)
            }
        }
    }

    override val gimbalAvailable: Boolean
        get() = rotationDirectionMethod != null &&
            rotationEnableMethod != null &&
            rotationStatusMethod != null

    override val lightAvailable: Boolean
        get() = writeMethod != null

    /** 给日志和设置页看的一句话：厂商接口在不在、sysfs 节点在不在。 */
    fun describe(): String = buildString {
        append("model=${Build.MODEL}")
        append(" 厂商接口[写节点=${writeMethod != null} 云台=$gimbalAvailable]")
        append(
            " sysfs节点[stepmotor=${File(STEPMOTOR_DIR).exists()}" +
                " led1=${File(ledPath(FillLight.BOTTOM)).exists()}" +
                " infrared=${File(INFRARED_NODE).exists()}]",
        )
        append("（节点为 false 也可能只是没权限看，不代表硬件不存在）")
    }

    /**
     * 设置页副标题里属于**云台 / 补光灯**的那半句。
     *
     * 音量那半句由 [SystemVolumeController.availabilityLabel] 提供，两句在 ViewModel 里拼起来。
     * 这里不再说"功能已自动停用"——音量是台 Android 设备就有，整体功能不会因为这台机器
     * 没灯就全停，那句话已经不成立了。
     */
    fun availabilityLabel(): String = when {
        gimbalAvailable && lightAvailable -> "云台和补光灯都能控制"
        gimbalAvailable -> "只能控制云台，没找到补光灯"
        lightAvailable -> "只能控制补光灯，没找到云台"
        else -> "没找到云台和补光灯"
    }

    override fun setFillLight(light: FillLight, on: Boolean): Boolean {
        val path = ledPath(light)
        // 参考实现：id=1 写 "2 1"（亮）/ "2 3"（灭）。
        val value = FillLight.value(light, on)
        val ok = write(path, value)
        Log.d(TAG, "[PANEL] ${light.label} ${if (on) "开" else "关"} -> $path='$value' ok=$ok")
        return ok
    }

    override fun setInfrared(on: Boolean): Boolean {
        val value = if (on) "1" else "0"
        val ok = write(INFRARED_NODE, value)
        Log.d(TAG, "[PANEL] 红外灯 ${if (on) "开" else "关"} -> $INFRARED_NODE='$value' ok=$ok")
        return ok
    }

    override fun rotateBy(deltaDegrees: Int): Boolean {
        if (!gimbalAvailable) return false
        queueAngle(PanelAngles.afterTurn(currentAngleDegrees, deltaDegrees))
        return true
    }

    override fun rotateTo(degrees: Int): Boolean {
        if (!gimbalAvailable) return false
        queueAngle(PanelAngles.normalize(degrees))
        return true
    }

    private fun queueAngle(target: Int) {
        Log.d(TAG, "[PANEL] 云台 ${currentAngleDegrees}° -> ${target}°")
        synchronized(angleLock) { pendingAngleDegrees = target }
        handler.removeCallbacks(applyPendingAngle)
        handler.post(applyPendingAngle)
    }

    override fun snapshot(): PanelSnapshot = PanelSnapshot(
        angleDegrees = currentAngleDegrees,
        rotating = isRotating(),
        bottomLightOn = FillLight.parseState(read(ledPath(FillLight.BOTTOM))),
        topLightOn = FillLight.parseState(read(ledPath(FillLight.TOP))),
        infraredOn = parseSwitchState(read(INFRARED_NODE)),
    )

    override fun releaseAll() {
        handler.removeCallbacks(applyPendingAngle)
        synchronized(angleLock) { pendingAngleDegrees = null }
        if (gimbalAvailable) {
            invoke(rotationEnableMethod, false)
        }
        lastWrittenValues.clear()
        FillLight.entries.forEach { setFillLight(it, false) }
        setInfrared(false)
    }

    override fun close() {
        handler.removeCallbacksAndMessages(null)
        worker.quitSafely()
    }

    private fun isRotating(): Boolean = (callResult(rotationStatusMethod) as? Int) == 1

    private fun ledPath(light: FillLight): String = "$LED_DRIVER_DIR/aw9963eled${light.channel}"

    private fun write(path: String, value: String): Boolean {
        val ok = invoke(writeMethod, path, value)
        if (ok) lastWrittenValues[path] = value
        return ok
    }

    /** 先读节点，读不到就退回"我们最后写进去的值"。 */
    private fun read(path: String): String? {
        val direct = runCatching { File(path).readText().trim().takeIf(String::isNotEmpty) }
            .getOrNull()
        return direct ?: lastWrittenValues[path]
    }

    private fun invoke(method: Method?, vararg args: Any): Boolean = try {
        if (method == null) {
            false
        } else {
            method.invoke(powerManager, *args)
            true
        }
    } catch (error: Exception) {
        // 反射失败是**要报出来的**：回 false 让工具层说"写入失败"，
        // 而不是吞掉异常、让用户以为灯坏了。
        Log.w(TAG, "[PANEL] 调用 ${method?.name} 失败：${error.message.orEmpty()}")
        false
    }

    private fun callResult(method: Method?): Any? = try {
        method?.invoke(powerManager)
    } catch (_: Exception) {
        null
    }

    companion object {
        /** 两次重试之间隔多久。参考实现是 200ms。 */
        private const val ANGLE_RETRY_MS = 200L

        private const val STEPMOTOR_DIR = "/sys/devices/platform/stepmotor"

        private const val LED_DRIVER_DIR = "/sys/bus/platform/drivers/aw9963e/aw9963e"

        private const val INFRARED_NODE = "/sys/devices/platform/infrared/infrared_debug"

        /**
         * 红外灯的开关值。参考实现是 `put("1")` / `put("0")`，
         * 回读时按同样的两个值判，认不出来就返回 null。
         */
        private fun parseSwitchState(raw: String?): Boolean? = when (raw?.trim()) {
            "1" -> true
            "0" -> false
            else -> null
        }
    }
}
