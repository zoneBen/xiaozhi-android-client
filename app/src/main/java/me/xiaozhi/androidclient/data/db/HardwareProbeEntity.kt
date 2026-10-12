package me.xiaozhi.androidclient.data.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 硬件探测结果缓存。**整张表永远只有一行**，主键固定为 [SINGLETON_ID]。
 *
 * 要解决的是「每次冷启动都重探一遍」：云台 / 补光灯 / 音量 / 摄像头朝向都是**构造时反射解析**
 * 的结果，进程生命周期内不会变，每次开机重新反射一遍纯属浪费。
 *
 * **麦克风是刻意的例外**：它的健康度是一个**持续巡检**（`startMicrophoneWatch`，每 5 秒一次），
 * 因为 USB 麦随时可以拔。这里缓存的只是「上一次探测时的结果」，用途是消除冷启动那一下
 * 「没有麦克风」的假警报——**巡检循环照旧保留**，缓存不替代它。
 *
 * 过期阈值见 `HardwareProbeRepository.PROBE_TTL_MS`；用户点「重新自检」会直接清掉这行重探。
 */
@Entity(tableName = "hardware_probe")
data class HardwareProbeEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val microphoneReady: Boolean,
    val microphoneMessage: String,
    /** 本机认不认得出云台/补光灯。为 false 时设置页那两个开关置灰。 */
    val hardwareControlAvailable: Boolean,
    /** 只说**硬件事实**（本机有没有这个硬件），不掺开关状态。 */
    val deviceControlStatus: String,
    /** 摄像头可选朝向，逗号分隔的 `CameraFacing.name`。 */
    val cameraFacingChoicesCsv: String,
    val volumeAvailable: Boolean,
    val probedAtEpochMs: Long,
) {
    companion object {
        /** 单例行的固定主键。用 REPLACE 写，不做 upsert 分支。 */
        const val SINGLETON_ID: Int = 0
    }
}
