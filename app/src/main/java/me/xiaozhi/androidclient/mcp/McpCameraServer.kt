package me.xiaozhi.androidclient.mcp

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import me.xiaozhi.androidclient.camera.CameraVisionTool
import me.xiaozhi.androidclient.network.XiaozhiRealtimeClient
import me.xiaozhi.androidclient.scheduling.ReminderScheduler
import org.json.JSONArray
import org.json.JSONObject

private const val CAMERA_TOOL_NAME = "self.camera.take_photo"

class McpCameraServer(
    private val cameraVisionTool: CameraVisionTool,
    private val realtimeClient: XiaozhiRealtimeClient,
    private val scope: CoroutineScope,
    private val reminderScheduler: ReminderScheduler,
    private val isCurrentSession: (String) -> Boolean,
    private val isScheduledDeliveryInProgress: () -> Boolean,
    private val isInitialSupervisionReminderInProgress: () -> Boolean,
    /** 本地设备（云台 / 补光灯 / 音量）工具。用户可以在设置里分组关掉。 */
    private val deviceToolSet: DeviceToolSet,
    /**
     * 用户有没有开着拍照。false 时不声明摄像头工具、监督描述里也不再提拍照。
     *
     * 这里只管"别告诉模型有这个工具"。**执行期的拒绝不在这里**——它收在
     * [CameraVisionTool] 自己身上，因为拍照还有一条不走 MCP 的入口
     * （监督提醒的定时核验），只拦这一层等于没拦。
     */
    private val cameraEnabled: () -> Boolean = { true },
    /** 用户在输入框里打的长文字（listen/detect 发不出去的那部分）。 */
    private val pendingUserMessage: () -> String? = { null },
    /**
     * 工具清单已经回给服务端了。
     *
     * 这是「服务端此刻知不知道有 `self.message.current` 这个工具」的唯一可靠信号。
     * 排队中的文字消息必须等它——否则服务端会拿着【文字消息】这四个字开一个回合，
     * 而模型手上根本没有取回原话的工具，只能回一句「你好呀，有什么可以帮你的吗」。
     */
    private val onToolsDeclared: () -> Unit = {},
    private val log: (String) -> Unit,
) {
    fun handleIncomingMcp(rawText: String): Boolean {
        val transportEnvelope = runCatching { JSONObject(rawText) }.getOrElse {
            log("忽略无效 MCP JSON：${it.message.orEmpty()}")
            return true
        }
        // The WebSocket transport wraps JSON-RPC in { type: "mcp", payload: {...} }.
        // ESP32 removes this envelope before dispatching; Android receives it directly.
        val request = transportEnvelope.optJSONObject("payload") ?: transportEnvelope
        val requestSessionId = transportEnvelope.optString("session_id").takeIf { it.isNotBlank() }
        if (request.optString("jsonrpc") != "2.0") {
            return false
        }
        val method = request.optString("method")
        if (method.isBlank()) {
            // This is a response to a client-initiated MCP debug request, not a server call.
            return false
        }
        val id = request.opt("id")
        if (id == null || id == JSONObject.NULL) {
            return true
        }

        when (method) {
            "initialize" -> handleInitialize(id, request.optJSONObject("params"))
            "tools/list" -> replyToolsList(id)
            "tools/call" -> handleToolCall(id, request.optJSONObject("params"), requestSessionId)
            else -> replyError(id, "Method not implemented: $method")
        }
        return true
    }

    private fun handleInitialize(id: Any, params: JSONObject?) {
        val vision = params?.optJSONObject("capabilities")?.optJSONObject("vision")
        cameraVisionTool.updateVisionEndpoint(
            url = vision?.optString("url")?.takeIf { it.isNotBlank() },
            token = vision?.optString("token"),
        )
        if (vision?.optString("url").isNullOrBlank()) {
            log("MCP 初始化完成，服务端暂未下发视觉上传地址")
        } else {
            log("MCP 初始化完成，已收到视觉上传地址")
        }
        replyResult(
            id,
            JSONObject()
                .put("protocolVersion", "2024-11-05")
                .put("capabilities", JSONObject().put("tools", JSONObject()))
                .put(
                    "serverInfo",
                    JSONObject()
                        .put("name", "xiaozhi-android-client")
                        .put("version", "0.3.0"),
                ),
        )
    }

    private fun replyToolsList(id: Any) {
        val cameraOn = cameraEnabled()
        val tools = JSONArray()
        if (cameraOn) {
            tools.put(cameraToolSchema())
        }
        tools.put(timerSchema())
            .put(timerCurrentSchema())
            .put(supervisionStartSchema(cameraOn))
            .put(supervisionCurrentSchema(cameraOn))
            .put(supervisionCancelSchema())
            .put(userMessageSchema())
        // 云台/补光灯只在"功能开着且本机确实有这个硬件"时才声明。
        // 不声明 = 模型不知道它存在 = 不可能误动作，这就是"可以关闭"的落点。
        val deviceTools = deviceToolSet.schemas()
        deviceTools.forEach { tools.put(it) }
        replyResult(id, JSONObject().put("tools", tools))
        // 「没声明」有两个来源，日志里要分开：用户关掉的（**用户已关闭：…**），
        // 和这台机器本来就没有的。后者不能写成前者——那会让人去找一个点不动的开关。
        val skippedByUser = buildList {
            if (!cameraOn) add("拍照")
            addAll(deviceToolSet.userDisabledLabels())
        }
        log(
            buildString {
                append("已向服务端声明定时提醒、监督和用户文字工具")
                if (cameraOn) append("、摄像头")
                if (deviceTools.isNotEmpty()) append("和 ${deviceTools.size} 个本地设备工具")
                if (skippedByUser.isNotEmpty()) append("（用户已关闭：${skippedByUser.joinToString("、")}）")
            },
        )
        // 放在最后：回调里会去 flush 排队中的文字消息，必须等 replyResult 先发出去，
        // 否则又是"回合开在工具声明之前"。
        onToolsDeclared()
    }

    private fun handleToolCall(id: Any, params: JSONObject?, requestSessionId: String?) {
        val name = params?.optString("name").orEmpty()
        val args = params?.optJSONObject("arguments") ?: JSONObject()
        log("MCP 调用 $name ${summarizeToolArgs(args)}")
        if (deviceToolSet.isDeviceTool(name)) {
            when (val outcome = deviceToolSet.call(name, args)) {
                is DeviceToolOutcome.Success -> replyResult(id, toolTextResult(outcome.text))
                is DeviceToolOutcome.Failure -> replyError(id, outcome.message)
            }
            return
        }
        when (name) {
            CAMERA_TOOL_NAME -> {
                // 这里**不是**拍照唯一的来路：监督提醒到点后，
                // `MainViewModel.runSupervisionVerification` 会自己直接调相机核验，
                // 不经过 tools/list、也不经过这里。所以"关掉相机"不能只做在这一层，
                // 真正的围栏在 CameraVisionTool 自己身上。
                handleCameraCall(id, requestSessionId, args)
            }
            "self.timer.set" -> {
                if (isScheduledDeliveryInProgress()) {
                    log("提醒投递期间拦截了重复的 self.timer.set")
                    replyResult(
                        id,
                        toolTextResult("原定时器已经到期，请直接向用户播报当前提醒，不要创建新的定时器。"),
                    )
                    return
                }
                val seconds = args.optInt("seconds", 0)
                val time = args.optString("time").takeIf { it.isNotBlank() }
                val message = args.optString("message", "时间到了")
                val reminder = reminderScheduler.setTimer(seconds, time, message)
                if (reminder == null) replyError(id, "请指定有效的 seconds 或 HH:MM time")
                else replyResult(id, toolTextResult("已设置提醒：${reminder.message}"))
            }
            "self.timer.current" -> {
                val reminder = reminderScheduler.activeTimer()
                if (reminder == null) replyResult(id, toolTextResult("当前没有到期的定时提醒"))
                else replyResult(id, toolTextResult("当前定时提醒：${reminder.message}"))
            }
            "self.message.current" -> {
                val text = pendingUserMessage()
                if (text.isNullOrBlank()) {
                    replyResult(id, toolTextResult("当前没有待读取的用户文字"))
                } else {
                    replyResult(id, toolTextResult("用户在设备屏幕上打给您的原话是：$text"))
                }
            }
            "self.supervision.start" -> {
                if (isScheduledDeliveryInProgress()) {
                    log("任务投递期间拦截了 self.supervision.start")
                    replyResult(id, toolTextResult("当前到期任务正在播报，请直接播报，不要创建监督任务。"))
                    return
                }
                val seconds = args.optInt("seconds", 0)
                val message = args.optString("message", "任务")
                val reminder = if (seconds in 1..86400 && message.isNotBlank()) {
                    reminderScheduler.startSupervision(seconds, message)
                } else null
                if (reminder == null) replyError(id, "参数无效，或监督任务数量已达上限")
                else replyResult(id, toolTextResult("监督任务已开始：${reminder.message}"))
            }
            "self.supervision.current" -> {
                val reminder = reminderScheduler.activeSupervision()
                if (reminder == null) replyResult(id, toolTextResult("当前没有监督任务"))
                else replyResult(id, toolTextResult("当前监督任务：${reminder.message}"))
            }
            "self.supervision.check" -> {
                log("已拦截模型发起的 self.supervision.check，核验由设备协调器管理")
                replyResult(id, toolTextResult("监督核验由设备自动安排，请勿重复设置。"))
            }
            "self.supervision.complete" -> {
                log("已拦截模型发起的 self.supervision.complete，完成状态必须来自摄像头核验")
                replyResult(id, toolTextResult("不能根据对话直接完成监督任务；设备会以摄像头核验结果为准。"))
            }
            "self.supervision.cancel" -> {
                if (isScheduledDeliveryInProgress()) {
                    log("任务投递期间拦截了 self.supervision.cancel")
                    replyResult(id, toolTextResult("当前到期任务正在播报，未取消任何监督任务。"))
                    return
                }
                reminderScheduler.cancelSupervision()
                replyResult(id, toolTextResult("当前到期的监督任务已取消"))
            }
            else -> replyError(id, "Unknown tool: $name")
        }
    }

    private fun handleCameraCall(id: Any, requestSessionId: String?, args: JSONObject) {
        val question = args.optString("question").orEmpty()
        if (question.isBlank()) {
            replyError(id, "Missing valid argument: question")
            return
        }
        log("收到拍照识别请求")
        scope.launch(Dispatchers.IO) {
            if (requestSessionId == null || !isCurrentSession(requestSessionId)) {
                log("已忽略旧会话的拍照识别请求")
                return@launch
            }
            runCatching { cameraVisionTool.takePhotoAndExplain(question) }
                .onSuccess { explanation ->
                    if (isCurrentSession(requestSessionId)) replyResult(id, toolTextResult(explanation))
                    log("拍照识别完成")
                }
                .onFailure { error ->
                    if (isCurrentSession(requestSessionId)) replyError(id, error.message ?: "Camera vision failed")
                    log("拍照识别失败：${error.message.orEmpty()}")
                }
        }
    }

    private fun replyResult(id: Any, result: JSONObject) {
        realtimeClient.sendMcp(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("result", result)
                .toString(),
        )
    }

    private fun replyError(id: Any, message: String) {
        realtimeClient.sendMcp(
            JSONObject()
                .put("jsonrpc", "2.0")
                .put("id", id)
                .put("error", JSONObject().put("message", message))
                .toString(),
        )
    }

    private fun cameraToolSchema(): JSONObject = JSONObject()
        .put("name", CAMERA_TOOL_NAME)
        .put(
            "description",
            "Always remember you have a camera. If the user asks you to see something, use this tool to take a photo and explain it. When a reminder message contains 【监督】, you must call this camera tool before judging whether the task was completed.",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject().put("question", JSONObject().put("type", "string")),
                )
                .put("required", JSONArray().put("question")),
        )

    private fun timerSchema() = simpleToolSchema(
        "self.timer.set",
        "Set a new local countdown timer or HH:MM alarm. Never call this when receiving 【定时提醒】; that marker means an existing timer is due.",
        JSONObject()
            .put("seconds", JSONObject().put("type", "integer"))
            .put("time", JSONObject().put("type", "string"))
            .put("message", JSONObject().put("type", "string")),
        JSONArray(),
    )

    private fun timerCurrentSchema() = simpleToolSchema(
        "self.timer.current",
        "When you receive the exact marker 【定时提醒】, call this tool to get the due reminder text, then speak that reminder to the user. Do not call self.timer.set.",
        JSONObject(),
        JSONArray(),
    )

    /**
     * 监督任务。拍照关掉时它降级成"只提醒、不核实"，描述也要跟着改口——
     * 不然模型会对用户承诺"待会儿我看看你做完了没"，而这件事根本做不到。
     *
     * [cameraEnabled] 由调用方**一次算好传进来**，不在这里现读：同一份 `tools/list`
     * 里两个监督工具的措辞必须一致，各自现读的话正赶上用户切开关，就会出现
     * "start 说会拍照核实 / current 说拍不了"这种自相矛盾的回复。
     */
    private fun supervisionStartSchema(cameraEnabled: Boolean) = simpleToolSchema(
        "self.supervision.start",
        if (cameraEnabled) {
            "Start a camera-verified supervision task. Several supervision tasks may run at the same " +
                "time: never tell the user to cancel an existing task first. For plain time " +
                "reminders that do not need the camera, use self.timer.set instead."
        } else {
            "Start a supervision task (a reminder that will be reported back to you when it is due). " +
                "The camera is turned off, so completion can NOT be verified — do not promise the " +
                "user that you will check. Several supervision tasks may run at the same time: " +
                "never tell the user to cancel an existing task first."
        },
        JSONObject()
            .put("seconds", JSONObject().put("type", "integer"))
            .put("message", JSONObject().put("type", "string")),
        JSONArray().put("seconds").put("message"),
    )

    private fun supervisionCancelSchema() = simpleToolSchema("self.supervision.cancel", "Cancel the supervision task that is currently due. Other supervision tasks are kept.", JSONObject(), JSONArray())

    private fun userMessageSchema() = simpleToolSchema(
        "self.message.current",
        "When you receive the exact marker 【文字消息】, call this tool to read the full sentence the user typed on the device screen, then answer it naturally. Never ask the user to repeat it and never guess its content.",
        JSONObject(),
        JSONArray(),
    )

    /**
     * 监督任务工具。描述里的后半句要跟着拍照开关走。
     *
     * 拍照关掉时摄像头工具**不会声明**，那句 "call self.camera.take_photo before judging
     * completion" 就成了一条**指向不存在工具的错误指令**：模型会去调它、调不到、
     * 然后再来一轮。所以关掉时改成"把任务告诉用户就好"。
     *
     * 监督本身**不**跟着摘掉：承重部分是"到点提醒"，拍照核实只是附加项。
     * 一起摘掉等于顺手删掉用户要的提醒功能。
     */
    private fun supervisionCurrentSchema(cameraEnabled: Boolean) = simpleToolSchema(
        "self.supervision.current",
        if (cameraEnabled) {
            "When you receive 【监督】, call this tool first to get the task. Then remind the user " +
                "and call self.camera.take_photo before judging completion."
        } else {
            "When you receive 【监督】, call this tool first to get the task. Then remind the user " +
                "of it. Do not try to photograph anything: the user has turned the camera off, " +
                "so there is no way to verify completion."
        },
        JSONObject(),
        JSONArray(),
    )

    private fun simpleToolSchema(name: String, description: String, properties: JSONObject, required: JSONArray) = JSONObject()
        .put("name", name)
        .put("description", description)
        .put("inputSchema", JSONObject().put("type", "object").put("properties", properties).put("required", required))

    /** 工具入参的精简摘要，供日志断言使用；过长则截断，避免刷屏。 */
    private fun summarizeToolArgs(args: JSONObject): String {
        if (args.length() == 0) return "{}"
        val text = args.toString()
        return if (text.length <= 160) text else text.take(160) + "…"
    }

    private fun toolTextResult(text: String): JSONObject = JSONObject()
        .put("content", JSONArray().put(JSONObject().put("type", "text").put("text", text)))
        .put("isError", false)
}
