package me.xiaozhi.androidclient.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSelectionTest {

    private fun external(id: String = "0") = CameraCandidate(id, isExternal = true, sensorOrientationDegrees = 0)

    private fun builtIn(id: String = "0", sensorOrientation: Int = 270) =
        CameraCandidate(id, isExternal = false, sensorOrientationDegrees = sensorOrientation)

    // ---- choose：外接优先，没有就用第一台 ----

    @Test
    fun `prefers external camera when both are present`() {
        val builtInDevice = builtIn(id = "0")
        val usbDevice = external(id = "1")
        assertEquals(usbDevice, CameraSelection.choose(listOf(builtInDevice, usbDevice)))
    }

    @Test
    fun `falls back to built-in camera when no external one exists`() {
        // 这就是 TV5701 的场景：只有 internal/0，原来这里抛 No external USB camera is available。
        val builtInDevice = builtIn(id = "0")
        assertEquals(builtInDevice, CameraSelection.choose(listOf(builtInDevice)))
    }

    @Test
    fun `falls back to the first camera when several built-ins exist`() {
        val first = builtIn(id = "0")
        val second = builtIn(id = "1", sensorOrientation = 90)
        assertEquals(first, CameraSelection.choose(listOf(first, second)))
    }

    @Test
    fun `returns null when the device has no camera at all`() {
        assertNull(CameraSelection.choose(emptyList()))
    }

    // ---- pixelRotationDegrees ----

    @Test
    fun `external camera keeps the hard-coded flipped-mount rotation`() {
        // 外接模组上报 SENSOR_ORIENTATION=0，但物理倒装，所以无论屏幕怎么转都用 180。
        val usbDevice = external()
        assertEquals(180, CameraSelection.pixelRotationDegrees(usbDevice, deviceRotationDegrees = 0))
        assertEquals(180, CameraSelection.pixelRotationDegrees(usbDevice, deviceRotationDegrees = 270))
    }

    @Test
    fun `built-in camera subtracts the screen rotation from the sensor orientation`() {
        // TV5701 实测：SENSOR_ORIENTATION=270，App 跑在 rotation 3（270°）→ 不用再转。
        assertEquals(0, CameraSelection.pixelRotationDegrees(builtIn(sensorOrientation = 270), 270))
    }

    @Test
    fun `built-in camera wraps around instead of going negative`() {
        // 传感器 90、屏幕 270 → 90 - 270 = -180 → 要 180，而不是 -180 或 540。
        assertEquals(180, CameraSelection.pixelRotationDegrees(builtIn(sensorOrientation = 90), 270))
    }

    @Test
    fun `built-in camera in natural orientation rotates by the full sensor orientation`() {
        assertEquals(90, CameraSelection.pixelRotationDegrees(builtIn(sensorOrientation = 90), 0))
        assertEquals(270, CameraSelection.pixelRotationDegrees(builtIn(sensorOrientation = 270), 0))
    }

    @Test
    fun `rotation is always within 0 up to 359`() {
        for (sensor in listOf(0, 90, 180, 270)) {
            for (device in listOf(0, 90, 180, 270)) {
                val degrees = CameraSelection.pixelRotationDegrees(builtIn(sensorOrientation = sensor), device)
                assertTrue("传感器 $sensor 屏幕 $device 得到 $degrees", degrees in 0..359)
            }
        }
    }

    // ---- rotationDegreesOf ----

    @Test
    fun `maps surface rotation constants to degrees`() {
        assertEquals(0, CameraSelection.rotationDegreesOf(0))
        assertEquals(90, CameraSelection.rotationDegreesOf(1))
        assertEquals(180, CameraSelection.rotationDegreesOf(2))
        assertEquals(270, CameraSelection.rotationDegreesOf(3))
    }

    @Test
    fun `unknown rotation values fall back to zero`() {
        assertEquals(0, CameraSelection.rotationDegreesOf(-1))
        assertEquals(0, CameraSelection.rotationDegreesOf(99))
    }

    // ---- chooseJpegSize ----

    @Test
    fun `picks the preferred size when the camera supports it`() {
        val supported = listOf(FrameSize(1440, 720), FrameSize(1280, 720), FrameSize(720, 480))
        assertEquals(FrameSize(1280, 720), CameraSelection.chooseJpegSize(supported))
    }

    @Test
    fun `does not pick a larger same-shape size than preferred`() {
        val supported = listOf(FrameSize(2560, 1440), FrameSize(1280, 720))
        assertEquals(FrameSize(1280, 720), CameraSelection.chooseJpegSize(supported))
    }

    @Test
    fun `prefers a smaller same-shape size over a bigger odd-shape one`() {
        // 2:1 的 1440x720 面积更大，但会把 16:9 的画面裁掉，不该选它。
        val supported = listOf(FrameSize(1440, 720), FrameSize(640, 360))
        assertEquals(FrameSize(640, 360), CameraSelection.chooseJpegSize(supported))
    }

    @Test
    fun `falls back to the smallest size when everything is larger than preferred`() {
        val supported = listOf(FrameSize(1920, 1080), FrameSize(3840, 2160))
        assertEquals(FrameSize(1920, 1080), CameraSelection.chooseJpegSize(supported))
    }

    @Test
    fun `still picks a size when the camera offers no 16-by-9 option at all`() {
        // 4:3 传感器常见的组合：同长宽比的一个都没有，那就放开比例限制，
        // 仍然优先不超过首选面积的，别为了"形状对"去要一张更大的图。
        val supported = listOf(FrameSize(1440, 720), FrameSize(640, 480))
        assertEquals(FrameSize(640, 480), CameraSelection.chooseJpegSize(supported))
    }

    @Test
    fun `does not invent a size when every odd-shaped option is larger than preferred`() {
        val supported = listOf(FrameSize(1280, 960), FrameSize(1600, 1200))
        assertEquals(FrameSize(1280, 960), CameraSelection.chooseJpegSize(supported))
    }

    @Test
    fun `returns null when the camera reports no output sizes`() {
        assertNull(CameraSelection.chooseJpegSize(emptyList()))
    }

    // ---- choosePrimingSize ----

    @Test
    fun `priming stream takes the smallest supported size`() {
        val supported = listOf(FrameSize(1440, 720), FrameSize(1280, 720), FrameSize(720, 480))
        assertEquals(FrameSize(720, 480), CameraSelection.choosePrimingSize(supported))
    }

    @Test
    fun `priming stream returns null when nothing is supported`() {
        assertNull(CameraSelection.choosePrimingSize(emptyList()))
    }

    // ---- FrameSize ----

    @Test
    fun `compares aspect ratio by cross multiplication`() {
        assertTrue(FrameSize(1280, 720).hasSameShapeAs(FrameSize(640, 360)))
        assertFalse(FrameSize(1280, 720).hasSameShapeAs(FrameSize(1440, 720)))
        assertFalse(FrameSize(1280, 720).hasSameShapeAs(FrameSize(720, 480)))
    }

    @Test
    fun `computes area`() {
        assertEquals(921_600, FrameSize(1280, 720).area)
    }
}
