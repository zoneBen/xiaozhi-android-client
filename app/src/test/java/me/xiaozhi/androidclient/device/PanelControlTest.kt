package me.xiaozhi.androidclient.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 云台/补光灯的纯规则。这些数字全部抄自参考实现 `MeBgCamCtrl`，
 * 一旦被改坏，真机上就是"叫它左转它乱转"，所以值得钉死。
 */
class PanelControlTest {

    @Test
    fun `angle wraps around a full turn`() {
        assertEquals(0, PanelAngles.normalize(0))
        assertEquals(30, PanelAngles.normalize(30))
        // 360 和 0 在物理上是同一个位置。
        assertEquals(0, PanelAngles.normalize(360))
        assertEquals(330, PanelAngles.normalize(-30))
        assertEquals(40, PanelAngles.normalize(400))
        assertEquals(320, PanelAngles.normalize(-400))
    }

    @Test
    fun `turning past zero wraps the other way`() {
        assertEquals(10, PanelAngles.afterTurn(350, 20))
        assertEquals(350, PanelAngles.afterTurn(10, -20))
        assertEquals(0, PanelAngles.afterTurn(0, 360))
    }

    @Test
    fun `motor units use the reference scale factor`() {
        // 参考实现：(target - current) * 1.3846154f，再截断成整数。
        assertEquals(41, PanelAngles.toMotorUnits(30))
        assertEquals(-41, PanelAngles.toMotorUnits(-30))
        assertEquals(0, PanelAngles.toMotorUnits(0))
    }

    @Test
    fun `fill light write value matches the reference protocol`() {
        // 参考实现：val value = "${id * 2} ${if (on) 1 else 3}"
        assertEquals("2 1", FillLight.value(FillLight.BOTTOM, on = true))
        assertEquals("2 3", FillLight.value(FillLight.BOTTOM, on = false))
        assertEquals("4 1", FillLight.value(FillLight.TOP, on = true))
        assertEquals("4 3", FillLight.value(FillLight.TOP, on = false))
    }

    @Test
    fun `fill light channels map to the sysfs nodes we saw on the device`() {
        assertEquals(1, FillLight.BOTTOM.channel)
        assertEquals(2, FillLight.TOP.channel)
    }

    @Test
    fun `fill light state is only reported when the value is understood`() {
        assertEquals(true, FillLight.parseState("2 1"))
        assertEquals(false, FillLight.parseState("2 3"))
        // 参考实现的网页界面也把单独的 "1" 当成开。
        assertEquals(true, FillLight.parseState("1"))
        assertEquals(false, FillLight.parseState("2 0"))
        // 读不到就返回 null —— 让界面写"读不到"，而不是编一个"关"出来。
        assertNull(FillLight.parseState(""))
        assertNull(FillLight.parseState(null))
        assertNull(FillLight.parseState("whatever"))
    }

    @Test
    fun `gimbal direction accepts the words the model is likely to send`() {
        assertEquals(GimbalDirection.LEFT, GimbalDirection.parse("left"))
        assertEquals(GimbalDirection.LEFT, GimbalDirection.parse(" LEFT "))
        assertEquals(GimbalDirection.RIGHT, GimbalDirection.parse("right"))
        assertEquals(GimbalDirection.HOME, GimbalDirection.parse("home"))
        assertEquals(GimbalDirection.HOME, GimbalDirection.parse("center"))
        assertEquals(GimbalDirection.ANGLE, GimbalDirection.parse("angle"))
        // 认不出来就是认不出来，不能默认成某个方向。
        assertNull(GimbalDirection.parse("sideways"))
        assertNull(GimbalDirection.parse(null))
    }
}
