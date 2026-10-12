package me.xiaozhi.androidclient.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraSelectionTest {

    private fun external(id: String = "0") =
        CameraCandidate(id, CameraFacing.EXTERNAL, sensorOrientationDegrees = 0)

    private fun builtIn(
        id: String = "0",
        sensorOrientation: Int = 270,
        facing: CameraFacing = CameraFacing.BACK,
    ) = CameraCandidate(id, facing, sensorOrientationDegrees = sensorOrientation)

    // ---- choose：外接优先 → 用户选的朝向 → 第一台 ----

    @Test
    fun `prefers external camera when both are present`() {
        val builtInDevice = builtIn(id = "0")
        val usbDevice = external(id = "1")
        assertEquals(usbDevice, CameraSelection.choose(listOf(builtInDevice, usbDevice)))
    }

    @Test
    fun `external camera wins even when the user asked for the front one`() {
        // 外接优先是**刻意保留**的既有行为：RK3568 上麦克风和相机是同一个 USB 设备，
        // 没有它这台机器就没有输入。设置页也因此不把外接列进"用哪一个"里。
        val usbDevice = external(id = "1")
        val front = builtIn(id = "0", facing = CameraFacing.FRONT)
        assertEquals(usbDevice, CameraSelection.choose(listOf(front, usbDevice), CameraFacing.FRONT))
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
    fun `picks the facing the user asked for when no external camera is present`() {
        // 手机上的场景：后置在前、前置在后，用户选了前置就得拿到前置。
        val back = builtIn(id = "0", facing = CameraFacing.BACK)
        val front = builtIn(id = "1", facing = CameraFacing.FRONT)
        assertEquals(front, CameraSelection.choose(listOf(back, front), CameraFacing.FRONT))
        assertEquals(back, CameraSelection.choose(listOf(back, front), CameraFacing.BACK))
    }

    @Test
    fun `asking for a facing this machine does not have falls back to the first camera`() {
        // 存着"前置"的设置被搬到一台只有后置的机器上（或者升级时换了设备）：
        // 退回第一台，而不是拍不了照。
        val onlyBack = builtIn(id = "0", facing = CameraFacing.BACK)
        assertEquals(onlyBack, CameraSelection.choose(listOf(onlyBack), CameraFacing.FRONT))
    }

    @Test
    fun `a camera whose facing could not be read is matched only by the back preference`() {
        // UNKNOWN 在枚举里排在 BACK 之后，但它**不**等于 BACK，不该被"选后置"选中。
        // 真相不明时宁可退回第一台，也别宣称"这就是你要的那台"。
        val unknown = builtIn(id = "0", facing = CameraFacing.UNKNOWN)
        val back = builtIn(id = "1", facing = CameraFacing.BACK)
        assertEquals(back, CameraSelection.choose(listOf(unknown, back), CameraFacing.BACK))
        assertEquals(unknown, CameraSelection.choose(listOf(unknown), CameraFacing.BACK))
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
        for (facing in CameraFacing.entries) {
            for (sensor in listOf(0, 90, 180, 270)) {
                for (device in listOf(0, 90, 180, 270)) {
                    val degrees = CameraSelection.pixelRotationDegrees(
                        builtIn(sensorOrientation = sensor, facing = facing),
                        device,
                    )
                    assertTrue("$facing 传感器 $sensor 屏幕 $device 得到 $degrees", degrees in 0..359)
                }
            }
        }
    }

    // ---- 前置（朝向不同，公式里的符号不同） ----

    @Test
    fun `front camera adds the screen rotation instead of subtracting it`() {
        // 前置模组是"绕着镜头轴倒着装"的，同一个屏幕旋转对它和对后置的作用方向相反。
        // 实测口径：sensor 90、屏幕 0 → 90（后置这条会得 270，正好差一个符号）。
        val front = builtIn(sensorOrientation = 90, facing = CameraFacing.FRONT)
        assertEquals(90, CameraSelection.pixelRotationDegrees(front, deviceRotationDegrees = 0))
        assertEquals(270, CameraSelection.pixelRotationDegrees(front, deviceRotationDegrees = 180))
    }

    @Test
    fun `front camera wraps the sum at 360`() {
        val front = builtIn(sensorOrientation = 90, facing = CameraFacing.FRONT)
        assertEquals(0, CameraSelection.pixelRotationDegrees(front, deviceRotationDegrees = 270))
    }

    @Test
    fun `front camera in the other sensor orientation also adds`() {
        val front = builtIn(sensorOrientation = 270, facing = CameraFacing.FRONT)
        assertEquals(270, CameraSelection.pixelRotationDegrees(front, deviceRotationDegrees = 0))
        assertEquals(180, CameraSelection.pixelRotationDegrees(front, deviceRotationDegrees = 270))
    }

    @Test
    fun `front and back agree when the screen is not rotated`() {
        // 屏幕正着的时候只有 sensor 一项，两个朝向必须给出同一个角度——
        // 这条是"前置公式没把符号写反"的最省事的哨兵。
        for (sensor in listOf(0, 90, 180, 270)) {
            assertEquals(
                CameraSelection.pixelRotationDegrees(builtIn(sensorOrientation = sensor), 0),
                CameraSelection.pixelRotationDegrees(
                    builtIn(sensorOrientation = sensor, facing = CameraFacing.FRONT),
                    0,
                ),
            )
        }
    }

    @Test
    fun `a camera whose facing could not be read uses the back formula`() {
        // `LENS_FACING` 读不到时**现在**就是这个行为（原来代码里只有后置一条公式）。
        // 认不出来时按后置算也比按前置算安全：前置那套符号反了会歪得更远。
        val unknown = builtIn(sensorOrientation = 90, facing = CameraFacing.UNKNOWN)
        assertEquals(180, CameraSelection.pixelRotationDegrees(unknown, deviceRotationDegrees = 270))
        assertEquals(90, CameraSelection.pixelRotationDegrees(unknown, deviceRotationDegrees = 0))
        // 同一个输入按前置算会得到 0，所以这两条断言真的在区分两套公式。
        assertEquals(
            0,
            CameraSelection.pixelRotationDegrees(
                builtIn(sensorOrientation = 90, facing = CameraFacing.FRONT),
                270,
            ),
        )
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

    // ---- CameraFacing.parsePreference ----

    @Test
    fun `reads a stored facing preference`() {
        assertEquals(CameraFacing.FRONT, CameraFacing.parsePreference("FRONT"))
        assertEquals(CameraFacing.BACK, CameraFacing.parsePreference("BACK"))
        assertEquals(CameraFacing.FRONT, CameraFacing.parsePreference(" front "))
    }

    @Test
    fun `a bad or missing facing preference falls back to back`() {
        // 这个值来自 SharedPreferences，一个坏字符串不该让启动失败；
        // 后置也是绝大多数场景下的正确默认（说「看看这是什么」指的是眼前的东西）。
        assertEquals(CameraFacing.BACK, CameraFacing.parsePreference(null))
        assertEquals(CameraFacing.BACK, CameraFacing.parsePreference(""))
        assertEquals(CameraFacing.BACK, CameraFacing.parsePreference("front-facing"))
        assertEquals(CameraFacing.BACK, CameraFacing.parsePreference("EXTERNAL"))
        assertEquals(CameraFacing.BACK, CameraFacing.parsePreference("UNKNOWN"))
    }
}
