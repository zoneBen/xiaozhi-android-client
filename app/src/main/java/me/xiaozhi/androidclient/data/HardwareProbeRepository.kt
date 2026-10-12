package me.xiaozhi.androidclient.data

import android.content.Context
import me.xiaozhi.androidclient.camera.CameraFacing
import me.xiaozhi.androidclient.data.db.HardwareProbeDao
import me.xiaozhi.androidclient.data.db.HardwareProbeEntity
import me.xiaozhi.androidclient.data.db.XiaozhiDatabase

/**
 * 本机硬件自检结果的缓存。整表一行，见 [HardwareProbeEntity]。
 *
 * ## 缓存**不**覆盖什么（这里比「缓存什么」更要紧）
 *
 * 1. **摄像头可选的朝向我不会拿缓存去顶替实时查询。**
 *    `refreshCameraFacings()` 在**每次打开设置页**时都会实时重查，这是刻意的：
 *    `availableBuiltinFacings()` 在 init 那一刻可能因为相机服务没就绪而返回空列表，
 *    而外接 USB 相机热插拔也会改变 `cameraIdList`。缓存只在**启动路径**上生效
 *    （省掉一次 CameraManager 枚举），设置页那条自愈通路一个字没动。
 *    另外**空列表不写进缓存**——一个已知不可靠的结果不值得记 24 小时。
 *
 * 2. **麦克风的健康度不由缓存决定。** 它是每 5 秒一次的持续巡检（`startMicrophoneWatch`），
 *    因为 USB 麦随时会掉。缓存里记的只是一个快照，给「重新自检」展示用；
 *    启动闸（`checksPassed` 里的 `microphoneReady`）仍然只认真实巡检的结果。
 *    拿一份过期的「上次麦克风是坏的」去开启动闸，只会制造它本来想消除的那种假警报。
 */
class HardwareProbeRepository(private val dao: HardwareProbeDao) {

    constructor(context: Context) : this(XiaozhiDatabase.get(context).hardwareProbeDao())

    /** 取缓存，**过期就当作没有**（返回 null），让调用方去做一次真探测。 */
    suspend fun loadFresh(nowEpochMs: Long = System.currentTimeMillis()): HardwareProbeEntity? =
        dao.get()?.takeIf { isFresh(it, nowEpochMs) }

    /** 不管过没过期都取出来。只给「上次自检于何时」这类展示用。 */
    suspend fun load(): HardwareProbeEntity? = dao.get()

    suspend fun save(probe: HardwareProbeEntity) = dao.upsert(probe)

    suspend fun clear() = dao.clear()

    companion object {
        /**
         * 缓存有效期。取 24 小时是个折中：这些值（能不能控制云台/补光灯、有没有音量通道）
         * 取决于机型和 ROM，基本不会变；一天重探一次足够发现换了硬件。
         * 用户随时可以用设置页的「重新自检」立刻作废它。
         */
        const val PROBE_TTL_MS = 24 * 60 * 60 * 1000L

        /**
         * 纯判定，单独抽出来是为了能单测——判据里有个不显然的边界：
         * `age < 0`（系统时间被往回调）也算过期。不这么写的话，
         * 一次 NTP 校时就可能让「上次自检」变成遥遥无期的未来值，缓存再也刷不掉。
         */
        fun isFresh(probe: HardwareProbeEntity, nowEpochMs: Long): Boolean {
            val age = nowEpochMs - probe.probedAtEpochMs
            return age in 0 until PROBE_TTL_MS
        }
    }
}

/** 朝向列表 → 逗号分隔存进 `cameraFacingChoicesCsv`。 */
fun List<CameraFacing>.toProbeCsv(): String = joinToString(",") { it.name }

/**
 * 逗号分隔 → 朝向列表。
 *
 * 认不出的名字直接丢掉而不是抛异常：枚举将来删项、或者有人手工改过库，
 * 都不该让设置页打不开。空串会 split 出一个 `[""]`，经 `mapNotNull` 后正好是空列表。
 */
fun String.toProbeFacings(): List<CameraFacing> {
    val known = CameraFacing.entries.associateBy { it.name }
    return split(',').mapNotNull { known[it.trim()] }
}
