package me.xiaozhi.androidclient.backend

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SplashAdStore] 的落盘/读盘单测。
 *
 * 这里测的是**开屏那条链路唯一会真正出错的地方**：头屏进程读缓存时它必须
 * 「要么给出一份自洽的缓存，要么干脆说没有」，绝不能抛异常、也绝不能给出一张
 * 指向不存在文件的 meta —— 后者会让 `BitmapFactory.decodeFile` 拿到空路径，
 * 而这台设备上开屏是没有 try 的第二道防线的。
 */
class SplashAdStoreTest {

    private fun tempDir(): File =
        File(System.getProperty("java.io.tmpdir"), "splashad-${System.nanoTime()}")
            .apply { mkdirs() }

    private fun store(dir: File) = SplashAdStore(dir)

    private fun cache(
        id: Int = 3,
        sha: String = SHA_A,
        fetchedAt: Long = NOW,
        title: String = "国庆活动火热进行中",
    ): SplashAdCache = SplashAdCache(
        id = id,
        title = title,
        subtitle = "扫码领优惠券 ›",
        imageUrl = "http://example.com/a.jpg",
        imageSha256 = sha,
        linkUrl = "https://example.com/act",
        durationMs = 3000,
        revision = 7L,
        fetchedAtMillis = fetchedAt,
    )

    @Test
    fun `写进去再读出来拿到同一份缓存`() {
        val dir = tempDir()
        val store = store(dir)
        assertTrue(store.write(cache(), byteArrayOf(1, 2, 3)))

        val read = store.read(MAX_AGE, nowMillis = NOW)
        assertNotNull(read)
        assertEquals(3, read?.id)
        assertEquals("国庆活动火热进行中", read?.title)
        assertEquals(SHA_A, read?.imageSha256)
        assertEquals(7L, read?.revision)
    }

    @Test
    fun `图片文件名带 sha 前缀_所以 meta 指向的文件一定存在`() {
        val dir = tempDir()
        val store = store(dir)
        store.write(cache(), byteArrayOf(9))

        val image = store.imageFile(cache())
        assertTrue("图片没落盘：${image.absolutePath}", image.isFile)
        assertEquals("ad_${SHA_A.take(16)}.img", image.name)
    }

    @Test
    fun `没有缓存时读出来是 null`() {
        assertNull(store(tempDir()).read(MAX_AGE, nowMillis = NOW))
    }

    @Test
    fun `超龄缓存按没有处理`() {
        val dir = tempDir()
        val store = store(dir)
        store.write(cache(fetchedAt = NOW - MAX_AGE - 1), byteArrayOf(1))

        // 超龄 = 放弃，而不是"降级用旧图"：服务器长期失联时不能把一张早就过期的
        // 活动图永远钉在开屏上。
        assertNull(store.read(MAX_AGE, nowMillis = NOW))
        // 但只差一点点就还算数（边界不能被 off-by-one 吃掉）
        store.write(cache(fetchedAt = NOW - MAX_AGE + 1), byteArrayOf(1))
        assertNotNull(store.read(MAX_AGE, nowMillis = NOW))
    }

    @Test
    fun `图片文件被删掉时读出来是 null_而不是一份指向空文件的缓存`() {
        val dir = tempDir()
        val store = store(dir)
        store.write(cache(), byteArrayOf(1, 2, 3))
        store.imageFile(cache()).delete()

        assertNull(store.read(MAX_AGE, nowMillis = NOW))
    }

    @Test
    fun `meta 被写坏时静默返回 null_不抛异常`() {
        val dir = tempDir()
        val store = store(dir)
        store.write(cache(), byteArrayOf(1))
        File(dir, "meta.json").writeText("{ 这不是 JSON")

        assertNull(store.read(MAX_AGE, nowMillis = NOW))
    }

    @Test
    fun `空 sha 的缓存按没有处理`() {
        val dir = tempDir()
        val store = store(dir)
        store.write(cache(sha = ""), byteArrayOf(1))

        // 没有 sha 就拼不出图片文件名，meta 也就失去了"指向哪张图"的依据。
        assertNull(store.read(MAX_AGE, nowMillis = NOW))
    }

    @Test
    fun `换图之后旧图被清掉_目录里只剩一张`() {
        val dir = tempDir()
        val store = store(dir)
        store.write(cache(sha = SHA_A), byteArrayOf(1))
        store.write(cache(sha = SHA_B), byteArrayOf(2))

        val images = dir.listFiles().orEmpty().filter { it.name.startsWith("ad_") }
        assertEquals("旧图没清理：${images.map { it.name }}", 1, images.size)
        assertEquals("ad_${SHA_B.take(16)}.img", images.single().name)
    }

    @Test
    fun `落盘不留临时文件`() {
        val dir = tempDir()
        store(dir).write(cache(), byteArrayOf(1, 2, 3))

        assertTrue(
            "留下了临时文件：${dir.listFiles().orEmpty().map { it.name }}",
            dir.listFiles().orEmpty().none { it.name.endsWith(".tmp") },
        )
    }

    @Test
    fun `只更新 meta 时图片原样留着`() {
        val dir = tempDir()
        val store = store(dir)
        store.write(cache(title = "旧文案"), byteArrayOf(1, 2, 3))
        val image = store.imageFile(cache())

        // 「文案改了、图没换」走这条路：revision 变了但 sha 没变，不该为此重下几 MB。
        assertTrue(store.updateMeta(cache(title = "新文案", fetchedAt = NOW + 1000)))

        assertTrue(image.isFile)
        assertEquals("新文案", store.read(MAX_AGE, nowMillis = NOW + 2000)?.title)
    }

    @Test
    fun `清空之后读出来是 null`() {
        val dir = tempDir()
        val store = store(dir)
        store.write(cache(), byteArrayOf(1))
        store.clear()

        assertNull(store.read(MAX_AGE, nowMillis = NOW))
    }

    // ---- 曝光/点击的中转 ------------------------------------------------------

    @Test
    fun `攒下的曝光被一次性取走_第二次取是空的`() {
        val dir = tempDir()
        val store = store(dir)
        store.appendPendingReport(3, SplashAdStore.ACTION_SHOW)
        store.appendPendingReport(3, SplashAdStore.ACTION_CLICK)

        val drained = store.drainPendingReports()
        assertEquals(
            listOf(
                PendingSplashReport(3, SplashAdStore.ACTION_SHOW),
                PendingSplashReport(3, SplashAdStore.ACTION_CLICK),
            ),
            drained,
        )
        // 取走就是取走：主进程下一轮不该把同一次曝光再报一遍。
        assertTrue(store.drainPendingReports().isEmpty())
    }

    @Test
    fun `没有待上报时取走返回空_不抛异常`() {
        assertTrue(store(tempDir()).drainPendingReports().isEmpty())
    }

    @Test
    fun `非法的待上报行被丢掉_不拖垮整批`() {
        val dir = tempDir()
        val store = store(dir)
        store.appendPendingReport(3, SplashAdStore.ACTION_SHOW)
        // 追加几行脏数据：真实场景是写到一半被杀、或文件被别的工具动过。
        File(dir, "pending_reports.txt").appendText(
            "click abc\n" +
                "unknown 5\n" +
                "show 3\n" +
                "\n" +
                "show\n",
        )

        val drained = store.drainPendingReports()
        assertEquals(
            listOf(
                PendingSplashReport(3, SplashAdStore.ACTION_SHOW),
                PendingSplashReport(3, SplashAdStore.ACTION_SHOW),
            ),
            drained,
        )
    }

    @Test
    fun `id 非正的上报从一开始就不记`() {
        val dir = tempDir()
        val store = store(dir)
        // 没换到素材的服务端没有任何 id 可记，写进去只会污染计数。
        store.appendPendingReport(0, SplashAdStore.ACTION_SHOW)

        assertTrue(store.drainPendingReports().isEmpty())
    }

    @Test
    fun `追加写不覆盖已有的待上报`() {
        val dir = tempDir()
        val store = store(dir)
        store.appendPendingReport(1, SplashAdStore.ACTION_SHOW)
        store.appendPendingReport(2, SplashAdStore.ACTION_SHOW)

        assertEquals(2, store.drainPendingReports().size)
        assertFalse(File(dir, "pending_reports.txt").exists())
    }

    private companion object {
        const val NOW = 1_760_246_400_000L
        const val MAX_AGE = SplashAdStore.MAX_AGE_MILLIS
        val SHA_A = "a".repeat(64)
        val SHA_B = "b".repeat(64)
    }
}
