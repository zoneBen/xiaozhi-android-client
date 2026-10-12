package me.xiaozhi.androidclient.scheduling

import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity

/**
 * 「关机这段时间里，它本来该响的那一次，怎么算？」
 *
 * 抽成纯函数是为了能钉住，而不是为了好看：补跑只在**开机、冷启动**这两条路径上跑，
 * 都是最不好手动复现的场景（要真关机、要真等）。这里把判定摊开，测试就能把
 * 「允许补跑 / 不允许补跑 / 没漏 / 已经记过」四种情形一次钉死。
 */
sealed interface CatchUpDecision {
    /** 没什么可做的：没漏，或者这条任务本来就不该响。 */
    data object Nothing : CatchUpDecision

    /** 漏了，且任务勾了「允许补跑」：补一次，并标上是补跑。 */
    data class CatchUp(val scheduledAtEpochMs: Long) : CatchUpDecision

    /** 漏了，但没勾补跑：**也记一条**，让用户在记录里看得见「它本来该跑」。 */
    data class SkipMissed(val scheduledAtEpochMs: Long) : CatchUpDecision
}

/**
 * 判定一条任务要不要补跑。
 *
 * @param alreadyRecorded 这一「次」在 `task_executions` 里是不是已经有行了。传参而不是
 *   在这里查库，是为了保持纯函数；[ScheduledTaskRecovery] 负责查了传进来。
 *
 * ## 两条边界，都是刻意的
 *
 * **只补一次，不逐次回放。** 判定只看 `nextFireAtEpochMs` 这**一个**时刻，所以哪怕关机一整夜
 * 漏了 12 次「每小时」，开机也只补 1 次。逐次回放会在开机瞬间刷出一串补跑——一次性的
 * 注入会连说 12 句话。用户要的是「这件事还没做」，不是「把这 12 次都补上」。
 *
 * **[alreadyRecorded] 是防重复的闸，不是优化。** 闹钟到点的 receiver 和这里的补跑都会
 * 往同一张表落行，而两者之间有一条真实的失败窗口：receiver 落完 `PENDING` 行、
 * 还没走到「把 nextFireAt 推到下一次」就被杀进程。此时库里 `nextFireAt` 仍在过去，
 * 下次开机补跑会认为「这次漏了」再落一行——结果是同一封邮件发两遍。
 * 用「这一刻是否已经有记录」当键，正好堵住这个窗口：同一次发生点，永远只产生一行。
 */
fun catchUpDecisionFor(
    task: ScheduledTaskEntity,
    nowEpochMs: Long,
    alreadyRecorded: Boolean,
): CatchUpDecision {
    // 关掉的任务不该响，也不该在记录里冒出来——用户是主动关的，不是「漏了」。
    if (!task.enabled) return CatchUpDecision.Nothing

    // 没有下一次发生点 = 还没排过（或已被用掉的一次性任务），无从谈「漏」。
    val scheduledAt = task.nextFireAtEpochMs ?: return CatchUpDecision.Nothing

    // 还没到点，没漏。
    if (scheduledAt > nowEpochMs) return CatchUpDecision.Nothing

    // 这一刻已经有行了：receiver 已经处理过，或上一轮补跑刚记完还没推进 nextFireAt。
    if (alreadyRecorded) return CatchUpDecision.Nothing

    return if (task.allowCatchUp) {
        CatchUpDecision.CatchUp(scheduledAt)
    } else {
        CatchUpDecision.SkipMissed(scheduledAt)
    }
}
