package me.xiaozhi.androidclient.mcp

import me.xiaozhi.androidclient.device.FillLight
import me.xiaozhi.androidclient.device.GimbalDirection
import me.xiaozhi.androidclient.device.PanelAngles
import me.xiaozhi.androidclient.device.PanelHardware
import me.xiaozhi.androidclient.device.VolumeHardware
import me.xiaozhi.androidclient.device.VolumeScale
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地 function call：把"左转""打开灯光""声音小一点"这类话接到这台设备的硬件上。
 *
 * ## 它在整条链路上的位置（2026-10-11）
 *
 * 本地 function call 的框架这个客户端**早就有**了（见 [McpCameraServer]：`tools/list` /
 * `tools/call` 走 WebSocket 里的 MCP，和 ESP32 那边 `main/mcp_server.cc` 是同一套协议）。
 * 这轮要做的不是再搭一遍，而是**多声明几个工具**——用 `self.<子系统>.<动作>` 的命名，
 * 和 ESP32 各板子的 `self.otto.action` / `self.AEC.set_mode` 一个路子。
 *
 * ## 两个子系统的差别：谁能被"关掉"
 *
 * 云台/补光灯（[PanelHardware]）只在 TV5701 这种板子上有；媒体音量（[VolumeHardware]）
 * 是台 Android 设备就有。所以非 TV5701 的机器上，开关打开后只会多出音量工具，
 * 云台和补光灯那两条不会声明——可用性各自探测，不互相拖累。
 *
 * ## 可关闭（用户明确要求）
 *
 * [enabled] 为 false 时 [schemas] 返回空——**这几个工具压根不向服务端声明**，
 * 模型不知道它们存在，就不可能误动作。会话中途关掉的话，服务端手里的旧列表还可能调过来，
 * 那时 [call] 会回一句"已关闭"，不会真的动硬件。
 *
 * ## 为什么单独一个类、不塞进 [McpCameraServer]
 *
 * 那边负责的是"JSON-RPC 收发 + 回复"，这边负责"中文参数怎么翻译成动作"。
 * 分开之后 [PanelHardware] / [VolumeHardware] 换成假的就能单测，不用连真板子。
 */
class DeviceToolSet(
    private val hardware: PanelHardware,
    private val volume: VolumeHardware,
    private val enabled: () -> Boolean,
) {
    /** 本工具集认领的工具名。 */
    val toolNames: Set<String> = setOf(GIMBAL_TOOL, LIGHT_TOOL, VOLUME_TOOL, STATUS_TOOL)

    fun isDeviceTool(name: String): Boolean = name in toolNames

    /** 声明给服务端的工具定义。功能关掉、或这台机器没有对应硬件时不声明。 */
    fun schemas(): List<JSONObject> {
        if (!enabled()) return emptyList()
        return buildList {
            if (hardware.gimbalAvailable) add(gimbalSchema())
            if (hardware.lightAvailable) add(lightSchema())
            if (volume.volumeAvailable) add(volumeSchema())
            // 状态工具只要有**任何**一样能报的东西就声明：它现在是云台、灯、音量三合一的入口，
            // 也是模型做"大一点/取消静音"这类相对操作前必须先查的地方。
            if (hardware.gimbalAvailable || hardware.lightAvailable || volume.volumeAvailable) {
                add(statusSchema())
            }
        }
    }

    fun call(name: String, args: JSONObject): DeviceToolOutcome {
        if (!isDeviceTool(name)) {
            return DeviceToolOutcome.Failure("Unknown tool: $name")
        }
        if (!enabled()) {
            // 会话中途关掉的情况：服务端手里还是旧列表，所以这条必须真的拦住。
            return DeviceToolOutcome.Failure("本机的本地设备控制已被用户关闭，无法操作云台、灯光和音量。")
        }
        return when (name) {
            GIMBAL_TOOL -> callGimbal(args)
            LIGHT_TOOL -> callLight(args)
            VOLUME_TOOL -> callVolume(args)
            else -> callStatus()
        }
    }

    private fun callGimbal(args: JSONObject): DeviceToolOutcome {
        if (!hardware.gimbalAvailable) {
            return DeviceToolOutcome.Failure("本机没有可用的云台，无法转动。")
        }
        val direction = GimbalDirection.parse(args.optString("direction"))
            ?: return DeviceToolOutcome.Failure("direction 只能是 left、right、home 或 angle。")
        val rawDegrees = args.optInt("degrees", PanelAngles.DEFAULT_STEP)

        return when (direction) {
            GimbalDirection.LEFT, GimbalDirection.RIGHT -> {
                if (rawDegrees !in 1..PanelAngles.FULL_TURN) {
                    return DeviceToolOutcome.Failure("degrees 必须在 1 到 360 之间。")
                }
                val delta = if (direction == GimbalDirection.LEFT) rawDegrees else -rawDegrees
                if (!hardware.rotateBy(delta)) {
                    return DeviceToolOutcome.Failure("云台指令发送失败，本机的云台可能不受支持。")
                }
                val side = if (direction == GimbalDirection.LEFT) "左" else "右"
                DeviceToolOutcome.Success("云台已开始向${side}转 $rawDegrees 度。")
            }

            GimbalDirection.HOME, GimbalDirection.ANGLE -> {
                val target = if (direction == GimbalDirection.HOME) 0 else rawDegrees
                if (target !in 0..PanelAngles.FULL_TURN) {
                    return DeviceToolOutcome.Failure("degrees 必须在 0 到 360 之间。")
                }
                if (!hardware.rotateTo(target)) {
                    return DeviceToolOutcome.Failure("云台指令发送失败，本机的云台可能不受支持。")
                }
                if (direction == GimbalDirection.HOME) {
                    DeviceToolOutcome.Success("云台正在回到原点，也就是 0 度。")
                } else {
                    DeviceToolOutcome.Success("云台正在转到 $target 度。")
                }
            }
        }
    }

    private fun callLight(args: JSONObject): DeviceToolOutcome {
        if (!hardware.lightAvailable) {
            return DeviceToolOutcome.Failure("本机没有可用的灯光控制。")
        }
        val target = LightTarget.parse(args.optString("target"))
            ?: return DeviceToolOutcome.Failure("target 只能是 bottom、top、all 或 infrared。")
        // on 是必填的：模型漏了就当"打开"太危险——灯会自己亮起来，
        // 而用户说的是什么谁也不知道。宁可回一句参数缺失让它重来。
        if (!args.has("on") || args.isNull("on")) {
            return DeviceToolOutcome.Failure("缺少 on 参数，请明确是打开还是关闭。")
        }
        val on = args.optBoolean("on")

        if (target.isInfrared) {
            if (!hardware.setInfrared(on)) {
                return DeviceToolOutcome.Failure("红外灯写入失败，本机可能没有这盏灯。")
            }
            return DeviceToolOutcome.Success("红外灯已${on.onOffVerb()}。")
        }

        val failed = target.fillLights.filterNot { hardware.setFillLight(it, on) }
        if (failed.size == target.fillLights.size) {
            return DeviceToolOutcome.Failure("${target.label}写入失败，本机可能没有这盏灯。")
        }
        val suffix = if (failed.isEmpty()) "" else "（${failed.joinToString("、") { it.label }}写入失败）"
        return DeviceToolOutcome.Success("${target.label}已${on.onOffVerb()}。$suffix")
    }

    private fun callVolume(args: JSONObject): DeviceToolOutcome {
        if (!volume.volumeAvailable) {
            return DeviceToolOutcome.Failure("本机没有可用的音量控制。")
        }
        if (!args.has("volume") || args.isNull("volume")) {
            return DeviceToolOutcome.Failure("缺少 volume 参数，请给出 0 到 100 之间的音量。")
        }
        // 越界就退回让模型重来，不悄悄夹到 100：schema 里已经写了 0-100，
        // 给到 150 是模型自己搞错了，夹一下会把错误盖掉。和云台的 degrees 一个处理方式。
        val requested = args.optInt("volume", -1)
        if (!VolumeScale.isWithinRange(requested)) {
            return DeviceToolOutcome.Failure("volume 必须在 0 到 100 之间。")
        }
        val actual = volume.setVolumePercent(requested)
            ?: return DeviceToolOutcome.Failure("音量设置失败，本机的音量可能改不了。")
        return DeviceToolOutcome.Success(volumeReply(requested, actual))
    }

    /**
     * 回复里报的是**实际生效**的音量（[actual]），不是请求值。
     *
     * Android 的音量是 15 档左右，所以 50% 常常落到 53%，两个数不一样时明说，
     * 免得用户按自己说的数字去理解。
     */
    private fun volumeReply(requested: Int, actual: Int): String {
        if (actual > 0) {
            val note = if (requested == actual) "" else "（你要的 $requested% 在这台机器上落到 $actual%）"
            return "媒体音量已调到 $actual%。$note"
        }
        val restore = volume.lastAudiblePercent()
        val note = if (restore == null) "" else "，静音前是 $restore%"
        return "媒体音量已调到 0，也就是静音$note。"
    }

    private fun callStatus(): DeviceToolOutcome {
        val snapshot = hardware.snapshot()
        val parts = mutableListOf<String>()
        if (hardware.gimbalAvailable) {
            val rotating = if (snapshot.rotating) "，正在转动" else ""
            parts += "云台角度 ${snapshot.angleDegrees} 度$rotating"
        } else {
            parts += "本机没有云台"
        }
        parts += "下方补光灯：${snapshot.bottomLightOn.onOffText()}"
        parts += "顶部补光灯：${snapshot.topLightOn.onOffText()}"
        parts += "红外灯：${snapshot.infraredOn.onOffText()}"
        parts += volumeStatusText()
        return DeviceToolOutcome.Success(parts.joinToString("；") + "。")
    }

    /**
     * 音量的状态文字。
     *
     * 静音时把"静音前是多少"一起报出去——这是模型能回答"取消静音"的唯一依据，
     * 不给它就只能猜一个 50 出来。
     */
    private fun volumeStatusText(): String {
        if (!volume.volumeAvailable) return "媒体音量：调不了"
        val current = volume.volumePercent() ?: return "媒体音量：读不到"
        if (current > 0) return "媒体音量 $current%"
        val restore = volume.lastAudiblePercent()
        return if (restore == null) {
            "媒体音量 0%（静音）"
        } else {
            "媒体音量 0%（静音，静音前是 $restore%）"
        }
    }

    private fun gimbalSchema() = JSONObject()
        .put("name", GIMBAL_TOOL)
        .put(
            "description",
            "转动这台设备的摄像头云台。用户说「左转」「往右一点」「转过去」「回正」" +
                "「转到 180 度」这类话时调用。direction 取值：" +
                "left=向左转、right=向右转（这两个按 degrees 转，默认 30 度）；" +
                "home=回到 0 度原点；angle=转到 degrees 指定的绝对角度。" +
                "degrees 单位是度，范围 0-360。返回的是「已经开始转」，不是「已经转完」。",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "direction",
                            JSONObject()
                                .put("type", "string")
                                .put("enum", JSONArray().put("left").put("right").put("home").put("angle")),
                        )
                        .put(
                            "degrees",
                            JSONObject()
                                .put("type", "integer")
                                .put("minimum", 0)
                                .put("maximum", PanelAngles.FULL_TURN),
                        ),
                )
                .put("required", JSONArray().put("direction")),
        )

    private fun lightSchema() = JSONObject()
        .put("name", LIGHT_TOOL)
        .put(
            "description",
            "打开或关闭这台设备上的灯。用户说「打开灯光」「开灯」「把灯关掉」「打开红外」时调用。" +
                "target 取值：bottom=下方补光灯、top=顶部补光灯、all=两盏补光灯一起、" +
                "infrared=红外灯（夜视用）。on=true 打开，false 关闭。" +
                "用户说「打开灯光」而没指明是哪一盏时，用 target=all、on=true。",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put(
                            "target",
                            JSONObject()
                                .put("type", "string")
                                .put(
                                    "enum",
                                    JSONArray().put("bottom").put("top").put("all").put("infrared"),
                                ),
                        )
                        .put("on", JSONObject().put("type", "boolean")),
                )
                .put("required", JSONArray().put("target").put("on")),
        )

    /**
     * 音量工具。
     *
     * 名字和参数形状**故意**和 `xiaozhi-esp32` 的 `main/mcp_server.cc` 一模一样
     * （`self.audio_speaker.set_volume`、`volume` 0-100），那边就是这么加的：
     * 服务端的提示词和模型对这套接口的熟悉度可以直接复用。
     *
     * 相对说法（"大一点"）和"取消静音"都必须先查 [STATUS_TOOL]——单条
     * `set_volume` 是绝对值接口，模型不知道当前值就只能瞎设。所以描述里把这两条写死。
     */
    private fun volumeSchema() = JSONObject()
        .put("name", VOLUME_TOOL)
        .put(
            "description",
            "设置这台设备播放媒体的音量，也就是小智自己说话的音量。" +
                "用户说「音量调到 50」「声音大一点」「小声一点」「静音」时调用。" +
                "volume 是 0 到 100 的整数，0 就是静音。" +
                "用户说相对的话（「大一点」「小一点」「太吵了」）时，" +
                "先调用 $STATUS_TOOL 看当前音量，再加或减 20 再调用本工具。" +
                "用户说「取消静音」「恢复音量」时，先调用 $STATUS_TOOL 看里面的" +
                "「静音前是 N%」，再把 volume 设回那个值。",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put(
                        "volume",
                        JSONObject()
                            .put("type", "integer")
                            .put("minimum", VolumeScale.MIN)
                            .put("maximum", VolumeScale.MAX),
                    ),
                )
                .put("required", JSONArray().put("volume")),
        )

    private fun statusSchema() = JSONObject()
        .put("name", STATUS_TOOL)
        .put(
            "description",
            "查询这台设备的硬件状态：云台现在在多少度、有没有在转，两盏补光灯和红外灯是开还是关，" +
                "以及当前媒体音量。用户问「云台现在朝哪」「灯开着吗」「现在音量多少」时调用，" +
                "也是调整音量和取消静音之前的必要一步。",
        )
        .put(
            "inputSchema",
            JSONObject().put("type", "object").put("properties", JSONObject()),
        )

    companion object {
        const val GIMBAL_TOOL = "self.device.gimbal"
        const val LIGHT_TOOL = "self.device.light"

        /** 和 ESP32 同名同参数，便于复用服务端提示词。 */
        const val VOLUME_TOOL = "self.audio_speaker.set_volume"
        const val STATUS_TOOL = "self.device.status"
    }
}

/** 工具执行结果。失败走 JSON-RPC 的 error，让模型自己决定怎么跟用户说。 */
sealed interface DeviceToolOutcome {
    data class Success(val text: String) : DeviceToolOutcome

    data class Failure(val message: String) : DeviceToolOutcome
}

/** 语音里能点到名的灯。 */
private enum class LightTarget(val label: String) {
    BOTTOM("下方补光灯"),
    TOP("顶部补光灯"),
    ALL("两盏补光灯"),
    INFRARED("红外灯"),
    ;

    val isInfrared: Boolean get() = this == INFRARED

    /** 要写哪几路补光灯。红外灯走另一条路，所以是空的。 */
    val fillLights: List<FillLight>
        get() = when (this) {
            BOTTOM -> listOf(FillLight.BOTTOM)
            TOP -> listOf(FillLight.TOP)
            ALL -> FillLight.entries
            INFRARED -> emptyList()
        }

    companion object {
        /** 认不出来返回 null，由调用方回一句"参数不对"——**不猜**。 */
        fun parse(raw: String?): LightTarget? = when (raw?.trim()?.lowercase()) {
            "bottom", "down", "led1" -> BOTTOM
            "top", "up", "led2" -> TOP
            "all", "both", "light", "lights" -> ALL
            "infrared", "ir", "night" -> INFRARED
            else -> null
        }
    }
}

private fun Boolean?.onOffText(): String = when (this) {
    true -> "开"
    false -> "关"
    null -> "读不到"
}

private fun Boolean.onOffVerb(): String = if (this) "打开" else "关闭"
