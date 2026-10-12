package me.xiaozhi.androidclient.backend

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 开屏展示时长的钳制。
 *
 * 这几条断言钉的是一个**真机上踩过的坑**：广告的展示时长不能直接用后台填的值，
 * 因为头屏能活多久不由广告决定 —— 主界面一画好头屏就该收。这台 PLQ110 上主界面
 * 0.4 秒就画好了，于是「有缓存的广告」在屏幕上活不过一帧，而曝光却照记不误。
 * 修法是给展示时长一个下限；同时给一个上限，免得后台一次填错（比如 15000）
 * 让这台 kiosk 每次冷启动都干等。
 */
class SplashAdVisibleMillisTest {

    private fun cache(durationMs: Int) = SplashAdCache(
        id = 1,
        title = "t",
        subtitle = "s",
        imageUrl = "http://example.com/a.jpg",
        imageSha256 = "a".repeat(64),
        linkUrl = "",
        durationMs = durationMs,
        revision = 1L,
        fetchedAtMillis = 0L,
    )

    @Test
    fun `后台填得太短也至少露两秒`() {
        assertEquals(2_000L, cache(500).visibleMillis)
        assertEquals(2_000L, cache(1_999).visibleMillis)
    }

    @Test
    fun `后台填得太长也只占五秒`() {
        // 15000 是后台表单允许的最大值 —— 原样用的话每次冷启动都要干等 15 秒。
        assertEquals(5_000L, cache(15_000).visibleMillis)
        assertEquals(5_000L, cache(5_001).visibleMillis)
    }

    @Test
    fun `区间内的值原样生效`() {
        assertEquals(2_000L, cache(2_000).visibleMillis)
        assertEquals(3_000L, cache(3_000).visibleMillis)
        assertEquals(5_000L, cache(5_000).visibleMillis)
    }

    @Test
    fun `填零或负数表示不放广告`() {
        // 后台那个字段允许填 0，这是它唯一说得通的含义：不是「展示 0 毫秒」，
        // 而是「这一轮别放」。否则会翻出一条谁也看不见的曝光。
        assertEquals(0L, cache(0).visibleMillis)
        assertEquals(0L, cache(-1).visibleMillis)
    }
}
