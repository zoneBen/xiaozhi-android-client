package me.xiaozhi.androidclient.backend

import android.content.Context
import java.io.IOException

/** 一轮开屏素材刷新的结果。 */
internal sealed interface SplashRefreshOutcome {
    /** 换上了新图。 */
    data object Updated : SplashRefreshOutcome

    /** 服务端说没变（或本地已是同 revision 同一张图），缓存不动。 */
    data object NotModified : SplashRefreshOutcome

    /** 服务端现在没有生效的素材（含总开关关闭），本地缓存已清掉。 */
    data object Cleared : SplashRefreshOutcome

    data object Skipped : SplashRefreshOutcome

    /** 这一轮没拿到东西，**本地缓存原样保留** —— 这正是「服务器挂了照样展示」。 */
    data class Failed(val error: Throwable) : SplashRefreshOutcome
}

/**
 * 拉清单 → 判断要不要换图 → 下图 → 落盘。
 *
 * 只在**主进程**跑（`SplashActivity` 所在的 `:splash` 进程只读缓存，不联网）。
 * 由 `MainViewModel` 在冷启动时调一次，也在心跳成功后顺手调一次。
 *
 * 任何一个环节失败都不会清掉已有缓存：**「有就展示」的语义是缓存说了算，
 * 不是服务器说了算**。唯一的例外是服务端明确说「现在没有素材」（`enabled=false`
 * 或清单为空），那是运营在后台把素材下线了，这时才清。
 */
internal class SplashAdRefresher(
    context: Context,
    private val service: SplashAdService,
    private val store: SplashAdStore,
    private val deviceUid: () -> String,
) {
    private val appContext = context.applicationContext

    suspend fun refresh(): SplashRefreshOutcome {
        if (!BackendConfig.isConfigured) return SplashRefreshOutcome.Skipped

        val uid = deviceUid()
        if (uid.isBlank()) return SplashRefreshOutcome.Failed(IllegalStateException("deviceUid 为空"))

        // 超龄缓存读不出来 → revision 当作 0 → 退化成一次全量拉取，正是想要的。
        val cached = store.read(SplashAdStore.MAX_AGE_MILLIS)
        val metrics = appContext.resources.displayMetrics
        val feed = service.fetch(
            deviceUid = uid,
            lastRevision = cached?.revision ?: 0L,
            width = metrics.widthPixels,
            height = metrics.heightPixels,
        ).getOrElse { return SplashRefreshOutcome.Failed(it) }

        if (!feed.enabled) {
            store.clear()
            return SplashRefreshOutcome.Cleared
        }
        if (feed.notModified) return SplashRefreshOutcome.NotModified

        // 服务端已经按 weight 排好序，取第一条就是权重最高的那张。
        // 清单为空 = 当前没有生效素材（也可能是总开关刚被打开但还没建素材）。
        val item = feed.items.firstOrNull()
        if (item == null) {
            store.clear()
            return SplashRefreshOutcome.Cleared
        }

        // 文案改了但图没换：revision 变了，sha 没变。这时不必重新下那几个 MB。
        if (cached != null &&
            item.imageSha256.isNotBlank() &&
            cached.imageSha256.equals(item.imageSha256, ignoreCase = true)
        ) {
            // 元数据（标题/时长/revision）还是要更新，否则下次又得全量拉一遍。
            val refreshed = cached.copy(
                id = item.id,
                title = item.title,
                subtitle = item.subtitle,
                linkUrl = item.linkUrl,
                durationMs = item.durationMs,
                revision = feed.revision,
                fetchedAtMillis = System.currentTimeMillis(),
            )
            val existingImage = store.imageFile(cached)
            if (existingImage.isFile && store.updateMeta(refreshed)) {
                return SplashRefreshOutcome.Updated
            }
        }

        // 素材没配图（后台只填了文案、没上传图）就当它不存在：宁可什么都不放，
        // 也不能让上一张已经下线的图永远卡在开屏上 —— 那是运营改不动的东西。
        if (item.imageUrl.isBlank()) {
            store.clear()
            return SplashRefreshOutcome.Cleared
        }

        val bytes = service.downloadImage(item.imageUrl, item.imageSha256)
            .getOrElse { return SplashRefreshOutcome.Failed(it) }

        // 后台填外链时拿不到 sha（远程文件量不了），这时用本地算出来的顶上，
        // 至少保证「哪张图配哪段文案」是自洽的。
        val sha256 = item.imageSha256.ifBlank { BackendSigner.sha256Hex(bytes) }
        val cache = SplashAdCache
            .fromItem(item, feed.revision, System.currentTimeMillis())
            .copy(imageSha256 = sha256)

        return if (store.write(cache, bytes)) {
            SplashRefreshOutcome.Updated
        } else {
            SplashRefreshOutcome.Failed(IOException("开屏缓存写入失败"))
        }
    }

    /**
     * 把 `:splash` 进程攒下的曝光/点击上报掉。
     *
     * 上报失败**直接丢弃**，不重排回文件：这是一份营销计数，为它做重试队列不划算
     * （丢一次曝光不会有任何可观察的后果），而重试队列本身会变成一个需要维护的坑。
     *
     * @return 成功上报的条数。
     */
    suspend fun flushPendingReports(): Int {
        if (!BackendConfig.isConfigured) return 0

        val uid = deviceUid()
        if (uid.isBlank()) return 0

        val pending = store.drainPendingReports()
        if (pending.isEmpty()) return 0

        var reported = 0
        pending.forEach { report ->
            service.report(uid, report.splashId, report.action).onSuccess { reported++ }
        }
        return reported
    }
}
