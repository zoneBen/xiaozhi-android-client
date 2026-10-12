package me.xiaozhi.androidclient.scheduling

/**
 * 一条 `PENDING` 执行记录在被消费时的重试规则。
 *
 * 抽成纯函数是因为这里的数字**全是拍出来的**，改一次就要重新算一遍总时长；
 * 摊开来才看得见「退避到最后一共等多久」这个真正重要的问题。
 *
 * ## 两档预算，因为「等下去」在两种情形下代价完全不同
 *
 * **一档：设备正忙。** `1500 + 3000 + 6000 + 12000 + 24000 ≈ 46 秒`。也就是撞上
 * 用户正在说话 / 助手正在播报时最多撑 46 秒，之后认输并在记录里写明原因。
 * 为什么不无限等：一条定时任务的价值在于「到点做了」，46 秒后还没能插进去，
 * 说明用户正在用，硬等下去只会在用户说完话的下一秒突然冒出一句他没要的话。
 * **明确失败**比无限排队诚实。
 *
 * **二档：还没连上。** 这时**没有人在被打断**，等下去零代价——而它偏偏是最需要
 * 耐心的一档：闹钟 `startActivity` 把 App 从后台/被杀状态拽回来之后，进程起来、
 * 连上服务端、音频通路重建，这几步合起来可能就是一两分钟。真机实测中，
 * 一条后台触发的任务在 46 秒预算里 6 次全部撞在「还没连上」上，然后被记为失败——
 * 而它在半分钟后本来就会成功。所以这一档单独给一份宽得多的预算。
 *
 * 两档共用同一套退避曲线与 [RETRY_CAP_MS]，只有次数不同。
 *
 * 上限 30 秒和次数同时存在不是重复：上限管的是**单次间隔**不被指数撑爆
 * （真要把次数调到 12，不加帽会退避到一整天），次数管的是总共试几次。
 */
object TaskRunPolicy {

    /** 「设备正忙」档试几次。见类注释里 46 秒那个账。 */
    const val MAX_ATTEMPTS = 6

    /**
     * 「还没连上」档试几次。
     *
     * `46500 + 30000 × 6 ≈ 3 分 47 秒`：退避到帽子（30 秒）之后靠次数往后铺，
     * 而不是继续抬高单次间隔——等重连这件事，隔半分钟问一次已经够了。
     *
     * 上限设在 4 分钟上下而不是无限：真配上一条「每 5 分钟」的任务，无限等会让
     * 补跑和下一次到点叠在一起排队。4 分钟仍短于最小合理周期，不会自相追尾。
     */
    const val CONNECT_WAIT_MAX_ATTEMPTS = 12

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

    /**
     * 还有下一次机会吗。`attempts` 是已经试过的次数，[maxAttempts] 是这一档的预算
     * （[MAX_ATTEMPTS] 或 [CONNECT_WAIT_MAX_ATTEMPTS]，由调用方按失败原因选）。
     */
    fun hasAttemptsLeft(attempts: Int, maxAttempts: Int = MAX_ATTEMPTS): Boolean =
        attempts < maxAttempts

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
