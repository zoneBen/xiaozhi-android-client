package me.xiaozhi.androidclient.scheduling

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisionPolicyTest {
    @Test
    fun `initial countdown becomes waiting for acknowledgement`() {
        assertEquals(SupervisionPhase.WAITING_FOR_ACK, SupervisionPolicy.phaseWhenDue(SupervisionPhase.COUNTDOWN))
    }

    @Test
    fun `scheduled verification becomes verifying`() {
        assertEquals(SupervisionPhase.VERIFYING, SupervisionPolicy.phaseWhenDue(SupervisionPhase.VERIFICATION_SCHEDULED))
    }

    @Test
    fun `verification can only be scheduled after acknowledgement or a completed attempt`() {
        assertTrue(SupervisionPolicy.canScheduleVerification(SupervisionPhase.WAITING_FOR_ACK))
        assertTrue(SupervisionPolicy.canScheduleVerification(SupervisionPhase.VERIFYING))
        assertFalse(SupervisionPolicy.canScheduleVerification(SupervisionPhase.COUNTDOWN))
        assertFalse(SupervisionPolicy.canScheduleVerification(SupervisionPhase.VERIFICATION_SCHEDULED))
    }

    @Test
    fun `retry backoff starts at ninety seconds and is capped`() {
        assertEquals(90, SupervisionPolicy.nextRetrySeconds(1))
        assertEquals(120, SupervisionPolicy.nextRetrySeconds(2))
        assertEquals(1_800, SupervisionPolicy.nextRetrySeconds(100))
    }

    @Test
    fun `legacy waiting verification is recovered as verifying`() {
        assertEquals(SupervisionPhase.VERIFYING, SupervisionPolicy.migrateLegacyPhase(checkCount = 2, hasDueTime = false))
    }

    @Test
    fun `an enabled camera verifies as usual`() {
        assertNull(SupervisionPolicy.skipReasonWhenCameraDisabled(cameraEnabled = true))
    }

    @Test
    fun `a disabled camera skips verification with a reason the model can repeat`() {
        // 监督核验是**设备自己**到点发起拍照的，不查 tools/list——所以"关掉相机"
        // 必须在执行期真的短路，否则界面上写着"拍照已关闭"却还在拍用户。
        val reason = SupervisionPolicy.skipReasonWhenCameraDisabled(cameraEnabled = false)
        assertEquals("相机已被用户关闭", reason)
    }
}
