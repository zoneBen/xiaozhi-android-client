package me.xiaozhi.androidclient.ota

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [isUpdateIndexConfigured] 的纯逻辑单测。
 *
 * 这条判断决定「检查更新」是发三个注定失败的请求还是一句话都不发，
 * 所以边界（空值、解析不出 host、路径里带 .invalid）都要钉住。
 */
class UpdateIndexPolicyTest {

    @Test
    fun `占位地址按没配处理`() {
        assertFalse(isUpdateIndexConfigured("https://update.example.invalid/xiaozhi-android-client/version.json"))
    }

    @Test
    fun `占位后缀不分大小写`() {
        assertFalse(isUpdateIndexConfigured("https://update.example.INVALID/version.json"))
        assertFalse(isUpdateIndexConfigured("https://Update.Example.Invalid/version.json"))
    }

    @Test
    fun `裸的 invalid 顶级域也算占位`() {
        assertFalse(isUpdateIndexConfigured("https://invalid/version.json"))
    }

    @Test
    fun `null 与空串按没配处理`() {
        assertFalse(isUpdateIndexConfigured(null))
        assertFalse(isUpdateIndexConfigured(""))
        assertFalse(isUpdateIndexConfigured("   "))
    }

    @Test
    fun `解析不出 host 的地址按没配处理`() {
        // 相对路径、没有 host 的写法、以及根本不是 URL 的字符串
        assertFalse(isUpdateIndexConfigured("/version.json"))
        assertFalse(isUpdateIndexConfigured("version.json"))
        assertFalse(isUpdateIndexConfigured("这不是一个地址"))
    }

    @Test
    fun `正常域名算配好了`() {
        assertTrue(isUpdateIndexConfigured("https://raw.githubusercontent.com/fenghui12/xiaozhi-android-client/main/version.json"))
        assertTrue(isUpdateIndexConfigured("http://127.0.0.1:8080/version.json"))
    }

    @Test
    fun `镜像前缀地址算配好了`() {
        // 真实检查时会在原始地址前拼镜像前缀，拼接后的 host 是镜像域名，不该被判成占位
        assertTrue(isUpdateIndexConfigured("https://ghfast.top/https://github.com/a/b/version.json"))
    }

    @Test
    fun `只看 host 后缀_路径里出现 invalid 不影响判定`() {
        assertTrue(isUpdateIndexConfigured("https://example.com/foo.invalid/version.json"))
    }
}
