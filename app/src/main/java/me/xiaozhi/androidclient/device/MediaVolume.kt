package me.xiaozhi.androidclient.device

/**
 * 媒体音量：这台设备"说话"的音量。
 *
 * ## 为什么是"媒体"（2026-10-11）
 *
 * 小智自己的声音走 [me.xiaozhi.androidclient.audio.XiaozhiAudioEngine]，那里
 * `AudioTrack` 的 `AudioAttributes` 是 `USAGE_MEDIA` + `CONTENT_TYPE_MUSIC`，
 * 也就是路由到 `STREAM_MUSIC`。所以"调媒体音量"**就是**调小智的音量——
 * 用户说"小声点"，说话声真的会变小，不是去调一个跟对话无关的通道。
 *
 * 铃声、闹钟、通话那几条流不动：这台机器不会响铃，通话音量也不该由语音助手碰。
 *
 * ## 单位是百分比，不是索引
 *
 * 参考 `xiaozhi-esp32` 的 `main/mcp_server.cc`，那边是
 * `AddTool("self.audio_speaker.set_volume", ..., Property("volume", kPropertyTypeInteger, 0, 100), ...)`
 * ——**0..100**。这里跟它对齐是有意的：工具名和参数形状一样，服务端的提示词和模型
 * 对这套接口的熟悉度就能直接复用，不用为 Android 再训一套说法。
 *
 * Android 那边 `AudioManager` 用的是**索引**（`STREAM_MUSIC` 的
 * `getStreamMaxVolume()` 通常是 15，也可能不是），换算放在 [VolumeScale] 里，
 * 是纯函数，好单测。
 *
 * ## 这一层只放"规则"，不碰硬件
 *
 * 和 [PanelControl] 一个路子：换算和范围检查是纯的，真去调 `AudioManager` 的是
 * [SystemVolumeController]。二者的缝是 [VolumeHardware]。
 */

/**
 * 百分比和 Android 音量索引之间的换算。
 *
 * 两个方向都会**先夹到合法范围再算**，所以上游给什么数都不会算出越界索引——
 * 越界的 `setStreamVolume` 在 Android 上是直接抛 `SecurityException` 的。
 */
object VolumeScale {
    /** 全静音。语音说"静音"落到这里。 */
    const val MIN = 0

    /** 最大。 */
    const val MAX = 100

    fun isWithinRange(percent: Int): Boolean = percent in MIN..MAX

    fun clamp(percent: Int): Int = percent.coerceIn(MIN, MAX)

    /**
     * 百分比 → 索引。
     *
     * [maxIndex] 取 `getStreamMaxVolume()`。它理论上不该是 0，
     * 但真机上（固定音量策略的设备）确实见过，所以这里挡住：非正数就返回 0，
     * 不去做除零。
     */
    fun toIndex(percent: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        val scaled = clamp(percent) / 100f * maxIndex
        return Math.round(scaled).coerceIn(0, maxIndex)
    }

    /**
     * 索引 → 百分比。
     *
     * 和 [toIndex] 不是严格互逆的（`maxIndex` 不是 100 的约数时必然丢精度，
     * 比如 15 档下 50% 会落到 53%）。所以**回复用户时一律报回读到的百分比**，
     * 不报请求值——不然会出现"我说调到 50%，它答已调到 50%，一查是 53%"。
     */
    fun toPercent(index: Int, maxIndex: Int): Int {
        if (maxIndex <= 0) return 0
        val scaled = index.coerceIn(0, maxIndex) * 100f / maxIndex
        return Math.round(scaled).coerceIn(MIN, MAX)
    }
}

/**
 * 媒体音量的硬件访问口。
 *
 * 真机实现是 [SystemVolumeController]；单测里换成假的。
 */
interface VolumeHardware {
    /** 这台机器能不能调媒体音量。 */
    val volumeAvailable: Boolean

    /**
     * 把音量设成 [percent]（0..100，越界由调用方先挡掉）。
     *
     * 返回**实际生效**的百分比——DND、固定音量策略、厂商定制都可能让写入不生效，
     * 回读一遍才知道真实结果。失败返回 null。
     */
    fun setVolumePercent(percent: Int): Int?

    /** 当前音量百分比。读不到返回 null。 */
    fun volumePercent(): Int?

    /**
     * 最近一次**读到过**的非零音量，从没读到过返回 null。
     *
     * 用来支持"取消静音"：设成 0 就是静音，但静音之前的音量只有这里记着，
     * 否则模型只能瞎猜一个值出来。
     *
     * 它可能偏旧：我们没有监听音量变化，用户用音量键改过的值，只有在我们**读的那一刻**
     * 才看得到。所以它是"最近一次已知的非零音量"，不是"实时值"。
     */
    fun lastAudiblePercent(): Int?
}
