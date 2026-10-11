package me.xiaozhi.androidclient.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioInputHealthTest {

    @Test
    fun `external microphone is ready and named as-is`() {
        val health = AudioInputHealth(
            externalMicPresent = true,
            builtinMicUsable = false,
            label = "USB-Audio - DECXIN",
            onlyBuiltin = false,
        )
        assertTrue(health.canCapture)
        assertEquals("麦克风：USB-Audio - DECXIN", health.message)
    }

    @Test
    fun `tv5701 builtin microphone is ready and no longer reported as missing`() {
        val health = AudioInputHealth(
            externalMicPresent = false,
            builtinMicUsable = true,
            label = "TV5701",
            onlyBuiltin = false,
        )
        assertTrue(health.canCapture)
        assertEquals("麦克风：TV5701（内置）", health.message)
    }

    @Test
    fun `deaf builtin channel still warns about the missing usb microphone`() {
        val health = AudioInputHealth(
            externalMicPresent = false,
            builtinMicUsable = false,
            label = "SSRK3568-HMI.6.1.75",
            onlyBuiltin = true,
        )
        assertFalse(health.canCapture)
        assertEquals("没有检测到外接麦克风，请检查 USB 音频连接", health.message)
    }

    @Test
    fun `no input device at all reads differently from a deaf builtin one`() {
        val health = AudioInputHealth(
            externalMicPresent = false,
            builtinMicUsable = false,
            label = "未知设备",
            onlyBuiltin = false,
        )
        assertFalse(health.canCapture)
        assertEquals("没有检测到任何麦克风", health.message)
    }

    @Test
    fun `external microphone outranks builtin in the displayed label`() {
        val health = AudioInputHealth(
            externalMicPresent = true,
            builtinMicUsable = true,
            label = "USB-Audio - Yundea 1076",
            onlyBuiltin = false,
        )
        assertTrue(health.canCapture)
        assertEquals("麦克风：USB-Audio - Yundea 1076", health.message)
    }
}
