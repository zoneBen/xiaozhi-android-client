package me.xiaozhi.androidclient.camera

/**
 * 拍照链路上「选哪台摄像头、转多少度、多大分辨率」这三条纯规则。
 *
 * ## 为什么单独拆出来（2026-10-11）
 *
 * 原来的 [CameraVisionTool] 只认 USB 外接摄像头：`selectCameraId()` 挑
 * `LENS_FACING_EXTERNAL`，挑不到就抛 `No external USB camera is available`。
 * RK3568 样机上麦克风和摄像头是同一个 USB 设备（见 `InputDeviceSelector` 里
 * "card 2 USB 摄像头麦"），所以那条路能用；换到 TV5701 就整条断了——
 *
 * > 真机实测：`dumpsys media.camera` 只有 1 台，`device@3.4/internal/0`，
 * > Facing: **Back**，`/dev/video*` 根本不存在。它是内置 MIPI 摄像头
 * > （主机名 `In_Door_Camera-001194`），不是 USB UVC。
 *
 * 于是模型听到的错误就是「摄像头好像没接上」——**不是没接上，是代码只找 USB 那一种**。
 *
 * ## 选摄像头的规则
 *
 * 有外接就还选外接（保住 RK3568 的既有行为），没有就在用户选的那个朝向里挑，
 * 用户没得选（本机只有一个朝向）时用第一台。[enumerate] 照
 * `workdayAlarmClockAndroid` 的 `AvcCameraPipeline` 那样**按索引枚举全部摄像头**，
 * 不拿 `LENS_FACING` 当门槛——那个项目在 TV5701 上能出图，正是因为没设这道门槛。
 *
 * ## 朝向：外接和内建要分开算
 *
 * 外接 UVC 模组上报的 `SENSOR_ORIENTATION` 是 0，可它在整机里是**倒装**的——
 * 这件事 API 里查不到，只能写死 [EXTERNAL_MODULE_PIXEL_ROTATION_DEGREES]（沿用原实现）。
 *
 * 内建摄像头不一样：`SENSOR_ORIENTATION` 是可信的，按标准公式和**当前屏幕方向**
 * 算就是该转的角度，不该再写死一个数——写死 180 只对那一个模组成立。
 * 前置和后置的公式差一个符号（见 [pixelRotationDegrees]），2026-10-12 补上。
 *
 * ## 分辨率：别写死
 *
 * 原来写死 `1280x720`。TV5701 恰好支持（JPEG/格式 33 的 `availableStreamConfigurations`
 * 里有 `1280 720`），所以这次没炸；但换个机型不支持这个尺寸时，
 * 表现出来是 `createCaptureSession` 的「会话配置失败」，很难往"尺寸"上想。改成按能力挑。
 */
object CameraSelection {

    /**
     * 外接 USB 模组的像素旋转角。
     *
     * 模组在整机里倒装，而它上报的 `SENSOR_ORIENTATION` 是 0，API 问不出真实朝向，
     * 所以这一条只能写死。内建摄像头**不要**用这个值，见 [pixelRotationDegrees]。
     */
    const val EXTERNAL_MODULE_PIXEL_ROTATION_DEGREES = 180

    /** 没有外接摄像头时退而求其次的尺寸，够云端模型看清画面了。 */
    val PREFERRED_JPEG_SIZE = FrameSize(1280, 720)

    /**
     * 挑一台摄像头：**优先外接**（保住 RK3568 的行为），否则用户选的那个朝向，
     * 否则第一台。
     *
     * 外接排在最前面是**刻意**的，不是没来得及改：RK3568 上麦克风和摄像头是同一个 USB
     * 设备（见 `InputDeviceSelector`），那台机器上外接相机是唯一能出图的一台，
     * 让"用户偏好"压过它只会把能用的那台换掉。反过来，本机只有内置摄像头时
     * （手机、平板）外接那一条根本不命中，用户的选择才有意义。
     *
     * 一台都没有时返回 null，由调用方决定报什么错——这里不抛异常，好单测。
     */
    fun choose(
        candidates: List<CameraCandidate>,
        preferred: CameraFacing = CameraFacing.BACK,
    ): CameraCandidate? =
        candidates.firstOrNull { it.isExternal }
            ?: candidates.firstOrNull { it.facing == preferred }
            ?: candidates.firstOrNull()

    /**
     * 传感器方向 + 屏幕方向的映射，得到"还要转多少度"（顺时针）。
     *
     * 外接走那个写死的倒装角；内建按标准公式：后置 `(传感器 - 屏幕 + 360) % 360`，
     * 前置 `(传感器 + 屏幕) % 360`。
     *
     * 前置**只转不镜像**。相机的标准自拍管线是"先镜像再旋转"，但这里删掉镜像那一步，
     * 因为这个画面不是给人看自拍的，是给视觉模型读的：镜像会把画面里的文字、招牌、
     * 书页、仪表盘全翻过来，那比"左右反了"严重得多。删掉镜像不影响角度——
     * 镜像与旋转可交换（`M ∘ R(θ) = R(-θ) ∘ M`），所以标准前置公式 `(传感器 + 屏幕)`
     * 原样拿来用，得到的正是**未镜像且摆正**的那张图。
     *
     * 方向核对过：唯一真正转像素的是 `CameraVisionTool.rotatePixels`，它用
     * `Matrix.postRotate`，正角度即顺时针，和上面这套公式的约定一致。
     */
    fun pixelRotationDegrees(candidate: CameraCandidate, deviceRotationDegrees: Int): Int =
        when (candidate.facing) {
            CameraFacing.EXTERNAL -> EXTERNAL_MODULE_PIXEL_ROTATION_DEGREES
            CameraFacing.FRONT ->
                ((candidate.sensorOrientationDegrees + deviceRotationDegrees) % 360 + 360) % 360
            // UNKNOWN 落在后置这条上：现在 `LENS_FACING` 读不到时就是这个行为，
            // 而且认不出来时按后置算总比按前置算安全（前置那套符号反了会歪得更远）。
            CameraFacing.BACK, CameraFacing.UNKNOWN ->
                ((candidate.sensorOrientationDegrees - deviceRotationDegrees) % 360 + 360) % 360
        }

    /**
     * `Display.getRotation()` / `Surface.ROTATION_*` 的 0/1/2/3 换成角度。
     *
     * 这里写字面量而不是引 `Surface.ROTATION_90`，是为了让这个文件在纯 JVM 单测里
     * 不碰 android 类（本仓库的单测不带 Robolectric）。
     */
    fun rotationDegreesOf(surfaceRotation: Int): Int = when (surfaceRotation) {
        1 -> 90   // ROTATION_90
        2 -> 180  // ROTATION_180
        3 -> 270  // ROTATION_270
        else -> 0 // ROTATION_0，以及任何没见过的值
    }

    /**
     * 从相机支持的输出尺寸里挑一个。
     *
     * 先在同长宽比的候选里挑「不超过 [preferred] 的最大者」，避免挑到 2:1 这种会把画面
     * 裁掉的怪尺寸；同长宽比的一个都没有时（有些机型只给奇怪的组合）放开比例限制，
     * 仍然优先不超过 [preferred] 的。全比 [preferred] 大就退而求其次挑最小的那个——
     * 有图总比报错强。
     */
    fun chooseJpegSize(supported: List<FrameSize>, preferred: FrameSize = PREFERRED_JPEG_SIZE): FrameSize? {
        if (supported.isEmpty()) return null
        val sameShape = supported.filter { it.hasSameShapeAs(preferred) }
        val pool = sameShape.ifEmpty { supported }
        return pool.filter { it.area <= preferred.area }.maxByOrNull { it.area }
            ?: pool.minByOrNull { it.area }
    }

    /**
     * 挑一路**只用来让 AE 收敛**的预览流尺寸：支持的最小尺寸。
     *
     * 这路流不进任何照片，只负责让 AE/AWB 在真正拍摄前跑起来（见 [AutoExposure]）。
     * 所以越小越好：省带宽、省内存，也让 HAL 更容易接受"同时开两路"。
     * 一路都不支持时返回 null，由调用方决定怎么办。
     */
    fun choosePrimingSize(supported: List<FrameSize>): FrameSize? = supported.minByOrNull { it.area }
}

/** 摄像头的朝向。 */
enum class CameraFacing(val label: String) {
    /** 后置（内建）。 */
    BACK("后置"),

    /** 前置（内建）。 */
    FRONT("前置"),

    /** 外接 USB UVC。 */
    EXTERNAL("外接"),

    /**
     * 没认出来（`LENS_FACING` 读不到、或是将来多出来的取值）。
     *
     * 和 [BACK] 走同一套旋转公式，但**不作为用户可选项**——认不出朝向的摄像头
     * 摆到"用哪一个"的选择器里，用户根本不知道那指的是哪一台。
     */
    UNKNOWN("内建（朝向未知）"),
    ;

    companion object {
        /**
         * 解析用户存的偏好。**只有 [BACK] 和 [FRONT] 是合法值**，其余一律退回 [BACK]。
         *
         * 退回而不是报错：这个值来自 SharedPreferences，一个坏字符串不该让启动失败，
         * 而后置是绝大多数场景下的正确默认（说「看看这是什么」指的是眼前的东西）。
         * [EXTERNAL] 和 [UNKNOWN] 也不接受：外接是**无条件优先**的（见 `choose`），
         * 选它没有意义；[UNKNOWN] 上面说了，不该出现在选择器里。
         */
        fun parsePreference(raw: String?): CameraFacing {
            val normalized = raw?.trim()?.uppercase().orEmpty()
            return when (normalized) {
                FRONT.name -> FRONT
                else -> BACK
            }
        }
    }
}

/** 枚举到的摄像头。[facing] 是朝向，[sensorOrientationDegrees] 是 `SENSOR_ORIENTATION`。 */
data class CameraCandidate(
    val id: String,
    val facing: CameraFacing,
    val sensorOrientationDegrees: Int,
) {
    /** 是不是 USB UVC。由 [facing] 派生，免得两个字段各说各话。 */
    val isExternal: Boolean get() = facing == CameraFacing.EXTERNAL
}

/** 相机输出尺寸。 */
data class FrameSize(val width: Int, val height: Int) {
    val area: Int get() = width * height

    /** 交叉相乘比长宽比，避免浮点误差：`1280x720` 与 `1440x720` 不算同形。 */
    fun hasSameShapeAs(other: FrameSize): Boolean =
        width * other.height == height * other.width
}
