package me.xiaozhi.androidclient.ui.settings

import me.xiaozhi.androidclient.model.RoleProfile
import me.xiaozhi.androidclient.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置首页三行摘要的分支覆盖。
 *
 * 这几句话是用户判断「要不要点进去」的唯一依据，念错了比不念更糟——
 * 他不会再进去核对。
 */
class SettingsSummariesTest {

    private fun role(id: String, name: String) = RoleProfile(
        id = id,
        displayName = name,
        deviceId = "",
        clientId = "",
        wakeWords = listOf(name),
    )

    @Test
    fun `还没有角色时说还没有角色`() {
        assertEquals("还没有角色", roleSettingsSummary(UiState()))
    }

    @Test
    fun `报的是当前正在用的那个角色，不是列表里的第一个`() {
        val state = UiState(
            roleProfiles = listOf(role("a", "小智"), role("b", "小美")),
            activeRoleId = "b",
        )
        assertEquals("当前：小美 · 共 2 个角色", roleSettingsSummary(state))
    }

    @Test
    fun `有角色但没有一个是当前时只报个数`() {
        val state = UiState(
            roleProfiles = listOf(role("a", "小智")),
            activeRoleId = "gone",
        )
        assertEquals("共 1 个角色", roleSettingsSummary(state))
    }

    @Test
    fun `两个开关都开着时如实报开`() {
        val state = UiState(
            deviceControlEnabled = true,
            hardwareControlEnabled = true,
            hardwareControlAvailable = true,
        )
        assertEquals("拍照与音量 开 · 云台与补光灯 开", deviceSettingsSummary(state))
    }

    @Test
    fun `本机没有云台硬件时说的是本机不支持，而不是关`() {
        // 这条最要紧：硬件不在时开关是灰的、存着的值哪怕是 true 也不生效。
        // 摘要若跟着念「开」，用户会带着一个假印象点进去，然后看到开关是灰的。
        val state = UiState(
            deviceControlEnabled = true,
            hardwareControlEnabled = true,
            hardwareControlAvailable = false,
        )
        assertEquals("拍照与音量 开 · 云台与补光灯 本机不支持", deviceSettingsSummary(state))
    }

    @Test
    fun `硬件在但用户关掉了时报关`() {
        val state = UiState(
            deviceControlEnabled = false,
            hardwareControlEnabled = false,
            hardwareControlAvailable = true,
        )
        assertEquals("拍照与音量 关 · 云台与补光灯 关", deviceSettingsSummary(state))
    }

    @Test
    fun `版号带上前缀，方便用户照着报给我们`() {
        val state = UiState(appVersionName = "1.2.8", appVersionCode = 12)
        assertEquals("v1.2.8 (Build 12)", aboutSettingsSummary(state))
    }

    @Test
    fun `默认 UiState 的版号来自构建信息而不是空串`() {
        // 版号是从 BuildConfig 透出来的，这里只是钉住"这条通路没断"。
        assertTrue(aboutSettingsSummary(UiState()).startsWith("v"))
    }
}
