package me.xiaozhi.androidclient.backend

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [BackendSigner] 的单测。
 *
 * ## 为什么这里必须用固定向量
 *
 * 签名的两端（客户端 `BackendSigner.kt` / 服务端 `php/common.php` 的 `zx_sign()`）算的是
 * 同一串字符。这条链路**只在单测里能钉死**：真出问题时客户端拿到的是一个
 * `code=401 签名校验失败`，它既不告诉你是哪一段不一样，也没法在设备上逐步调试。
 * 下面那两个向量是按下面这段 PHP 口径手算出来的，任何一个环节走偏（换行、字段顺序、
 * 大小写、hex 编码）都会立刻红：
 *
 * ```php
 * $canonical = implode("\n", [strtoupper($method), $path, $appKey, $timestamp, hash('sha256', $rawBody)]);
 * hash_hmac('sha256', $canonical, $appSecret);
 * ```
 *
 * ## 为什么 `$path` 带着 `/apps/xiaozhi_android_client` 前缀
 *
 * 服务端取的是 `parse_url($_SERVER['REQUEST_URI'], PHP_URL_PATH)`，线上就挂在这层子目录下。
 * 也就是说客户端签的必须是 `/apps/xiaozhi_android_client/v1/heartbeat.php`，
 * 而不是 `/v1/heartbeat.php`。这个测试用的就是前者 —— 如果有人"顺手规范化"成后者，
 * 线上会全线 401，而本地怎么点都看不出来。
 */
class BackendSignerTest {

    @Test
    fun `POST 带请求体_与服务端固定向量一致`() {
        val actual = BackendSigner.sign(
            method = "POST",
            path = HEARTBEAT_PATH,
            appKey = APP_KEY,
            timestampSeconds = TIMESTAMP,
            rawBody = HEARTBEAT_BODY.toByteArray(Charsets.UTF_8),
            appSecret = APP_SECRET,
        )
        assertEquals("1da7229035c4d61cb866f03bbe2dd864c2ce1f4da413d0e2d6e66155c521144c", actual)
    }

    @Test
    fun `GET 无请求体_与服务端固定向量一致`() {
        // 空请求体也要参与签名（sha256("") 是个真实的值，不是空串）——
        // 这是 GET 那条路上唯一容易漏、且漏了必然全线 401 的地方。
        val actual = BackendSigner.sign(
            method = "GET",
            path = UPGRADE_PATH,
            appKey = APP_KEY,
            timestampSeconds = TIMESTAMP,
            rawBody = ByteArray(0),
            appSecret = APP_SECRET,
        )
        assertEquals("cd87ace0b28e1119c0ff5a60533789ad63ac5bd7837fe3dd2b3622c3755b973d", actual)
    }

    @Test
    fun `方法大小写不影响签名`() {
        val upper = sign(method = "POST", body = HEARTBEAT_BODY)
        val lower = sign(method = "post", body = HEARTBEAT_BODY)
        val mixed = sign(method = "Post", body = HEARTBEAT_BODY)

        assertEquals(upper, lower)
        assertEquals(upper, mixed)
    }

    @Test
    fun `请求体差一个字节签名就不同`() {
        // 这条防的是"把 body 和签名输入当成两份东西"：只要有一处编码/BOM/换行对不上，
        // 两边算出来的就不是同一个 hash，而现象只有线上的 401。
        assertEquals(
            BackendSigner.sha256Hex(""),
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        )
        assertEquals(
            BackendSigner.sha256Hex("abc"),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        )
        // String 重载和 ByteArray 重载必须是同一件事（接口用前者、签名用后者）
        assertEquals(
            BackendSigner.sha256Hex(HEARTBEAT_BODY),
            BackendSigner.sha256Hex(HEARTBEAT_BODY.toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `签名结果是小写十六进制`() {
        val sign = sign(method = "POST", body = HEARTBEAT_BODY)
        assertEquals(sign, sign.lowercase(Locale.US))
        assertEquals(64, sign.length)
    }

    @Test
    fun `签名不受系统语言影响`() {
        // 这是一道**防守性**的闸门，不是在复现已知故障：`%02x` 目前在 JDK / Android 上
        // 都不做本地化（本地化的是 `%d` 一类），所以今天把 Locale 去掉也仍然会绿。
        // 但签名是要和 PHP 逐字节比对的，任何一处格式化被换成会本地化的转换都会静默
        // 全线 401，所以顺手钉一条：换默认语言不影响结果。
        val original = Locale.getDefault()
        val expected = sign(method = "POST", body = HEARTBEAT_BODY)
        try {
            Locale.setDefault(Locale.forLanguageTag("ar-SA"))
            assertEquals(expected, sign(method = "POST", body = HEARTBEAT_BODY))
        } finally {
            Locale.setDefault(original)
        }
    }

    private fun sign(method: String, body: String): String = BackendSigner.sign(
        method = method,
        path = HEARTBEAT_PATH,
        appKey = APP_KEY,
        timestampSeconds = TIMESTAMP,
        rawBody = body.toByteArray(Charsets.UTF_8),
        appSecret = APP_SECRET,
    )

    private companion object {
        const val APP_KEY = "xiaozhi-android-client"
        const val APP_SECRET = "test-secret-do-not-use"
        const val TIMESTAMP = 1_760_246_400L
        const val HEARTBEAT_PATH = "/apps/xiaozhi_android_client/v1/heartbeat.php"
        const val UPGRADE_PATH = "/apps/xiaozhi_android_client/v1/upgrade.php"
        const val HEARTBEAT_BODY = """{"deviceUid":"a2:1f:cc:00:11:22","source":"app"}"""
    }
}
