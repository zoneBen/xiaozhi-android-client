package me.xiaozhi.androidclient.camera

/**
 * 拍照前的自动曝光（AE）策略。
 *
 * ## 2026-10-11：为什么非要有这一层
 *
 * 用户反馈"参考项目 `workdayAlarmClockAndroid` 的图像很清晰，本项目很暗"，实测本机拍的
 * 三张照片平均亮度 **1.2/255、中位数 0**——基本全黑。
 *
 * 差别在**拍摄方式**，不在摄像头：
 *
 * - 参考项目是**持续推流**，一路重复请求不停跑，AE 一直在收敛，所以画面是正常曝光的；
 * - 我们是打开会话就发一次性 `TEMPLATE_STILL_CAPTURE`，**AE 根本没时间收敛**，
 *   拍到的是传感器初始的那一帧。
 *
 * 雪上加霜的是这台机器 `camera.disable_zsl_mode = 1`：关掉 ZSL 的设备上，
 * `TEMPLATE_STILL_CAPTURE` **不再自动做 precapture**，必须由应用自己触发，
 * 否则结果就是一张暗片。
 *
 * 还有个容易被忽略的放大器：曝光上限由**帧率区间**决定。本机
 * `aeAvailableTargetFpsRanges = [15,15] [15,20] [20,20] [5,30] [30,30]`，
 * 默认区间把帧率钉在 30fps，等于把曝光时间**封顶在 1/30 秒**；而传感器
 * `exposureTimeRange` 明明允许到 **0.4 秒**。暗房里差的是 12 倍进光量。
 * 所以这里挑"下限最低"的区间（本机是 `[5,30]`），给长曝光留出空间。
 *
 * 只放规则、不碰 Camera2 的常量，好单测（本仓库单测不带 Robolectric）。
 */

/** AE 现在能不能拍。由调用方把 `CaptureResult.CONTROL_AE_STATE` 映射过来。 */
enum class AeReadiness {
    /** 已收敛（`AE_STATE_CONVERGED` / `AE_STATE_LOCKED`）。 */
    CONVERGED,

    /** 还在搜寻、或在 precapture 过程中。 */
    NOT_CONVERGED,

    /** 读不到状态（老 HAL 可能不给）。 */
    UNKNOWN,
}

object AutoExposure {

    /**
     * 等 AE 收敛的上限。**必须封顶**：极暗或对着纯色墙面时 AE 可能永远到不了
     * `CONVERGED`，不封顶就是拍照永久卡住，一路挂到 12 秒的拍摄超时。
     * 宁可拍一张略欠曝的，也不能让用户等 12 秒然后拿到一句"超时"。
     */
    const val MAX_WAIT_MS = 3_000L

    /** 收敛了就可以拍；没收敛但已经等够 [MAX_WAIT_MS] 也拍——有图总比超时强。 */
    fun shouldCapture(readiness: AeReadiness, elapsedMs: Long): Boolean =
        readiness == AeReadiness.CONVERGED || elapsedMs >= MAX_WAIT_MS

    /**
     * 挑一个"曝光时间能放最长"的帧率区间。
     *
     * 区间是 `[minFps, maxFps]`，**上限决定最长曝光**（1/maxFps 秒）。所以下限最低的
     * 区间给 AE 留的余地最大。下限相同再比上限，取更小的那个。
     *
     * 一个区间都没有时返回 null，调用方就别设这个控制项、用 HAL 默认值。
     */
    fun lowLightFpsRange(ranges: List<IntRange>): IntRange? =
        ranges.minWithOrNull(compareBy({ it.first }, { it.last }))

    /**
     * 把 HAL 报的 `CONTROL_AE_STATE` 值映射成 [AeReadiness]。
     *
     * 用裸 magic number 而不是 `CaptureResult.CONTROL_AE_STATE_*`，是为了让这个文件
     * 在纯 JVM 单测里不碰 android 类。值来自 Camera2 的公开定义：
     * 0=INACTIVE、1=SEARCHING、2=CONVERGED、3=LOCKED、4=FLASH_REQUIRED、5=PRECAPTURE。
     */
    fun readinessOfAeState(aeState: Int?): AeReadiness = when (aeState) {
        null -> AeReadiness.UNKNOWN
        2, 3 -> AeReadiness.CONVERGED
        else -> AeReadiness.NOT_CONVERGED
    }
}
