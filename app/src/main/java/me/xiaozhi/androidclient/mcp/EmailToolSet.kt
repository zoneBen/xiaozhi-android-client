package me.xiaozhi.androidclient.mcp

import me.xiaozhi.androidclient.email.PhotoEmailer
import org.json.JSONArray
import org.json.JSONObject

/**
 * 邮件工具：把"拍张照发我邮箱""把这段话发到我邮箱"接到这台设备的真实邮箱上。
 *
 * ## 为什么单独一个类（不塞进 [DeviceToolSet]、也不内联进 [McpCameraServer]）
 *
 * [DeviceToolSet] 的整套模型是"本机有没有这个硬件"（云台 / 补光灯 / 音量），
 * 邮件塞进去会把那份语义搅浑——邮箱不是硬件，它的开关是"用户配没配、开没开"。
 * 内联进 [McpCameraServer] 则会把那个已经 370 行的文件再撑大一圈，而这里的形状
 * 和它并不一样（这里要发网络请求、要等好几秒）。
 *
 * 所以照 [DeviceToolSet] 的三段式来：[toolNames] / [isEmailTool] / [schemas] / [call]，
 * 由 [McpCameraServer] 作为一个新的构造参数持着。
 *
 * ## 启用闸（用户明确要求的能力开关）
 *
 * 邮箱没配好、或总开关关着时 [schemas] 返回**空列表**：不声明 = 模型不知道有这个能力
 * = 不可能误动作。会话中途关掉的话，服务端手里的旧列表还可能调过来，那时 [call]
 * 会真的拦住并回一句"已被用户关闭"，见 [PhotoEmailer.notReadyReason]。
 *
 * 摄像头那一半不归这里管：[PhotoEmailer] 会去调 [me.xiaozhi.androidclient.camera.CameraVisionTool]，
 * 而拍照的总闸收在那边自己身上（"用户关了摄像头就真的一张都不拍"对两个入口同时成立）。
 */
class EmailToolSet(
    private val photoEmailer: PhotoEmailer,
) {
    /** 本工具集认领的工具名。 */
    val toolNames: Set<String> = setOf(SEND_PHOTO_TOOL, SEND_MESSAGE_TOOL)

    fun isEmailTool(name: String): Boolean = name in toolNames

    /**
     * 声明给服务端的工具定义。总开关关着、或服务器凭据还没填完时**一个都不声明**。
     *
     * 判据 [PhotoEmailer.isReady] 里不含收件人：模型可以临时指定 `to`，
     * 所以"没填默认收件人"不该让这两个工具整个消失。
     */
    fun schemas(): List<JSONObject> =
        if (photoEmailer.isReady()) listOf(sendPhotoSchema(), sendMessageSchema()) else emptyList()

    /**
     * 执行。**慢活（拍照 + SMTP）都在这里**，调用方必须放到 IO 协程上。
     *
     * 开头的闸是同步的、做不了任何事就会返回，所以"会话中途被用户关掉"这种情形
     * 不会真的去拍一张、发一封。
     */
    suspend fun call(name: String, args: JSONObject): DeviceToolOutcome {
        if (!isEmailTool(name)) {
            return DeviceToolOutcome.Failure("Unknown tool: $name")
        }
        photoEmailer.notReadyReason()?.let { return DeviceToolOutcome.Failure(it) }

        val result = when (name) {
            SEND_PHOTO_TOOL -> photoEmailer.captureAndSend(
                to = args.optString("to").takeIf { it.isNotBlank() },
                subject = args.optString("subject").takeIf { it.isNotBlank() },
                note = args.optString("note").takeIf { it.isNotBlank() },
            )
            else -> photoEmailer.sendText(
                to = args.optString("to").takeIf { it.isNotBlank() },
                subject = args.optString("subject").takeIf { it.isNotBlank() },
                body = args.optString("body"),
            )
        }
        return result.fold(
            onSuccess = { DeviceToolOutcome.Success(it) },
            onFailure = { error -> DeviceToolOutcome.Failure(error.message ?: "发信失败，原因不明。") },
        )
    }

    /**
     * 拍照发邮件。
     *
     * `required` **故意是空的**：用户说的是"拍张照发我邮箱"，一个参数都不给也该成立
     * （收件人用设置里的默认值）。要是把 `to` 标成必填，模型就会在用户没提地址时
     * 反过来问一句"请问发到哪个邮箱"——而设置页里明明写着。
     *
     * 描述里写明"本工具自己会拍"，是因为另一条路（`self.camera.take_photo`）需要先
     * 拍一次再解释；不写清楚的话模型可能先调那个、再拿一段文字描述来发邮件，
     * 结果发出去的是一句话而不是照片。
     */
    private fun sendPhotoSchema() = JSONObject()
        .put("name", SEND_PHOTO_TOOL)
        .put(
            "description",
            "拍一张照片，作为附件发到邮箱。用户说「拍张照发我邮箱」「拍下来发给我」" +
                "「把现在看到的拍下来发到 xxx@qq.com」时调用。本工具自己会拍照，" +
                "不需要先调用 self.camera.take_photo，也不要把画面描述当照片发出去。" +
                "to 不填就发给用户在设置里填的默认收件人，所以用户没提地址时直接调用即可，" +
                "不要反问地址。返回「已发出」才算成功；失败时把原因如实告诉用户。",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("to", JSONObject().put("type", "string").put("description", "收件人邮箱，不填就用默认收件人"))
                        .put("subject", JSONObject().put("type", "string").put("description", "邮件主题"))
                        .put("note", JSONObject().put("type", "string").put("description", "随照片一起发过去的正文")),
                )
                .put("required", JSONArray()),
        )

    private fun sendMessageSchema() = JSONObject()
        .put("name", SEND_MESSAGE_TOOL)
        .put(
            "description",
            "把一段文字发到邮箱。用户说「把这段话发到我邮箱」「发封邮件给他，告诉他…」时调用。" +
                "to 不填就发给用户在设置里填的默认收件人；body 是要发的正文，不能为空。",
        )
        .put(
            "inputSchema",
            JSONObject()
                .put("type", "object")
                .put(
                    "properties",
                    JSONObject()
                        .put("to", JSONObject().put("type", "string").put("description", "收件人邮箱，不填就用默认收件人"))
                        .put("subject", JSONObject().put("type", "string").put("description", "邮件主题"))
                        .put("body", JSONObject().put("type", "string").put("description", "邮件正文")),
                )
                .put("required", JSONArray().put("body")),
        )

    companion object {
        const val SEND_PHOTO_TOOL = "self.email.send_photo"
        const val SEND_MESSAGE_TOOL = "self.email.send_message"
    }
}
