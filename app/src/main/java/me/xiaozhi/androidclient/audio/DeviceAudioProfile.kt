package me.xiaozhi.androidclient.audio

import android.os.Build

/**
 * 机型级的音频输入能力表。
 *
 * ## 为什么需要它（2026-10-11 TV5701 适配）
 *
 * 唤醒/采集这套逻辑原来是**围绕一台 RK3568 工程样机**写的，那台机器的板载采集通路
 * **没有焊咪头**（实测 40 秒录音 RMS 恒定 7.4~7.6，平坦噪声），唯一能拾音的是外接
 * USB 摄像头麦。于是 [InputDeviceSelector] 把"健康"的判据定成了"USB/有线麦在不在位" ——
 * 在那个硬件上这是对的。
 *
 * 但 TV5701 是一体机面板，**板上焊了麦克风**（音频 HAL 里是 `Built-In Mic` + `Built-In
 * Back Mic`），没有 USB 音频设备也完全能用。把前者那条规则原样搬过来，结果就是：
 *
 * - 底栏常驻红字「没有检测到外接麦克风，请检查 USB 音频连接」；
 * - 更严重的是开机自检 `checksPassed` 里带了 `microphoneReady`，于是**开机遮罩永远不撤**，
 *   用户卡在自检页上，连主界面都进不去（实测 2026-10-11）。
 *
 * 所以"内置麦能不能拾音"是**硬件属性**，只能按机型判，不能全局放宽 —— 老机器的板载
 * 通路是聋的，全局放宽会把"听不见"误判成"正常"，那是这个文件最想避免的事。
 */
object DeviceAudioProfile {

    /**
     * 内置麦克风可以直接当输入源用的机型。
     *
     * 加进来的条件只有一个：**在真机上确认过内置麦能拾到人声**。别照着参数表加。
     */
    private val BUILTIN_MIC_MODELS = setOf(
        "TV5701",
    )

    /**
     * 本机内置麦克风是否可用。默认读 [Build.MODEL]，显式传参是为了能单测。
     *
     * @param model 机型名。比对前做 trim + 大写，免得大小写或空格差异漏判。
     */
    fun supportsBuiltinMic(model: String? = Build.MODEL): Boolean =
        model?.trim()?.uppercase() in BUILTIN_MIC_MODELS

    /** 给日志用的一句话，出问题时一眼能看出走的是哪条策略。 */
    fun describe(model: String? = Build.MODEL): String =
        "model=${model.orEmpty()} builtinMicUsable=${supportsBuiltinMic(model)}"
}
