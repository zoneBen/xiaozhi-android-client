package me.xiaozhi.androidclient.mcp

import me.xiaozhi.androidclient.device.FillLight
import me.xiaozhi.androidclient.device.PanelHardware
import me.xiaozhi.androidclient.device.PanelSnapshot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具层：中文语音说出来的话，怎么变成硬件动作，以及**关掉之后真的调不动**。
 *
 * 这里用假的 [PanelHardware]，所以不碰真板子、也不需要 Android 运行时。
 */
class DeviceToolSetTest {

    private val hardware = FakePanelHardware()
    private val logs = mutableListOf<String>()

    private fun tools(enabled: Boolean = true) = DeviceToolSet(
        hardware = hardware,
        enabled = { enabled },
        log = logs::add,
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
            listOf(DeviceToolSet.GIMBAL_TOOL, DeviceToolSet.LIGHT_TOOL, DeviceToolSet.STATUS_TOOL),
            names,
        )
    }

    @Test
    fun `a machine without a gimbal never declares the gimbal tool`() {
        val lightOnly = DeviceToolSet(
            hardware = FakePanelHardware(gimbalAvailable = false),
            enabled = { true },
            log = logs::add,
        )
        val names = lightOnly.schemas().map { it.getString("name") }
        assertEquals(listOf(DeviceToolSet.LIGHT_TOOL, DeviceToolSet.STATUS_TOOL), names)
    }

    @Test
    fun `a machine without lights never declares the light tool`() {
        val gimbalOnly = DeviceToolSet(
            hardware = FakePanelHardware(lightAvailable = false),
            enabled = { true },
            log = logs::add,
        )
        val names = gimbalOnly.schemas().map { it.getString("name") }
        assertEquals(listOf(DeviceToolSet.GIMBAL_TOOL, DeviceToolSet.STATUS_TOOL), names)
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
            enabled = { true },
            log = logs::add,
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
            enabled = { true },
            log = logs::add,
        ).call(DeviceToolSet.GIMBAL_TOOL, JSONObject().put("direction", "left"))
        assertTrue(failureText(outcome).contains("没有可用的云台"))
    }

    // ---- 状态 ----

    @Test
    fun `status reports the angle and each light`() {
        hardware.snapshotValue = PanelSnapshot(
            angleDegrees = 45,
            rotating = true,
            bottomLightOn = true,
            topLightOn = false,
            infraredOn = null,
        )
        val text = successText(call(DeviceToolSet.STATUS_TOOL))
        assertTrue(text.contains("云台角度 45 度，正在转动"))
        assertTrue(text.contains("下方补光灯：开"))
        assertTrue(text.contains("顶部补光灯：关"))
        assertTrue(text.contains("红外灯：读不到"))
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
