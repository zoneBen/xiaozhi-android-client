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
 * 有外接就还选外接（保住 RK3568 的既有行为），没有就用第一台。[enumerate] 照
 * `workdayAlarmClockAndroid` 的 `AvcCameraPipeline` 那样**按索引枚举全部摄像头**，
 * 不拿 `LENS_FACING` 当门槛——那个项目在 TV5701 上能出图，正是因为没设这道门槛。
 *
 * ## 朝向：外接和内建要分开算
 *
 * 外接 UVC 模组上报的 `SENSOR_ORIENTATION` 是 0，可它在整机里是**倒装**的——
 * 这件事 API 里查不到，只能写死 [EXTERNAL_MODULE_PIXEL_ROTATION_DEGREES]（沿用原实现）。
 *
 * 内建摄像头不一样：`SENSOR_ORIENTATION` 是可信的，按标准公式和**当前屏幕方向**相减
 * 就是该转的角度，不该再写死一个数——写死 180 只对那一个模组成立。
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
     * 挑一台摄像头：**优先外接**（保住 RK3568 的行为），否则第一台。
     *
     * 一台都没有时返回 null，由调用方决定报什么错——这里不抛异常，好单测。
     */
    fun choose(candidates: List<CameraCandidate>): CameraCandidate? =
        candidates.firstOrNull { it.isExternal } ?: candidates.firstOrNull()

    /**
     * 传感器方向 + 屏幕方向的映射，得到"还要转多少度"。
     *
     * 外接走那个写死的倒装角；内建用标准公式 `(传感器 - 屏幕 + 360) % 360`
     * （后置摄像头的公式，本机只有后置，前置还要额外镜像，这轮不涉及）。
     */
    fun pixelRotationDegrees(candidate: CameraCandidate, deviceRotationDegrees: Int): Int =
        if (candidate.isExternal) {
            EXTERNAL_MODULE_PIXEL_ROTATION_DEGREES
        } else {
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

/** 枚举到的摄像头。[isExternal] 是 USB UVC，[sensorOrientationDegrees] 是 `SENSOR_ORIENTATION`。 */
data class CameraCandidate(
    val id: String,
    val isExternal: Boolean,
    val sensorOrientationDegrees: Int,
)

/** 相机输出尺寸。 */
data class FrameSize(val width: Int, val height: Int) {
    val area: Int get() = width * height

    /** 交叉相乘比长宽比，避免浮点误差：`1280x720` 与 `1440x720` 不算同形。 */
    fun hasSameShapeAs(other: FrameSize): Boolean =
        width * other.height == height * other.width
}
