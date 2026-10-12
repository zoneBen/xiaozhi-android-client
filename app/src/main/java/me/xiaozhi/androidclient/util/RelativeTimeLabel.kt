package me.xiaozhi.androidclient.util

/**
 * 把「多久以前」说成人话。
 *
 * 用在中性的**时间跨度**上（比如「上次自检：3 小时前」），不是日期格式化——
 * 界面上到处都是 `SimpleDateFormat("HH:mm:ss")`，那是给人看具体时刻的；
 * 这里要表达的是新鲜度。
 *
 * 刻意做成纯函数：它有无数的边界（刚好 60 秒、刚好 1 小时、负数），
 * 而这段文字出现在设置页上，写错了没人会报错，只会看到一句奇怪的话。
 *
 * 负数（系统时间被往回调）按「刚刚」处理，不显示「-3 小时前」这种没意义的东西。
 */
fun relativeTimeLabel(ageMs: Long): String {
    if (ageMs < 0L) return "刚刚"
    return when {
        ageMs < MINUTE_MS -> "刚刚"
        ageMs < HOUR_MS -> "${ageMs / MINUTE_MS} 分钟前"
        ageMs < DAY_MS -> "${ageMs / HOUR_MS} 小时前"
        else -> "${ageMs / DAY_MS} 天前"
    }
}

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60 * MINUTE_MS
private const val DAY_MS = 24 * HOUR_MS
