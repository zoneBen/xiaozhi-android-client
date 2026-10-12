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
    fun `an unfamiliar model now gets its builtin microphone for free`() {
        // 2026-10-12 反转：判据从"白名单里有没有它"改成"是不是确认没焊咪头的那批板子"。
        // 换一台新硬件就重新卡在自检页，正是这次要根治的问题。
        assertTrue(DeviceAudioProfile.supportsBuiltinMic("PLQ110"))
        assertTrue(DeviceAudioProfile.supportsBuiltinMic("Pixel 7"))
    }

    @Test
    fun `model match ignores case and surrounding whitespace`() {
        assertTrue(DeviceAudioProfile.supportsBuiltinMic(" tv5701 "))
        assertFalse(DeviceAudioProfile.supportsBuiltinMic(" ssrk3568-hmi "))
    }

    @Test
    fun `rk3568 board keeps the old deaf-builtin policy`() {
        // 板载采集通路没焊咪头，必须继续要求外接麦 —— 这是这个策略最想保住的机型。
        assertFalse(DeviceAudioProfile.supportsBuiltinMic("SSRK3568"))
        assertFalse(DeviceAudioProfile.supportsBuiltinMic("SSRK3568-HMI"))
        // 真机和日志里出现的是带版本号的整串，前缀匹配要能盖住。
        assertFalse(DeviceAudioProfile.supportsBuiltinMic("SSRK3568-HMI.6.1.75"))
    }

    @Test
    fun `a blank model is not treated as a deaf board`() {
        // 认不出机型时不该把人卡在自检页上：宁可漏报，也不要误报成"设备坏了"。
        assertTrue(DeviceAudioProfile.supportsBuiltinMic(""))
        assertTrue(DeviceAudioProfile.supportsBuiltinMic("   "))
        assertTrue(DeviceAudioProfile.supportsBuiltinMic(null))
    }

    @Test
    fun `deaf board detection and builtin usability are exact opposites`() {
        assertTrue(DeviceAudioProfile.isDeafBuiltinModel("SSRK3568-HMI.6.1.75"))
        assertFalse(DeviceAudioProfile.isDeafBuiltinModel("TV5701"))
        assertFalse(DeviceAudioProfile.isDeafBuiltinModel(null))
    }
}
