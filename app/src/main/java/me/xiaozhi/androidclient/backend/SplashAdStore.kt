package me.xiaozhi.androidclient.backend

import android.content.Context
import java.io.File
import java.io.FileOutputStream
import org.json.JSONObject

/** 一条待上报的曝光/点击。 */
internal data class PendingSplashReport(val splashId: Int, val action: String)

/**
 * 开屏素材的本地缓存：`filesDir/splash_ad/`。
 *
 * ## 这个类必须能被 `:splash` 进程用
 *
 * `SplashActivity` 跑在独立进程 `:splash` 里，刻意只依赖平台类（为了头屏起得早）。
 * 所以这个类**不许**引入 OkHttp / AndroidX / Compose —— 只用 `java.io` 和 `org.json`。
 * 头屏读缓存走的就是它。
 *
 * ## 为什么图片文件名里带 sha
 *
 * 写缓存要落两个文件（图片 + meta），两次写之间被打断就会留下「新图配旧文案」。
 * 把 sha 编进图片文件名之后，meta 指向哪张图是**自洽**的：要么指到一个确实存在的
 * 文件，要么整份缓存作废 —— 不存在中间态。
 *
 * ## 为什么读的时候不校验 sha
 *
 * 校验要整份读完几 MB 的图，而这段代码跑在开屏进程的主线程路径上（头屏时间就是它的代价）。
 * 写盘本来就走临时文件 + 原子重命名，磁盘上不会出现半张图；真损坏了，
 * `BitmapFactory.decodeFile` 返回 null，调用方照样回退到原动画。
 *
 * ## 为什么目录是构造参数
 *
 * 单测里没法造 `Context`（这个模块的 JVM 单测没有 Robolectric，`Application()` 拿到的是
 * 空壳，`filesDir` 为 null）。而这里最值得测的偏偏是「"过期判定、文件缺失、原子写、
 * 攒下的上报能不能被取走"这几条分支」—— 与其为了可测性去引一整个 Robolectric，
 * 不如照 `camera/PhotoStore` 的做法：目录由调用方给，生产路径仍是 `filesDir/splash_ad/`。
 */
internal class SplashAdStore(private val directory: File) {

    /** 生产路径：`filesDir/splash_ad/`。 */
    constructor(context: Context) : this(File(context.applicationContext.filesDir, DIRECTORY_NAME))

    private val metaFile = File(directory, META_FILE_NAME)
    private val pendingFile = File(directory, PENDING_FILE_NAME)

    /**
     * 有没有缓存。只探一下 meta.json 在不在，**不做任何解析**。
     *
     * 给头屏用：绝大多数机器（以及后台把素材停用之后的机器）这里就是 false，
     * 于是连后台线程都不用开，直接进主界面 —— 有了广告功能之后，这条路的开销
     * 必须还是零。真正的过期判定、图片存在性都在 [read] 里，别拿这个方法当它用。
     */
    fun hasCache(): Boolean = metaFile.isFile

    /**
     * 读缓存。[maxAgeMillis] 之外的缓存直接作废。
     *
     * 缓存优先于联网 —— 这正是「有就展示、没有就直接进」：服务器挂了的时候，
     * 手上有图就照常展示，而不是因为拉不到新素材就干脆不放了。超龄上限是唯一的例外，
     * 免得服务器长期失联后把一张早就过期的活动图永远钉在开屏上。
     */
    fun read(maxAgeMillis: Long, nowMillis: Long = System.currentTimeMillis()): SplashAdCache? =
        runCatching {
            if (!metaFile.exists()) return@runCatching null

            val cache = SplashAdCache.fromJson(JSONObject(metaFile.readText()), fallbackFetchedAt = 0L)
            if (cache.imageSha256.isBlank()) return@runCatching null
            if (cache.fetchedAtMillis <= 0L) return@runCatching null
            if (nowMillis - cache.fetchedAtMillis > maxAgeMillis) return@runCatching null

            val image = imageFile(cache)
            if (!image.isFile || image.length() <= 0L) return@runCatching null

            cache
        }.getOrNull()

    /** 缓存里那张图。名字由 sha 决定，所以 [cache] 和文件是一一对应的。 */
    fun imageFile(cache: SplashAdCache): File = File(directory, imageFileName(cache.imageSha256))

    /**
     * 落盘。顺序是「先图后 meta」：反过来的话，meta 会先指向一张还不存在的图。
     *
     * @return 是否成功。失败只代表这次没缓存上，不影响任何别的东西。
     */
    fun write(cache: SplashAdCache, imageBytes: ByteArray): Boolean = runCatching {
        directory.mkdirs()
        writeAtomically(File(directory, imageFileName(cache.imageSha256)), imageBytes)
        writeAtomically(metaFile, cache.toJson().toByteArray(Charsets.UTF_8))
        // 清掉上一张：文件名带 sha，不清理的话每换一次图就多留一份几 MB 的旧图。
        pruneOldImages(keep = imageFileName(cache.imageSha256))
        true
    }.getOrDefault(false)

    /**
     * 只更新 meta，不动图片。
     *
     * 给「图没换、文案/时长变了」用：这时图片文件本来就在、名字也没变，
     * 单独重写 meta 是安全的 —— 那份「先图后 meta」的原子性顾虑只存在于**换图**的时候。
     */
    fun updateMeta(cache: SplashAdCache): Boolean = runCatching {
        directory.mkdirs()
        writeAtomically(metaFile, cache.toJson().toByteArray(Charsets.UTF_8))
        true
    }.getOrDefault(false)

    /** 清空缓存。运营在后台把开屏总开关关掉时走这里。 */
    fun clear() {
        runCatching {
            directory.listFiles()?.forEach { it.delete() }
        }
    }

    // ---- 曝光/点击上报的中转 ------------------------------------------------

    /**
     * 记一条待上报。**由 `:splash` 进程调用**。
     *
     * 开屏进程不联网（那会把 OkHttp 拉进那个进程），所以它只把这件事写进一个文件，
     * 由主进程下次起来时取走。顺带解决另一个问题：用户看一眼就杀进程的话，
     * 记在内存里的上报也会一起没掉。
     */
    fun appendPendingReport(splashId: Int, action: String) {
        if (splashId <= 0) return
        runCatching {
            directory.mkdirs()
            // 追加写、写完就关，不持有 fd：下面 drain 是靠重命名搬走文件的，
            // 长期开着 fd 的话会一直往已经搬走的旧 inode 上写。
            FileOutputStream(pendingFile, true).use { stream ->
                stream.write("$action $splashId\n".toByteArray(Charsets.UTF_8))
            }
        }
    }

    /**
     * 取走所有待上报。**由主进程调用**。
     *
     * 先重命名再读：直接「读 + 删」的话，删掉之后开屏进程新建的那个文件不会丢，
     * 但两边同时操作会有丢行的窗口。重命名是原子的，最坏情况只是这一轮少收一条，
     * 下一轮补上 —— 而漏掉一次曝光统计，本来就不是需要付出复杂度去避免的事。
     */
    fun drainPendingReports(): List<PendingSplashReport> {
        val staging = File(directory, "$PENDING_FILE_NAME.draining")
        return runCatching {
            if (!pendingFile.exists()) return@runCatching emptyList()
            if (!pendingFile.renameTo(staging)) return@runCatching emptyList()

            val reports = staging.readLines()
                .mapNotNull(::parsePendingLine)
            staging.delete()
            reports
        }.getOrDefault(emptyList())
    }

    private fun parsePendingLine(line: String): PendingSplashReport? {
        val parts = line.trim().split(' ')
        if (parts.size != 2) return null
        val action = parts[0]
        val splashId = parts[1].toIntOrNull() ?: return null
        if (action != ACTION_SHOW && action != ACTION_CLICK) return null
        return PendingSplashReport(splashId, action)
    }

    private fun imageFileName(sha256: String): String = "ad_${sha256.lowercase().take(SHA_PREFIX_LENGTH)}.img"

    private fun pruneOldImages(keep: String) {
        directory.listFiles()?.forEach { file ->
            if (file.name.startsWith(IMAGE_PREFIX) && file.name != keep) {
                file.delete()
            }
        }
    }

    /**
     * 临时文件 + 原子重命名。中途断线/被杀时磁盘上要么是旧内容、要么是新内容，
     * 不会留下一个半截的图片让下次冷启动去解码。
     */
    private fun writeAtomically(target: File, bytes: ByteArray) {
        val temporary = File(target.parentFile, "${target.name}.tmp")
        FileOutputStream(temporary).use { stream ->
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        }
        if (!temporary.renameTo(target)) {
            // 少数文件系统上目标已存在时 rename 会失败，退化成「先删再改名」。
            target.delete()
            if (!temporary.renameTo(target)) {
                temporary.delete()
                throw java.io.IOException("写入失败: ${target.name}")
            }
        }
    }

    companion object {
        const val ACTION_SHOW = "show"
        const val ACTION_CLICK = "click"

        private const val DIRECTORY_NAME = "splash_ad"
        private const val META_FILE_NAME = "meta.json"
        private const val PENDING_FILE_NAME = "pending_reports.txt"
        private const val IMAGE_PREFIX = "ad_"
        private const val SHA_PREFIX_LENGTH = 16

        /** 服务端长期失联时，缓存最多用这么久就放弃。 */
        const val MAX_AGE_MILLIS: Long = 7L * 24 * 60 * 60 * 1000
    }
}
