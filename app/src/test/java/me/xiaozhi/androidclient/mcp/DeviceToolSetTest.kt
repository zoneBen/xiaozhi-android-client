package me.xiaozhi.androidclient.mcp

import me.xiaozhi.androidclient.device.FillLight
import me.xiaozhi.androidclient.device.PanelHardware
import me.xiaozhi.androidclient.device.PanelSnapshot
import me.xiaozhi.androidclient.device.VolumeHardware
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具层：中文语音说出来的话，怎么变成硬件动作，以及**关掉之后真的调不动**。
 *
 * 这里用假的 [PanelHardware] / [VolumeHardware]，所以不碰真板子、也不需要 Android 运行时。
 */
class DeviceToolSetTest {

    private val hardware = FakePanelHardware()
    private val volumeHardware = FakeVolumeHardware()

    private fun tools(enabled: Boolean = true) = DeviceToolSet(
        hardware = hardware,
        volume = volumeHardware,
        enabled = { enabled },
    )

    private fun call(name: String, vararg args: Pair<String, Any>): DeviceToolOutcome {
        val json = JSONObject()
        args.forEach { (key, value) -> json.put(key, value) }
        return tools().call(name, json)
    }

    private fun successText(outcome: DeviceToolOutcome): String {
        assertTrue("期望成功，实际是 $outcome", outcome is DeviceToolOutcome.Success)
        return (outcome as DeviceToolOutcome.Success).text
    }

    private fun failureText(outcome: DeviceToolOutcome): String {
        assertTrue("期望失败，实际是 $outcome", outcome is DeviceToolOutcome.Failure)
        return (outcome as DeviceToolOutcome.Failure).message
    }

    // ---- 可关闭（用户明确要求） ----

    @Test
    fun `disabled feature declares no tools at all`() {
        assertTrue(tools(enabled = false).schemas().isEmpty())
    }

    @Test
    fun `disabled feature refuses calls even if the server still has the old list`() {
        val message = failureText(
            tools(enabled = false).call(
                DeviceToolSet.LIGHT_TOOL,
                JSONObject().put("target", "all").put("on", true),
            ),
        )
        assertTrue(message.contains("已被用户关闭"))
        // 关键：硬件一次都没被碰过。
        assertTrue(hardware.fillLightCalls.isEmpty())
    }

    @Test
    fun `enabled feature declares the devices this machine actually has`() {
        val names = tools().schemas().map { it.getString("name") }
        assertEquals(
            listOf(
                DeviceToolSet.GIMBAL_TOOL,
                DeviceToolSet.LIGHT_TOOL,
                DeviceToolSet.VOLUME_TOOL,
                DeviceToolSet.STATUS_TOOL,
            ),
            names,
        )
    }

    @Test
    fun `a machine without a gimbal never declares the gimbal tool`() {
        val lightOnly = DeviceToolSet(
            hardware = FakePanelHardware(gimbalAvailable = false),
            volume = volumeHardware,
            enabled = { true },
        )
        val names = lightOnly.schemas().map { it.getString("name") }
        assertEquals(
            listOf(DeviceToolSet.LIGHT_TOOL, DeviceToolSet.VOLUME_TOOL, DeviceToolSet.STATUS_TOOL),
            names,
        )
    }

    @Test
    fun `a machine without lights never declares the light tool`() {
        val gimbalOnly = DeviceToolSet(
            hardware = FakePanelHardware(lightAvailable = false),
            volume = volumeHardware,
            enabled = { true },
        )
        val names = gimbalOnly.schemas().map { it.getString("name") }
        assertEquals(
            listOf(DeviceToolSet.GIMBAL_TOOL, DeviceToolSet.VOLUME_TOOL, DeviceToolSet.STATUS_TOOL),
            names,
        )
    }

    @Test
    fun `volume still works on a machine that has neither gimbal nor lights`() {
        // 音量是台 Android 设备就有，不该被这台机器没有灯连累。
        val plain = DeviceToolSet(
            hardware = FakePanelHardware(gimbalAvailable = false, lightAvailable = false),
            volume = volumeHardware,
            enabled = { true },
        )
        val names = plain.schemas().map { it.getString("name") }
        assertEquals(listOf(DeviceToolSet.VOLUME_TOOL, DeviceToolSet.STATUS_TOOL), names)
    }

    @Test
    fun `a machine without volume control never declares the volume tool`() {
        val noVolume = DeviceToolSet(
            hardware = hardware,
            volume = FakeVolumeHardware(volumeAvailable = false),
            enabled = { true },
        )
        val names = noVolume.schemas().map { it.getString("name") }
        assertEquals(
            listOf(DeviceToolSet.GIMBAL_TOOL, DeviceToolSet.LIGHT_TOOL, DeviceToolSet.STATUS_TOOL),
            names,
        )
    }

    // ---- 灯光 ----

    @Test
    fun `open the lights turns both fill lights on`() {
        val text = successText(call(DeviceToolSet.LIGHT_TOOL, "target" to "all", "on" to true))
        assertEquals(
            listOf(FillLight.BOTTOM to true, FillLight.TOP to true),
            hardware.fillLightCalls,
        )
        assertEquals("两盏补光灯已打开。", text)
    }

    @Test
    fun `turn the light off reaches the hardware as off`() {
        val text = successText(call(DeviceToolSet.LIGHT_TOOL, "target" to "bottom", "on" to false))
        assertEquals(listOf(FillLight.BOTTOM to false), hardware.fillLightCalls)
        assertEquals("下方补光灯已关闭。", text)
    }

    @Test
    fun `infrared goes down its own path`() {
        val text = successText(call(DeviceToolSet.LIGHT_TOOL, "target" to "infrared", "on" to true))
        assertEquals(listOf(true), hardware.infraredCalls)
        assertTrue(hardware.fillLightCalls.isEmpty())
        assertEquals("红外灯已打开。", text)
    }

    @Test
    fun `missing on is rejected instead of defaulting to on`() {
        // 猜成"打开"会让灯自己亮起来，而用户说的到底是什么谁也不知道。
        val message = failureText(call(DeviceToolSet.LIGHT_TOOL, "target" to "all"))
        assertTrue(message.contains("on"))
        assertTrue(hardware.fillLightCalls.isEmpty())
    }

    @Test
    fun `unknown light target is rejected`() {
        val message = failureText(
            call(DeviceToolSet.LIGHT_TOOL, "target" to "ceiling", "on" to true),
        )
        assertTrue(message.contains("target"))
    }

    @Test
    fun `a write failure is reported instead of pretending success`() {
        val broken = DeviceToolSet(
            hardware = FakePanelHardware(writeSucceeds = false),
            volume = volumeHardware,
            enabled = { true },
        )
        val outcome = broken.call(
            DeviceToolSet.LIGHT_TOOL,
            JSONObject().put("target", "all").put("on", true),
        )
        assertTrue(failureText(outcome).contains("写入失败"))
    }

    // ---- 云台 ----

    @Test
    fun `turn left rotates left by the given degrees`() {
        val text = successText(
            call(DeviceToolSet.GIMBAL_TOOL, "direction" to "left", "degrees" to 45),
        )
        assertEquals(listOf(45), hardware.rotations)
        assertEquals("云台已开始向左转 45 度。", text)
    }

    @Test
    fun `turn right with no degrees uses the default step`() {
        call(DeviceToolSet.GIMBAL_TOOL, "direction" to "right")
        assertEquals(listOf(-30), hardware.rotations)
    }

    @Test
    fun `home goes back to zero degrees`() {
        val text = successText(call(DeviceToolSet.GIMBAL_TOOL, "direction" to "home"))
        assertEquals(listOf(0), hardware.absoluteTargets)
        assertTrue(text.contains("原点"))
    }

    @Test
    fun `absolute angle goes to exactly that angle`() {
        call(DeviceToolSet.GIMBAL_TOOL, "direction" to "angle", "degrees" to 180)
        assertEquals(listOf(180), hardware.absoluteTargets)
    }

    @Test
    fun `an unknown direction is rejected`() {
        val message = failureText(call(DeviceToolSet.GIMBAL_TOOL, "direction" to "sideways"))
        assertTrue(message.contains("direction"))
        assertTrue(hardware.rotations.isEmpty())
    }

    @Test
    fun `degrees outside the turn are rejected`() {
        val message = failureText(
            call(DeviceToolSet.GIMBAL_TOOL, "direction" to "left", "degrees" to 0),
        )
        assertTrue(message.contains("degrees"))
        assertTrue(hardware.rotations.isEmpty())
    }

    @Test
    fun `no gimbal means the tool fails instead of claiming a turn`() {
        val outcome = DeviceToolSet(
            hardware = FakePanelHardware(gimbalAvailable = false),
            volume = volumeHardware,
            enabled = { true },
        ).call(DeviceToolSet.GIMBAL_TOOL, JSONObject().put("direction", "left"))
        assertTrue(failureText(outcome).contains("没有可用的云台"))
    }

    // ---- 媒体音量 ----

    @Test
    fun `turning the volume down goes to the hardware as a percent`() {
        val text = successText(call(DeviceToolSet.VOLUME_TOOL, "volume" to 30))
        assertEquals(listOf(30), volumeHardware.setCalls)
        assertEquals("媒体音量已调到 30%。", text)
    }

    @Test
    fun `mute is volume zero and the reply keeps the pre-mute level`() {
        volumeHardware.lastAudibleValue = 40
        val text = successText(call(DeviceToolSet.VOLUME_TOOL, "volume" to 0))
        assertEquals(listOf(0), volumeHardware.setCalls)
        assertTrue(text.contains("静音"))
        assertTrue(text.contains("静音前是 40%"))
    }

    @Test
    fun `the reply reports what the device actually applied`() {
        // Android 是 15 档，50% 会落到 53%，回复必须说 53 而不是 50。
        volumeHardware.appliedPercent = 53
        val text = successText(call(DeviceToolSet.VOLUME_TOOL, "volume" to 50))
        assertTrue(text.contains("已调到 53%"))
        assertTrue(text.contains("你要的 50%"))
    }

    @Test
    fun `missing volume is rejected instead of defaulting to something`() {
        val message = failureText(call(DeviceToolSet.VOLUME_TOOL))
        assertTrue(message.contains("volume"))
        assertTrue(volumeHardware.setCalls.isEmpty())
    }

    @Test
    fun `volume outside 0 to 100 is rejected, not clamped`() {
        // schema 里写了 0-100，给到 150 是模型自己搞错了；悄悄夹成 100 会把错误盖掉。
        val tooLoud = failureText(call(DeviceToolSet.VOLUME_TOOL, "volume" to 150))
        assertTrue(tooLoud.contains("0 到 100"))
        val negative = failureText(call(DeviceToolSet.VOLUME_TOOL, "volume" to -5))
        assertTrue(negative.contains("0 到 100"))
        assertTrue(volumeHardware.setCalls.isEmpty())
    }

    @Test
    fun `a volume write failure is reported instead of pretending success`() {
        val failing = DeviceToolSet(
            hardware = hardware,
            volume = FakeVolumeHardware(writeSucceeds = false),
            enabled = { true },
        )
        val outcome = failing.call(DeviceToolSet.VOLUME_TOOL, JSONObject().put("volume", 30))
        assertTrue(failureText(outcome).contains("音量设置失败"))
    }

    @Test
    fun `no volume control means the tool fails instead of claiming a change`() {
        val outcome = DeviceToolSet(
            hardware = hardware,
            volume = FakeVolumeHardware(volumeAvailable = false),
            enabled = { true },
        ).call(DeviceToolSet.VOLUME_TOOL, JSONObject().put("volume", 30))
        assertTrue(failureText(outcome).contains("没有可用的音量控制"))
    }

    @Test
    fun `disabled feature refuses the volume tool too`() {
        val message = failureText(
            tools(enabled = false).call(
                DeviceToolSet.VOLUME_TOOL,
                JSONObject().put("volume", 30),
            ),
        )
        assertTrue(message.contains("已被用户关闭"))
        assertTrue(volumeHardware.setCalls.isEmpty())
    }

    // ---- 状态 ----

    @Test
    fun `status reports the angle, each light and the volume`() {
        hardware.snapshotValue = PanelSnapshot(
            angleDegrees = 45,
            rotating = true,
            bottomLightOn = true,
            topLightOn = false,
            infraredOn = null,
        )
        volumeHardware.currentPercent = 60
        val text = successText(call(DeviceToolSet.STATUS_TOOL))
        assertTrue(text.contains("云台角度 45 度，正在转动"))
        assertTrue(text.contains("下方补光灯：开"))
        assertTrue(text.contains("顶部补光灯：关"))
        assertTrue(text.contains("红外灯：读不到"))
        assertTrue(text.contains("媒体音量 60%"))
    }

    @Test
    fun `status tells the model what to restore when muted`() {
        // 没有这句，模型只能猜一个 50 出来回答"取消静音"。
        volumeHardware.currentPercent = 0
        volumeHardware.lastAudibleValue = 40
        val text = successText(call(DeviceToolSet.STATUS_TOOL))
        assertTrue(text.contains("媒体音量 0%（静音，静音前是 40%）"))
    }

    @Test
    fun `status says so instead of inventing a number when the volume is unreadable`() {
        volumeHardware.currentPercent = null
        val text = successText(call(DeviceToolSet.STATUS_TOOL))
        assertTrue(text.contains("媒体音量：读不到"))
    }

    @Test
    fun `unknown tool name is not handled silently`() {
        val outcome = tools().call("self.timer.set", JSONObject())
        assertTrue(failureText(outcome).contains("Unknown tool"))
    }
}

private class FakePanelHardware(
    override val gimbalAvailable: Boolean = true,
    override val lightAvailable: Boolean = true,
    private val writeSucceeds: Boolean = true,
) : PanelHardware {

    val fillLightCalls = mutableListOf<Pair<FillLight, Boolean>>()
    val infraredCalls = mutableListOf<Boolean>()

    /** `rotateBy` 收到的相对角度。 */
    val rotations = mutableListOf<Int>()

    /** `rotateTo` 收到的绝对角度。 */
    val absoluteTargets = mutableListOf<Int>()

    var snapshotValue = PanelSnapshot(0, false, null, null, null)

    override fun setFillLight(light: FillLight, on: Boolean): Boolean {
        fillLightCalls += light to on
        return writeSucceeds
    }

    override fun setInfrared(on: Boolean): Boolean {
        infraredCalls += on
        return writeSucceeds
    }

    override fun rotateBy(deltaDegrees: Int): Boolean {
        rotations += deltaDegrees
        return true
    }

    override fun rotateTo(degrees: Int): Boolean {
        absoluteTargets += degrees
        return true
    }

    override fun snapshot(): PanelSnapshot = snapshotValue

    override fun releaseAll() = Unit

    override fun close() = Unit
}

private class FakeVolumeHardware(
    override val volumeAvailable: Boolean = true,
    private val writeSucceeds: Boolean = true,
) : VolumeHardware {

    /** `setVolumePercent` 收到的百分比。 */
    val setCalls = mutableListOf<Int>()

    /** 当前音量，null = 读不到。 */
    var currentPercent: Int? = 50

    /**
     * 写入后"实际生效"的百分比，默认就是请求值。
     * 设成别的数用来模拟 15 档取整（请求 50 落到 53）。
     */
    var appliedPercent: Int? = null

    /** "静音前是多少"。 */
    var lastAudibleValue: Int? = null

    override fun setVolumePercent(percent: Int): Int? {
        setCalls += percent
        if (!writeSucceeds) return null
        val applied = appliedPercent ?: percent
        currentPercent = applied
        if (applied > 0) lastAudibleValue = applied
        return applied
    }

    override fun volumePercent(): Int? = currentPercent

    override fun lastAudiblePercent(): Int? = lastAudibleValue
}
