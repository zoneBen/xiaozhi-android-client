package me.xiaozhi.androidclient.scheduling

object SupervisionPolicy {
    fun canScheduleVerification(phase: SupervisionPhase?): Boolean =
        phase == SupervisionPhase.WAITING_FOR_ACK || phase == SupervisionPhase.VERIFYING

    fun phaseWhenDue(phase: SupervisionPhase?): SupervisionPhase? = when (phase) {
        SupervisionPhase.COUNTDOWN -> SupervisionPhase.WAITING_FOR_ACK
        SupervisionPhase.VERIFICATION_SCHEDULED -> SupervisionPhase.VERIFYING
        else -> null
    }

    fun migrateLegacyPhase(checkCount: Int, hasDueTime: Boolean): SupervisionPhase = when {
        checkCount == 0 && hasDueTime -> SupervisionPhase.COUNTDOWN
        checkCount == 0 -> SupervisionPhase.WAITING_FOR_ACK
        hasDueTime -> SupervisionPhase.VERIFICATION_SCHEDULED
        else -> SupervisionPhase.VERIFYING
    }

    fun nextRetrySeconds(checkCount: Int): Int =
        (checkCount.coerceAtLeast(1) * 30 + 60).coerceAtMost(1_800)

    /**
     * 相机被用户关掉时，监督核验该怎么处置。返回 null = 照常拍照核验；
     * 返回的字符串是发给模型的"无法确认"原因。
     *
     * **不拍照**是隐私问题，不是礼貌问题：监督核验是**设备自己**到点发起拍照的
     * （`runSupervisionVerification`），不查 `tools/list`。界面上写着"拍照已关闭"、
     * 却还在拍用户，那是这台设备上最不能出的一种谎。
     *
     * **不重试**：重试也是白醒——相机不会自己打开——而且会连着醒
     * `MAX_SUPERVISION_CHECKS` 次，每次只能报同一句话。调用方拿到非 null 后要
     * 直接判"无法确认"并把任务收尾，别走 `retryOrGiveUpSupervision` 那条路。
     */
    fun skipReasonWhenCameraDisabled(cameraEnabled: Boolean): String? =
        if (cameraEnabled) null else "相机已被用户关闭"
}
