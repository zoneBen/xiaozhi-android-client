package me.xiaozhi.androidclient.util

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「多久以前」的文案。
 *
 * 它出现在设置页的硬件自检那一行，写错了不会有人报错，只会看到一句奇怪的话——
 * 而且恰好在最需要它准确的地方（「上次自检：」是用来判断结论新不新鲜的）。
 */
class RelativeTimeLabelTest {

    @Test
    fun `一分钟以内说刚刚`() {
        assertEquals("刚刚", relativeTimeLabel(0L))
        assertEquals("刚刚", relativeTimeLabel(59_999L))
    }

    @Test
    fun `满一分钟开始报分钟`() {
        assertEquals("1 分钟前", relativeTimeLabel(60_000L))
        assertEquals("59 分钟前", relativeTimeLabel(59 * 60_000L + 59_999L))
    }

    @Test
    fun `满一小时开始报小时`() {
        assertEquals("1 小时前", relativeTimeLabel(60 * 60_000L))
        assertEquals("23 小时前", relativeTimeLabel(24 * 60 * 60_000L - 1))
    }

    @Test
    fun `满一天开始报天`() {
        assertEquals("1 天前", relativeTimeLabel(24 * 60 * 60_000L))
        assertEquals("3 天前", relativeTimeLabel(3 * 24 * 60 * 60_000L + 5_000L))
    }

    /**
     * 系统时间被 NTP 往回调时，`now - probedAt` 会是负数。
     * 「-3 小时前」是句没意义的话，按「刚刚」处理。
     */
    @Test
    fun `负时长按刚刚处理`() {
        assertEquals("刚刚", relativeTimeLabel(-1L))
        assertEquals("刚刚", relativeTimeLabel(-3 * 60 * 60_000L))
    }
}
