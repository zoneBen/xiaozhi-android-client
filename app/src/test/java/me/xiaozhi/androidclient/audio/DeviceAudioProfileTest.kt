package me.xiaozhi.androidclient.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAudioProfileTest {

    @Test
    fun `tv5701 builtin microphone counts as usable`() {
        assertTrue(DeviceAudioProfile.supportsBuiltinMic("TV5701"))
    }

    @Test
    fun `model match ignores case and surrounding whitespace`() {
        assertTrue(DeviceAudioProfile.supportsBuiltinMic(" tv5701 "))
    }

    @Test
    fun `rk3568 board keeps the old deaf-builtin policy`() {
        // 板载采集通路没焊咪头，必须继续要求外接麦 —— 这是这个策略最想保住的机型。
        assertFalse(DeviceAudioProfile.supportsBuiltinMic("SSRK3568-HMI"))
    }

    @Test
    fun `unknown and missing models never get builtin microphones for free`() {
        assertFalse(DeviceAudioProfile.supportsBuiltinMic("Pixel 7"))
        assertFalse(DeviceAudioProfile.supportsBuiltinMic(""))
        assertFalse(DeviceAudioProfile.supportsBuiltinMic(null))
    }
}
