package me.xiaozhi.androidclient.data

import me.xiaozhi.androidclient.camera.CameraFacing
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 朝向列表 ↔ 缓存里那串 CSV。
 *
 * 存的是逗号分隔的 `CameraFacing.name`。这个往返必须稳：解析不出来时设置页
 * 「用哪个摄像头」那一行会凭空消失，而没有任何报错。
 */
class HardwareProbeCsvTest {

    @Test
    fun `列表转 CSV 用逗号分隔枚举名`() {
        assertEquals("BACK,FRONT", listOf(CameraFacing.BACK, CameraFacing.FRONT).toProbeCsv())
        assertEquals("", emptyList<CameraFacing>().toProbeCsv())
    }

    @Test
    fun `CSV 往返回得到原列表且保持顺序`() {
        val original = listOf(CameraFacing.FRONT, CameraFacing.BACK)
        assertEquals(original, original.toProbeCsv().toProbeFacings())
    }

    @Test
    fun `空串解析成空列表而不是一个空项`() {
        assertEquals(emptyList<CameraFacing>(), "".toProbeFacings())
        assertEquals(emptyList<CameraFacing>(), "  ".toProbeFacings())
    }

    /** 枚举将来删项、或者有人手工改过库，都不该让设置页打不开。 */
    @Test
    fun `认不出的名字被丢掉，认得出的留下`() {
        assertEquals(listOf(CameraFacing.BACK), "BACK,SIDEWAYS".toProbeFacings())
        assertEquals(emptyList<CameraFacing>(), "SIDEWAYS".toProbeFacings())
    }

    @Test
    fun `多余空白和空项都被容忍`() {
        assertEquals(listOf(CameraFacing.BACK, CameraFacing.FRONT), "BACK, ,FRONT".toProbeFacings())
        assertEquals(listOf(CameraFacing.BACK), " BACK ".toProbeFacings())
    }
}
