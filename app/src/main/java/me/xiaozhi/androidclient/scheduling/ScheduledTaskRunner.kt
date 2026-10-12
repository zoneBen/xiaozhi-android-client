package me.xiaozhi.androidclient.scheduling

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import me.xiaozhi.androidclient.data.ScheduledTaskRepository
import me.xiaozhi.androidclient.data.db.ScheduledTaskEntity
import me.xiaozhi.androidclient.data.db.TaskExecutionEntity
import me.xiaozhi.androidclient.email.PhotoCaptureException

/**
 * 把 `PENDING` 执行记录真正做掉。
 *
 * ## 它在整条链上的位置
 *
 * 闹钟 receiver 只负责**写库**（那条边界见 [TaskAlarmReceiver] 的文件头），
 * 这里负责**读库并执行**。所以这个类必须活在进程里、且由 ViewModel 持有——
 * 它是「写库」与「真正说话/拍照」之间唯一的桥。
 *
 * ## 为什么是定时轮询而不是 collect 一个 Flow
 *
 * 看着像偷懒，其实是被契约逼出来的，两条都不能省：
 *
 * 1. **重试到点时没有任何写入事件**。`observeReadyPending(now)` 的 `now` 是绑定的参数，
 *    Room 只会在**表被写过**时重新执行它；而一条退避中的记录到点，表没有任何变化。
 *    collect 一个 Flow 会在「退避到点」这个瞬间**永远收不到通知**。
 * 2. **receiver 到 VM 之间没有别的唤醒通路**（这正是那条边界的设计意图：
 *    receiver 绝不碰 MainViewModel）。闹钟落了行，谁来通知这把循环？只有它自己看。
 *
 * 所以是一个定时器。[DRAIN_INTERVAL_MS] 是按重试的**基本单位**选的：首次退避是
 * `RETRY_BASE_MS`，轮询间隔比它小才不会让重试又平白多等一个周期。
 * 代价是一条走 `(status, nextAttemptAtEpochMs)` 索引、表有 2000 行上限的查询，
 * 每秒一次可以忽略。
 *
 * ## 忙的时候怎么办
 *
 * 判定用现成的 [ReminderDeliveryPolicy]（**import 即用，不改它**），让新旧两套引擎
 * 对"什么时候能插话"的理解一致。区别在于动作：旧引擎走它自己那条
 * `pendingScheduledPrompts` 队列，这套走 `submitExternalMessage`，
 * 所以「让出对话」这一步由 ViewModel 通过 [yieldForTask] 交进来。
 *
 * `PHOTO_TO_EMAIL` **不看忙门控**：拍照发邮件不往对话里塞话，用户正在说话也不冲突，
 * 拿"设备忙"把它挡回去纯属误伤。
 */
class ScheduledTaskRunner(
    private val scope: CoroutineScope,
    private val repository: ScheduledTaskRepository,
    private val conversationState: () -> ReminderConversationState,
    /** 让出对话。收到的正是策略判出来的那个动作，让宽限期由判定方决定，不在这里重猜。 */
    private val yieldForTask: suspend (ReminderDeliveryAction) -> Unit,
    /** 在对话里留一行「【定时任务】xxx」——用户得知道它为什么突然开口。 */
    private val announceTask: (String) -> Unit,
    private val injectText: (String) -> String,
    private val captureAndSendPhoto: suspend (to: String?, subject: String?) -> Result<String>,
    private val note: (String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private var job: Job? = null

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                // 每一轮都整体兜住：一次执行里任何一处抛异常都不该让这把循环死掉——
                // 它一死，之后所有定时任务就永远不会执行，而且**没有任何迹象**。
                try {
                    drain()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    Log.e(TAG, "定时任务执行循环出错", error)
                }
                delay(DRAIN_INTERVAL_MS)
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private suspend fun drain() {
        val ready = repository.readyPending(now())
        for (execution in ready) {
            // 原子认领失败 = 别的消费者已经拿走了。这里只有一个消费者，但库是共享的
            // （界面上的"立即执行"按钮也走同一条路），所以仍以认领结果为准。
            if (!repository.claim(execution.id)) continue
            execute(execution)
        }
    }

    private suspend fun execute(execution: TaskExecutionEntity) {
        val task = repository.byId(execution.taskId)
        if (task == null) {
            finish(execution, TaskOutcome.SKIPPED_MISSED, "任务已被删除，这次不执行了")
            return
        }
        // 「任务被停用」这一档不能只看 `task.enabled`：一次性任务用掉之后会自己关掉
        // （receiver 那一步 retire），那是触发的收尾而不是用户改主意。判定抽在
        // [queuedExecutionSkipReason] 里，连理由一起写在那边。
        queuedExecutionSkipReason(task, execution)?.let { reason ->
            finish(execution, TaskOutcome.SKIPPED_MISSED, reason)
            return
        }

        when (TaskAction.fromName(task.action)) {
            TaskAction.PHOTO_TO_EMAIL -> runPhotoToEmail(execution, task)
            TaskAction.INJECT_TEXT -> runInjectText(execution, task)
            // action 来自更新的版本（或手工写坏的库）：**不重试**，退避多少次都还是不认识它。
            null -> finish(execution, TaskOutcome.SKIPPED_MISSED, "不认识的动作：${task.action}")
        }
    }

    // ---------- 给助手说句话 ----------

    private suspend fun runInjectText(execution: TaskExecutionEntity, task: ScheduledTaskEntity) {
        val text = task.text?.trim()
        if (text.isNullOrBlank()) {
            finish(execution, TaskOutcome.SKIPPED_MISSED, "任务没有要说的内容")
            return
        }

        // 忙就先让出对话，再发。让出的动作在 VM 里（关掉连续聆听 / 结束停滞的旧回合），
        // 这里只管"什么时候该让"——判定复用旧提醒那套策略，语义才一致。
        when (val action = ReminderDeliveryPolicy.decide(conversationState())) {
            ReminderDeliveryAction.SEND_NOW -> Unit

            ReminderDeliveryAction.PAUSE_LISTENING_AFTER_GRACE,
            ReminderDeliveryAction.INTERRUPT_CURRENT_TURN_AFTER_GRACE -> yieldForTask(action)

            // 还没连上：**没人在被打断**，而且这一档最常见的情形正是「闹钟把 App 从
            // 后台/被杀状态拽回来，进程刚起、正在连服务端」。所以它单独拿一份宽得多的
            // 预算——理由与实测数据见 TaskRunPolicy 的类注释。
            ReminderDeliveryAction.WAIT_FOR_CONNECTION -> {
                retryOrGiveUp(
                    execution = execution,
                    task = task,
                    reason = "还没连上服务端",
                    maxAttempts = TaskRunPolicy.CONNECT_WAIT_MAX_ATTEMPTS,
                )
                return
            }

            // 下面两种是「用户正在用」：等一会儿就有戏，但等久了会唐突——用户说完话的
            // 下一秒突然冒出一句他没要的话，比这次没做成更糟。沿用那份 46 秒的预算。
            ReminderDeliveryAction.WAIT_FOR_ASSISTANT -> {
                retryOrGiveUp(execution, task, "助手正在说话")
                return
            }

            ReminderDeliveryAction.WAIT_FOR_ACTIVE_DELIVERY -> {
                retryOrGiveUp(execution, task, "还有一条消息正在投递")
                return
            }
        }

        // 先留痕再说话。顺序反过来的话，用户会先听到一句莫名其妙的话，
        // 而那行"【定时任务】xxx"是事后才出现的解释，读起来像在给自己找补。
        announceTask(task.title)

        when (injectText(text)) {
            ExternalSend.SENT -> finish(execution, TaskOutcome.SENT, null)
            ExternalSend.BUSY -> retryOrGiveUp(execution, task, "设备正忙，没发出去")
            // 空文本在上面已经拦掉了，走到这里说明 VM 那边改了约定，如实记下来。
            else -> finish(execution, TaskOutcome.SKIPPED_MISSED, "发送没有成功，也没有说为什么")
        }
    }

    // ---------- 拍张照片发邮箱 ----------

    private suspend fun runPhotoToEmail(execution: TaskExecutionEntity, task: ScheduledTaskEntity) {
        val result = try {
            captureAndSendPhoto(task.emailTo, task.emailSubject)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            Result.failure(error)
        }

        result.fold(
            onSuccess = { message ->
                note(message)
                finish(execution, TaskOutcome.SENT, null)
            },
            onFailure = { error ->
                // 拍照失败**不重试**：摄像头被别的应用占着时，隔两秒再拍一次通常是同一个结果，
                // 而且用户会看到摄像头灯反复亮。发信失败同理（授权码错了重试一百次也还是错的）。
                // 与"设备正忙"那种"等一会儿就好了"的失败不是一类，不能共用一条重试路径。
                finish(execution, taskOutcomeOf(error), error.message ?: "拍照发邮件失败")
            },
        )
    }

    // ---------- 收尾 ----------

    /**
     * 退避重试，或者认输。
     *
     * [maxAttempts] 由调用方按**失败原因**选（见 [TaskRunPolicy]）：撞上用户在用只给 46 秒，
     * 还没连上则给到 4 分钟上下。默认取窄的那档——调用方没想清楚时不该白等四分钟。
     */
    private suspend fun retryOrGiveUp(
        execution: TaskExecutionEntity,
        task: ScheduledTaskEntity,
        reason: String,
        maxAttempts: Int = TaskRunPolicy.MAX_ATTEMPTS,
    ) {
        val attempts = execution.attempts + 1
        if (TaskRunPolicy.hasAttemptsLeft(attempts, maxAttempts)) {
            val delayMs = TaskRunPolicy.retryDelayMs(attempts)
            repository.requeue(
                executionId = execution.id,
                attempts = attempts,
                nextAttemptAtEpochMs = now() + delayMs,
                detail = "$reason，${delayMs / 1_000} 秒后重试",
            )
            Log.i(TAG, "定时任务「${task.title}」$reason，第 $attempts 次，${delayMs}ms 后重试")
            return
        }

        // 次数用光：**明确失败**，不静默丢弃也不无限排队（理由见 TaskRunPolicy 的文件头）。
        // 这条记录会留在执行记录页上，用户看得见"它到点了，但一直没插进去"。
        finish(
            execution,
            TaskOutcome.SKIPPED_BUSY,
            "$reason，试了 $maxAttempts 次都没成功",
        )
        note("定时任务「${task.title}」没能执行：$reason")
    }

    private suspend fun finish(
        execution: TaskExecutionEntity,
        outcome: TaskOutcome,
        detail: String?,
    ) {
        // 补跑成功单独标一个 outcome：记录页上一眼能分出"准点做的"和"补的"。
        val resolved = if (execution.isCatchUp && outcome == TaskOutcome.SENT) {
            TaskOutcome.CATCH_UP
        } else {
            outcome
        }
        repository.finish(
            executionId = execution.id,
            status = if (resolved == TaskOutcome.SENT || resolved == TaskOutcome.CATCH_UP) {
                TaskRunStatus.DONE.name
            } else {
                TaskRunStatus.FAILED.name
            },
            outcome = resolved.name,
            detail = detail,
        )
        Log.i(TAG, "定时任务执行结束 id=${execution.id} outcome=${resolved.name} detail=${detail.orEmpty()}")
    }

    private companion object {
        const val TAG = "XiaozhiTaskRun"

        /** 轮询间隔。见类注释：按重试的基本单位选的，必须小于 `TaskRunPolicy.RETRY_BASE_MS`。 */
        const val DRAIN_INTERVAL_MS = 1_000L

        /**
         * 失败原因归到哪一类。
         *
         * 靠**异常类型**而不是消息文本：拍照失败和发信失败在中文上都叫"失败"，
         * 但一个要去看摄像头、一个要去看 SMTP 授权码，混在一起用户永远修不好。
         */
        fun taskOutcomeOf(error: Throwable): TaskOutcome =
            if (error is PhotoCaptureException) TaskOutcome.FAILED_CAPTURE else TaskOutcome.FAILED_EMAIL
    }
}
