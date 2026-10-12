package me.xiaozhi.androidclient.audio

import android.os.Build

/**
 * 机型级的音频输入能力表。
 *
 * ## 为什么需要它
 *
 * 唤醒/采集这套逻辑原来是**围绕一台 RK3568 工程样机**写的，那台机器的板载采集通路
 * **没有焊咪头**（实测 40 秒录音 RMS 恒定 7.4~7.6，平坦噪声），唯一能拾音的是外接
 * USB 摄像头麦。于是 [InputDeviceSelector] 把"健康"的判据定成了"USB/有线麦在不在位" ——
 * 在那个硬件上这是对的。
 *
 * 但"板载通路是聋的"是**那台机器的硬件事实，不是通用规律**。把那条规则原样套到别的机器上，
 * 结果是一台好机器被报成坏的：
 *
 * - 底栏常驻红字「没有检测到外接麦克风，请检查 USB 音频连接」；
 * - 更严重的是开机自检 `checksPassed` 里带了 `microphoneReady`，于是**开机遮罩永远不撤**，
 *   用户卡在自检页上，连主界面都进不去。
 *
 * ## 判据的两次翻转（读到这里再改）
 *
 * 1. **2026-10-11**：先加了白名单，只放 `TV5701`（一体机面板，板上焊了麦克风）。
 *    当时加的机型必须真机验过内置麦能拾到人声。
 * 2. **2026-10-12 改成黑名单**：白名单的问题是**每换一台新硬件就要重新卡一次自检页**，
 *    而"卡在自检页"意味着这台设备完全不能用 —— 每次换硬件都要改代码、重新发版。
 *    现在反过来：**默认相信内置麦能用**，只把"确认没焊咪头"的那批老板子拉黑。
 *
 * 代价要说清楚：黑名单对**没见过的聋板子**是漏报的（会显示正常，实际听不见）。
 * 白名单对**没见过的好机器**是误报的（直接不可用）。两害相权，选了漏报 ——
 * 漏报是"能用但没声音"，误报是"整台设备打不开"，后者严重得多。
 * 真发现新的聋板子，把机型前缀加进 [DEAF_BUILTIN_MODEL_PREFIXES] 就行。
 */
object DeviceAudioProfile {

    /**
     * 板载采集通路**没有焊咪头**的机型前缀。
     *
     * 用前缀而不是全等：同一个板子在 `Build.MODEL`、音频设备名、日志里出现的写法不一样
     * （`SSRK3568` / `SSRK3568-HMI` / `SSRK3568-HMI.6.1.75`），前缀能一次盖住。
     *
     * 加进来的条件只有一个：**在真机上确认过内置麦拾不到人声**。别照着参数表加。
     */
    private val DEAF_BUILTIN_MODEL_PREFIXES = setOf(
        "SSRK3568",
    )

    /**
     * 本机内置麦克风是不是能直接当输入源用。
     *
     * 除了 [DEAF_BUILTIN_MODEL_PREFIXES] 里那批聋板子，一律为 true。
     * 默认读 [Build.MODEL]，显式传参是为了能单测。
     *
     * @param model 机型名。比对前做 trim + 大写，免得大小写或空格差异漏判。
     *   认不出机型（空串）时也返回 true：宁可漏报，也不要把人卡在自检页上。
     */
    fun supportsBuiltinMic(model: String? = Build.MODEL): Boolean = !isDeafBuiltinModel(model)

    /** 是不是"板载采集通路没焊咪头"的那批老板子。退回板载麦时要靠它决定报警文案。 */
    fun isDeafBuiltinModel(model: String? = Build.MODEL): Boolean {
        val normalized = model?.trim()?.uppercase().orEmpty()
        return DEAF_BUILTIN_MODEL_PREFIXES.any { normalized.startsWith(it) }
    }

    /** 给日志用的一句话，出问题时一眼能看出走的是哪条策略。 */
    fun describe(model: String? = Build.MODEL): String =
        "model=${model.orEmpty()} builtinMicUsable=${supportsBuiltinMic(model)}"
}
