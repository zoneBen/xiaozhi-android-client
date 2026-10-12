package me.xiaozhi.androidclient.ui.settings

import me.xiaozhi.androidclient.email.EmailConfig
import me.xiaozhi.androidclient.email.EmailSecurity
import me.xiaozhi.androidclient.model.RoleProfile
import me.xiaozhi.androidclient.model.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置首页每一行摘要的分支覆盖。
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

    @Test
    fun `一条记录都没有时说还没有对话记录，而不是共 0 条`() {
        assertEquals("还没有对话记录", conversationSettingsSummary(UiState()))
    }

    @Test
    fun `有条数时报总数和当前角色的条数`() {
        val state = UiState(historyTotalCount = 128, historyActiveRoleCount = 12)
        assertEquals("共 128 条 · 当前角色 12 条", conversationSettingsSummary(state))
    }

    @Test
    fun `只有别的角色有记录时，总数照报而当前角色是 0`() {
        // 最容易写成"总数 > 0 就说有记录"的情况：这时候当前角色其实一条没有，
        // 清空按钮是灰的。摘要必须把这两个数分开报，用户才明白该切角色还是该清空。
        val state = UiState(historyTotalCount = 7, historyActiveRoleCount = 0)
        assertEquals("共 7 条 · 当前角色 0 条", conversationSettingsSummary(state))
    }

    @Test
    fun `关着邮件功能时说未启用，不提配置填没填`() {
        // 关着的时候"服务器填了没"是个无意义的问题——能力压根不存在。
        val off = EmailConfig(enabled = false, host = "", username = "")
        assertEquals("未启用", emailSettingsSummary(off))
    }

    @Test
    fun `开着但没填完时要明说没填完，不能只说已启用`() {
        // 这是最该被看见的一种：用户以为配好了，实际一发就报错。
        val halfDone = EmailConfig(enabled = true, host = "smtp.qq.com")
        assertEquals("已打开，但服务器/账号还没填完", emailSettingsSummary(halfDone))
    }

    @Test
    fun `填完了就报出默认发给谁`() {
        val summary = emailSettingsSummary(configured())
        assertEquals("已启用 · 默认发给 someone@qq.com", summary)
    }

    @Test
    fun `不加密要单独点一句`() {
        // 能用，但不该用——这种情况必须和正常配置读起来不一样。
        val summary = emailSettingsSummary(configured().copy(security = EmailSecurity.NONE, port = 25))
        assertTrue("期望点出不加密，实际「$summary」", summary.contains("未加密"))
        assertTrue("期望仍然报收件人，实际「$summary」", summary.contains("someone@qq.com"))
    }

    private fun configured() = EmailConfig(
        enabled = true,
        host = "smtp.qq.com",
        port = 465,
        security = EmailSecurity.SSL,
        username = "someone@qq.com",
        password = "authcode",
        defaultRecipient = "someone@qq.com",
    )
}
