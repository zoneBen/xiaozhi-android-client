package me.xiaozhi.androidclient.scheduling

/**
 * 一条 `PENDING` 执行记录在被消费时的重试规则。
 *
 * 抽成纯函数是因为这里的数字**全是拍出来的**，改一次就要重新算一遍总时长；
 * 摊开来才看得见「退避到最后一共等多久」这个真正重要的问题。
 *
 * ## 总等待时长（这是选 6 次的理由）
 *
 * `1500 + 3000 + 6000 + 12000 + 24000 ≈ 46 秒`。也就是「设备正忙」最多撑 46 秒，
 * 之后认输并在记录里写明原因。为什么不无限等：一条定时任务的价值在于「到点做了」，
 * 46 秒后还没能插进去，说明用户正在用，硬等下去只会在用户说完话的下一秒
 * 突然冒出一句他没要的话。**明确失败**比无限排队诚实。
 *
 * 上限 30 秒和 [MAX_ATTEMPTS] 同时存在不是重复：上限管的是**单次间隔**不被指数撑爆
 * （真要把次数调到 12，不加帽会退避到一整天），次数管的是总共试几次。
 */
object TaskRunPolicy {

    /** 一条任务总共试几次。见类注释里 46 秒那个账。 */
    const val MAX_ATTEMPTS = 6

    /** 首次重试间隔，与旧提醒的 `REMINDER_SEND_RETRY_MS` 同值，让两套引擎手感一致。 */
    const val RETRY_BASE_MS = 1_500L

    /** 单次间隔上限。 */
    const val RETRY_CAP_MS = 30_000L

    /**
     * 第 [attempts] 次失败之后该等多久。`attempts` 是**累加后**的次数（1 表示刚失败第一次）。
     *
     * 非正数按 1 处理：调用方算错次数不该变成「立刻无限重试」——那是最坏的一种错。
     */
    fun retryDelayMs(attempts: Int): Long {
        val exponent = (attempts.coerceAtLeast(1) - 1).coerceAtMost(MAX_BACKOFF_EXPONENT)
        return (RETRY_BASE_MS shl exponent).coerceAtMost(RETRY_CAP_MS)
    }

    /** 还有下一次机会吗。`attempts` 是已经试过的次数。 */
    fun hasAttemptsLeft(attempts: Int): Boolean = attempts < MAX_ATTEMPTS

    /**
     * 指数最多撑到 [RETRY_CAP_MS]，所以再往上移位只是徒增溢出风险。
     * `1500 << 5 = 48000 > 30000`，5 已经够到帽子了。
     */
    private const val MAX_BACKOFF_EXPONENT = 5
}

/**
 * [me.xiaozhi.androidclient.ui.MainViewModel.submitExternalMessage] 的返回约定。
 *
 * 为什么把这三个中文字符串提成常量：它们是一条**跨类的口头契约**——
 * 生产者是 MainViewModel，消费者是 [ScheduledTaskRunner]，而编译器对
 * 「两边写的中文不一样」毫无意见。提出来之后，改文案的人至少会先看到这里。
 */
object ExternalSend {
    const val SENT = "已发送"
    const val BUSY = "设备正忙，未发送"
    const val EMPTY = "文本为空，未发送"
}
