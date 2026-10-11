package me.xiaozhi.androidclient.device

import android.content.Context
import android.media.AudioManager
import android.util.Log

private const val TAG = "XiaozhiClient"

/**
 * [VolumeHardware] 的真机实现：调 `STREAM_MUSIC`。
 *
 * ## 就一个流
 *
 * 小智的播放是 `USAGE_MEDIA`（见 [me.xiaozhi.androidclient.audio.XiaozhiAudioEngine]），
 * 路由到 `STREAM_MUSIC`，所以调它就能改变说话音量。铃声/闹钟/通话不动。
 *
 * 清单里已有的 `MODIFY_AUDIO_SETTINGS` 够用，不需要新权限。
 *
 * ## 不用单独开线程
 *
 * 和云台（[BodyGuargzController]）不一样：那边电机一次只认一条指令、还得轮询等它转完，
 * 所以排在一条专用线程上；`setStreamVolume` 是同步且立刻返回的，直接调就行，
 * 没有先后约束，也就没有队列。
 */
class SystemVolumeController(context: Context) : VolumeHardware {

    private val audioManager = context.applicationContext
        .getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /**
     * 最近一次**读到过**的非零音量。
     *
     * 开机先读一次做种子：这样"静音 → 取消静音"即使中间没人设过别的值，
     * 也有个真实的底可用，而不是让模型猜一个 50 出来。
     */
    @Volatile
    private var lastAudible: Int? = readPercent()

    override val volumeAvailable: Boolean
        get() = audioManager != null

    override fun setVolumePercent(percent: Int): Int? {
        val manager = audioManager ?: return null
        val requested = VolumeScale.clamp(percent)
        val index = VolumeScale.toIndex(requested, maxIndex(manager))
        // flags 传 0 = 不弹系统音量条。这是台挂在墙上的面板，弹一条挡住画面的
        // 系统 UI 出来，不如让语音回复去交代结果。
        val ok = runCatching {
            manager.setStreamVolume(AudioManager.STREAM_MUSIC, index, 0)
        }.isSuccess
        if (!ok) {
            Log.w(TAG, "[VOLUME] setStreamVolume(索引 $index) 失败")
            return null
        }
        // 回读，不返回请求值：勿扰模式、固定音量策略、厂商定制都可能让上面那句白写，
        // 只有回读才知道真实结果。工具层拿这个数去回复用户。
        val actual = readPercent()
        Log.d(TAG, "[VOLUME] 请求 $requested% -> 索引 $index -> 实际 ${actual ?: "读不到"}%")
        return actual
    }

    override fun volumePercent(): Int? = readPercent()

    override fun lastAudiblePercent(): Int? = lastAudible

    /** 设置页副标题用的一句话。 */
    fun availabilityLabel(): String =
        if (volumeAvailable) "可调媒体音量" else "调不了音量"

    /**
     * 读当前音量，顺手更新 [lastAudible]。
     *
     * 「每次读到非零值就记下来」是这里唯一的规则：读到 0（静音）**不覆盖**，
     * 这样静音前的音量才留得住。
     */
    private fun readPercent(): Int? {
        val manager = audioManager ?: return null
        val percent = runCatching {
            VolumeScale.toPercent(
                manager.getStreamVolume(AudioManager.STREAM_MUSIC),
                maxIndex(manager),
            )
        }.getOrNull() ?: return null
        if (percent > 0) lastAudible = percent
        return percent
    }

    /** `getStreamMaxVolume` 正常返回 15 左右；拿不到就返回 0，由 [VolumeScale] 兜底。 */
    private fun maxIndex(manager: AudioManager): Int =
        runCatching { manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) }.getOrDefault(0)
}
