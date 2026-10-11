package me.xiaozhi.androidclient.device

/**
 * 这台一体机上能用语音点的两样硬件：**摄像头云台**和**补光灯 / 红外灯**。
 *
 * ## 为什么是这么两样（2026-10-11）
 *
 * 参考 `workdayAlarmClockAndroid` 的 `MeBgCamCtrl`——那是给"老王家卖的 BodyGuargz 监控"
 * 写的 App。用户指出 TV5701 就是那台设备，真机 `getprop` / `sysfs` 也对得上：
 *
 * | 参考实现里的东西 | TV5701 上的对应物 |
 * |---|---|
 * | `MeBgCamCtrl` 注释里写的 MT6739 | `ro.mediatek.platform=MT6739`、`ro.boot.hardware=mt8765` |
 * | `PowerManager.setSysClassStringValue` 等 4 个厂商扩展 | 全在本机 `boot-framework.vdex` 里 |
 * | `/sys/bus/platform/drivers/aw9963e/aw9963e/aw9963eledN` | 驱动已绑定，`aw9963eled1..4` 都在 |
 * | `setRotationAngleDir/Enable/Status` 驱动的云台 | `/sys/devices/platform/stepmotor`，驱动 `stepperMotor` |
 * | `infrared` / `pir` / `ln4120` | 三个 platform 设备都在 |
 *
 * 所以这里只保留**灯和云台**两件事：用户要的就是"说左转就左转、说开灯就开灯"。
 * 人体/声音传感器是只读的，这轮不做。
 *
 * ## 这一层只放"规则"，不碰硬件
 *
 * 角度换算、灯的编号、状态解析都是纯函数，放在这里，好单测；真的去写 sysfs 的是
 * [BodyGuargzController]。二者的缝就是 [PanelHardware]。
 */

/**
 * aw9963e 的补光灯通道。
 *
 * [channel] 是 sysfs 节点名里的 N（`aw9963eledN`，1..4）；[register] 是写值里的第一个数
 * ——参考实现是 `val value = "${id * 2} ${if (on) 1 else 3}"`，所以 = channel × 2。
 *
 * 参考实现的网页界面只放出 `led1`/`led2` 两个按钮，也就是下面这两盏。`led3`/`led4`
 * 是有节点的，但没有任何界面在用，多半是同一批模组的另外两个通道，这轮不声明。
 */
enum class FillLight(val channel: Int, val label: String) {
    /** 下方补光灯（参考实现里的 led1 / 按钮"下方补光灯"）。 */
    BOTTOM(1, "下方补光灯"),

    /** 顶部补光灯（参考实现里的 led2 / 按钮"顶部补光灯"）。 */
    TOP(2, "顶部补光灯"),
    ;

    /** 写值里的寄存器号，参考实现写作 `id * 2`。 */
    val register: Int get() = channel * 2

    companion object {
        /** 亮。参考实现里写的是 1。 */
        const val CODE_ON = 1

        /** 灭。参考实现里写的是 3（不是 0）。 */
        const val CODE_OFF = 3

        /** 拼出 aw9963e 要的写值，形如 `"2 1"`。 */
        fun value(light: FillLight, on: Boolean): String =
            "${light.register} ${if (on) CODE_ON else CODE_OFF}"

        /**
         * 解析回读值。读不到就返回 null，**不要编一个"关"出来**——
         * 设置页和 `self.device.status` 都按 null 显示"读不到"。
         */
        fun parseState(raw: String?): Boolean? {
            val text = raw?.trim().orEmpty()
            if (text.isEmpty()) return null
            val last = text.split(' ').lastOrNull().orEmpty()
            return when (last) {
                CODE_ON.toString() -> true
                CODE_OFF.toString(), "0" -> false
                else -> null
            }
        }
    }
}

/** 一次云台转动。模型只能从这四个里挑，不给它自由填角度的口子。 */
enum class GimbalDirection {
    LEFT,
    RIGHT,

    /** 回到 0 度原点。 */
    HOME,

    /** 转到 `degrees` 指定的绝对角度。 */
    ANGLE,
    ;

    companion object {
        /** 认不出来就返回 null，由调用方回一句"参数不对"，别瞎猜方向。 */
        fun parse(raw: String?): GimbalDirection? = when (raw?.trim()?.lowercase()) {
            "left", "left_turn", "ccw" -> LEFT
            "right", "right_turn", "cw" -> RIGHT
            "home", "reset", "center" -> HOME
            "angle", "absolute", "goto" -> ANGLE
            else -> null
        }
    }
}

/** 云台上的角度，0..360。 */
object PanelAngles {
    /** 转盘的满量程。0 和 360 是同一个位置。 */
    const val FULL_TURN = 360

    /** 模型没说角度时，一次转这么多。 */
    const val DEFAULT_STEP = 30

    /** 不管上游给什么数，都收进 `0..359`（360 归 0——物理上就是原点）。 */
    fun normalize(degrees: Int): Int {
        val wrapped = degrees % FULL_TURN
        return if (wrapped < 0) wrapped + FULL_TURN else wrapped
    }

    /** 从 [current] 转了 [delta] 度之后落在哪。 */
    fun afterTurn(current: Int, delta: Int): Int = normalize(current + delta)

    /**
     * 步进电机认的单位，不是度。
     *
     * 这个系数是参考实现里实测出来的（`(target - current) * 1.3846154f`），没有别的来源；
     * 换机型前必须先确认它。
     */
    fun toMotorUnits(deltaDegrees: Int): Int = (deltaDegrees * MOTOR_UNITS_PER_DEGREE).toInt()

    private const val MOTOR_UNITS_PER_DEGREE = 1.3846154f
}

/** 设备当前状态的一句话快照。`null` = 这个值读不到，不要当成"关"。 */
data class PanelSnapshot(
    val angleDegrees: Int,
    /** 此刻电机还在转。 */
    val rotating: Boolean,
    val bottomLightOn: Boolean?,
    val topLightOn: Boolean?,
    val infraredOn: Boolean?,
)

/**
 * 云台 + 灯光的硬件访问口。
 *
 * 真机实现是 [BodyGuargzController]；单测里换成假的，这样工具层的参数解析、
 * 文案和开关逻辑不用连真板子也能测。
 */
interface PanelHardware {
    /** 云台能不能控制。 */
    val gimbalAvailable: Boolean

    /** 补光灯 / 红外灯能不能控制。 */
    val lightAvailable: Boolean

    fun setFillLight(light: FillLight, on: Boolean): Boolean

    fun setInfrared(on: Boolean): Boolean

    /** 从当前位置再转 [deltaDegrees] 度，正数向右。 */
    fun rotateBy(deltaDegrees: Int): Boolean

    /** 转到绝对角度 [degrees]。 */
    fun rotateTo(degrees: Int): Boolean

    fun snapshot(): PanelSnapshot

    /**
     * 把所有执行器拉回安全状态：停转、关灯。
     *
     * 用户在设置里关掉"本地设备控制"时调用——那一刻语音通道已经没了，
     * 再留着灯亮着、云台转着，用户就**没有任何办法**把它停下来了。
     */
    fun releaseAll()

    /** 放掉后台线程。 */
    fun close()
}
