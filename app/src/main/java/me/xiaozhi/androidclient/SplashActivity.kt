package me.xiaozhi.androidclient

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.AlphaAnimation
import android.view.animation.Animation
import android.widget.ImageView
import android.widget.TextView
import java.io.File
import me.xiaozhi.androidclient.backend.SplashAdCache
import me.xiaozhi.androidclient.backend.SplashAdStore
import me.xiaozhi.androidclient.backend.visibleMillis
import me.xiaozhi.androidclient.util.ImageSampling

/**
 * 冷启动等待画面：气泡呼吸 + 声波扩散，顶上那 8~9 秒。
 *
 * ## 为什么需要单独一个 Activity
 *
 * 那段时间屏幕上显示的是系统「启动窗口」，它由 system_server 画。实测这台 TV5701
 * （Android 9 / MTK 固件）的启动窗口只画一次快照：把 animation-list 挂在主题的
 * windowBackground 上，连拍十帧像素完全一致，永远停在第一帧。
 *
 * ## 为什么要单独一个进程（manifest 里的 android:process=":splash"）
 *
 * 冷启动的开销是 ART 现场校验字节码，发生在主进程的主线程上（见
 * app/build.gradle.kts 里 dex 相关的注释，本机量到 MainActivity 要 8~9 秒）。
 * 头屏如果和主界面同进程，一调 startActivity 主线程就卡在校验里，动画跟着冻住，
 * 看起来反而更像死机。分进程之后头屏进程的主线程全程空闲。
 *
 * ## 为什么不用 AnimationDrawable
 *
 * 试过，跑不起来：挂在这台机器的启动窗口上冻在第一帧；换到本 Activity 自己的
 * ImageView 上同样不动——`dumpsys gfxinfo` 显示头屏进程 5 秒内只渲染了 3 帧，
 * 且不再增长。与其去猜是哪一层没把 setVisible(true) 传下来，不如自己推帧：
 * 下面这个 Handler 每 165ms 换一张图，帧循环完全握在自己手里。
 *
 * ## 开屏广告必须盖在主界面**起来之前**（2026-10-12 修）
 *
 * 上面说的 8~9 秒是 TV5701 的冷启动代价。**PLQ110 上主界面 0.4 秒就画好了**
 * （实测三轮：头屏从起帧到收工 0.39/0.39/0.38 秒）。
 *
 * 第一版把广告「盖在那段等待上」，在这台机器上等于没有广告：图还在后台线程解码，
 * 主界面已经画好、头屏已经收了，人一次没看见，而曝光每轮都记了一笔。
 *
 * 第二版改成「主界面照旧立刻起，只是拖住头屏不收」——还是不行，实机验证过：
 * 主界面是 NEW_TASK 立刻起来的，它一画出首帧就把头屏整个盖住了，头屏在后面
 * 多活几秒没有任何人看得见。日志里清清白白写着「还剩 4545ms，展示完再收」，
 * 屏幕上是主界面。
 *
 * 所以顺序反过来：**先问广告，再起主界面**。有广告就等它放完（`holdMs` 之后）再起；
 * 没有就立刻起 —— 没有素材的机器与加这个功能之前一字不差，代价只有一次 `exists()`。
 * 展示时长钳在 `[MIN_VISIBLE_MILLIS, MAX_VISIBLE_MILLIS]`（2~5 秒，见
 * `SplashAdCache.visibleMillis`），所以**真有素材**时冷启动最多慢这 5 秒。
 *
 * 代价说清楚：主界面那几秒的类校验不再与广告并行，而是排在广告之后。TV5701 上
 * 「广告 5 秒 + 校验 8~9 秒」= 14 秒，加广告之前是 8~9 秒。这是放一条广告的实际价格。
 *
 * 这个 Activity 刻意只用平台类（Activity / ImageView / Handler），不碰 Compose
 * 和 AndroidX：进程起得越轻，头屏出现得越早。
 */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var mainDrawnReceiver: BroadcastReceiver? = null
    private var frameIndex = 0

    /**
     * 主界面起过没有。
     *
     * 现在有**三条路**会走到「起主界面」：onCreate 里发现没有广告、广告放完、
     * 超时兜底。用 [startMainActivityOnce] 收口，免得将来再多一条路时糊里糊涂起两次
     * —— 那会真的弹出第二个 MainActivity。
     */
    private var mainStarted = false

    /** 自己推帧，见类注释里「为什么不用 AnimationDrawable」。 */
    private val frameTicker = object : Runnable {
        override fun run() {
            val image = findViewById<ImageView>(R.id.splash_image)
            if (image == null) {
                Log.w(TAG, "头屏 ImageView 已不在，停止推帧")
                return
            }
            image.setImageResource(FRAME_RES[frameIndex])
            frameIndex = (frameIndex + 1) % FRAME_RES.size
            handler.postDelayed(this, FRAME_DURATION_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 主进程还活着说明这次不是冷启动（用户按了 HOME、或从最近任务切回来），
        // 那就没有等待可言，直接把主界面叫起来收工，别再放一遍动画。
        if (isMainProcessAlive()) {
            Log.i(TAG, "主进程已在运行，跳过头屏动画")
            startMainActivityOnce()
            finish()
            return
        }

        setContentView(R.layout.activity_splash)
        listenForMainDrawn()

        // 兜底：主界面万一崩了、或者广告那条路卡住（解码卡死、后台线程没回来），
        // 头屏不能一直赖着不走。
        handler.postDelayed({ finishAfterTimeout() }, MAIN_DRAWN_TIMEOUT_MS)

        // 开屏素材。**放在最后**：上面那几步（setContentView / 排超时）是头屏能不能
        // 准时出现的全部条件，先保证它们，再去管广告。也正因为写在 isMainProcessAlive()
        // 那个 return 之后，它只在**真冷启动**时才跑 —— 按 HOME 切回来不会弹广告。
        //
        // 主界面什么时侯起也由这里决定：有广告就先放广告、放完再起（见类注释里
        // 「广告必须盖在主界面起来之前」）。所以它必须**保证**每条分支都走到起主界面，
        // 少一条就是一个白屏死等。
        loadSplashAd()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(frameTicker)
        handler.post(frameTicker)
        Log.i(TAG, "头屏开始播放 ${FRAME_RES.size} 帧动画")
    }

    // 注意：这里**不能**在 onPause 里停推帧。
    // MainActivity 是在 onCreate 里就拉起来的，系统会在它启动前先 pause 头屏；
    // 但此时 MainActivity 的窗口还没画出来，屏幕上仍然是头屏那张图。
    // 一旦在 onPause 停掉推帧，用户看到的就是一张定格图——正是要解决的问题。

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        mainDrawnReceiver?.let { runCatching { unregisterReceiver(it) } }
        mainDrawnReceiver = null
        super.onDestroy()
    }

    /**
     * 起主界面。**只起一次** —— 「没有广告」「广告放完」「超时兜底」三条路都汇到这里。
     *
     * MainActivity 是 singleTask，必须 NEW_TASK 起，否则会塞进头屏所在的 task。
     */
    private fun startMainActivityOnce() {
        if (mainStarted) return
        mainStarted = true

        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /** 主界面画出第一帧后会发这个广播，收到就说明头屏可以让位了。 */
    private fun listenForMainDrawn() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                Log.i(TAG, "主界面已绘制，收起头屏")
                finish()
            }
        }
        val filter = IntentFilter(ACTION_MAIN_DRAWN)
        // targetSdk 34 起运行时注册必须显式声明导出性；两个进程同 UID，
        // NOT_EXPORTED 不影响自己给自己发。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
        mainDrawnReceiver = receiver
    }

    /** 只认主进程；头屏自己跑在 `:splash` 进程里，别把自己也算进去。 */
    private fun isMainProcessAlive(): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        return manager.runningAppProcesses.orEmpty().any { it.processName == packageName }
    }

    private fun finishAfterTimeout() {
        Log.w(TAG, "等主界面超时（${MAIN_DRAWN_TIMEOUT_MS}ms），先收起头屏")
        // 也把主界面起起来：能走到这儿说明有一条路没走到头（多半是广告那条），
        // 但用户不该被丢在头屏上，更不该被丢回桌面。
        startMainActivityOnce()
        finish()
    }

    // ---- 开屏素材 ----------------------------------------------------------
    //
    // 这一整块的首要约束是**不许拖慢头屏**，其次是**不许因为任何异常而难看**。
    // 每一步失败（没缓存、meta 坏了、图没了、解码失败、跳转失败）都直接放弃，
    // 屏幕上留下的就是原来那 8 帧动画 —— 用户不会知道刚才发生过什么，
    // 这正是想要的：服务器是自托管的，它出问题的正常表现就是「什么都没有」。

    /**
     * 读缓存 → 解码 → 盖上广告层 → 到点起主界面。
     *
     * 全程在后台线程：读 meta.json 和一张几 MB 的图都要碰磁盘，而主线程正在推那 8 帧动画
     * —— 在主线程解码正是这个类要治的那种卡顿。
     *
     * 这里的网络依赖是**零**：`SplashAdStore` 只读本地文件，图片是上一次 App
     * 运行时（主进程）预先下好的。所以服务器此刻是死是活都不影响这一屏。
     *
     * **每条分支都必须以 [startMainActivityOnce] 收尾**，因为主界面现在等的就是这一句。
     */
    private fun loadSplashAd() {
        val store = SplashAdStore(this)

        // 没有 meta.json 就是没有素材。绝大多数机器、以及后台把素材停用之后的机器
        // 都在这条路上，所以它必须零开销：一次 exists()，然后主界面照旧立刻起来。
        if (!store.hasCache()) {
            Log.i(TAG, "本地没有开屏缓存，直接进主界面")
            startMainActivityOnce()
            return
        }

        Thread {
            // 超龄的缓存 read() 会当作没有（服务器长期失联时，不能把一张早就过期的
            // 活动图永远钉在开屏上）；meta 坏了、图没了、解码失败 likewise ——
            // 一口气全落到「这轮没有广告」，屏幕上留下的还是原来那 8 帧动画，
            // 用户不会知道刚才发生过什么。
            val cache = store.read(SplashAdStore.MAX_AGE_MILLIS)
            val bitmap = cache?.let { decodeAdBitmap(store.imageFile(it)) }
            val ready = if (cache == null || bitmap == null) null else cache to bitmap

            handler.post {
                if (ready == null) {
                    resolveWithoutAd()
                } else {
                    showSplashAd(ready.first, ready.second)
                }
            }
        }.apply { isDaemon = true }.start()
    }

    /** 这轮没有广告。给主界面让路，屏幕上留下的就是原来那 8 帧动画。 */
    private fun resolveWithoutAd() {
        Log.i(TAG, "这轮没有开屏广告，进主界面")
        startMainActivityOnce()
    }

    /**
     * 按屏幕尺寸采样解码。
     *
     * 走 [ImageSampling.portraitSampleSize]（「两个方向都要盖住目标框」那套）而不是探针那套：
     * 开屏图是按 `centerCrop` 铺满的，采样过头会糊。直接用 `decodeFile` 整张解的话，
     * 一张 1080×2400 的图就是 10 MB 位图，这块板子经不起。
     */
    private fun decodeAdBitmap(file: File): Bitmap? = runCatching {
        if (!file.isFile) return null

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        // 只读尺寸失败（文件损坏 / 不是图片）时 outWidth 是 -1，`sampleSizeFor` 会退回 1，
        // 于是下面这次解码同样失败并返回 null —— 交给调用方静默放弃。
        val metrics = resources.displayMetrics
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSampling.portraitSampleSize(
                bounds.outWidth,
                bounds.outHeight,
                metrics.widthPixels,
                metrics.heightPixels,
            )
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
    }.getOrNull()

    private fun showSplashAd(cache: SplashAdCache, bitmap: Bitmap) {
        val layer = findViewById<View>(R.id.splash_ad_layer) ?: run {
            Log.w(TAG, "布局里没有开屏广告层，这一轮不放广告")
            resolveWithoutAd()
            return
        }
        // 后台把展示时长填成 0 就是「不放广告」，别翻出一个 0 毫秒的曝光来。
        val holdMs = cache.visibleMillis
        if (holdMs <= 0L) {
            resolveWithoutAd()
            return
        }

        findViewById<ImageView>(R.id.splash_ad_image)?.setImageBitmap(bitmap)

        // 空文案就不显示那一行，而不是留一块空白：后台允许只传图不写字。
        findViewById<TextView>(R.id.splash_ad_title)?.apply {
            text = cache.title
            visibility = if (cache.title.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<TextView>(R.id.splash_ad_subtitle)?.apply {
            text = cache.subtitle
            visibility = if (cache.subtitle.isBlank()) View.GONE else View.VISIBLE
        }
        findViewById<View>(R.id.splash_ad_text_row)?.visibility =
            if (cache.title.isBlank() && cache.subtitle.isBlank()) View.GONE else View.VISIBLE

        // **没有跳转链接就不注册点击**。让整块图可点却什么都不发生，用户会连点几下；
        // 而每一次点击都会被记成一次「点击」上报，等于往统计里灌水。
        if (cache.linkUrl.isNotBlank()) {
            layer.setOnClickListener {
                recordAdAction(cache, SplashAdStore.ACTION_CLICK)
                openAdLink(cache.linkUrl)
            }
        }

        layer.visibility = View.VISIBLE

        // 曝光在**盖上去的这一刻**就记，不是等它淡出：用户完全可能在展示期间按 HOME 走掉，
        // 那种情况下他确实看过这张图了。
        recordAdAction(cache, SplashAdStore.ACTION_SHOW)

        // 展示时间是从"盖上去了"算起的，不是从"头屏起来了"算起 —— 中间那段解码
        // 是客户端自己的开销，不该算进运营买的这几秒里。
        //
        // 到点了：淡出 + 起主界面。**主界面只能等到这一刻才起** —— 它一起来就会把
        // 头屏（连同上面这层广告）整个盖住，起早了广告就白放了。
        Log.i(TAG, "开屏广告已覆盖，展示 ${holdMs}ms（后台填的 ${cache.durationMs}ms）")
        handler.postDelayed({ startMainActivityOnce() }, holdMs)
        handler.postDelayed({ fadeOutSplashAd(layer) }, holdMs)
    }

    /** 淡出后重新露出下面那 8 帧动画。用户看到的是「广告放完了」，不是一个跳变。 */
    private fun fadeOutSplashAd(layer: View) {
        val fade = AlphaAnimation(1f, 0f).apply { duration = AD_FADE_DURATION_MS }
        fade.setAnimationListener(object : Animation.AnimationListener {
            override fun onAnimationStart(animation: Animation?) = Unit
            override fun onAnimationRepeat(animation: Animation?) = Unit
            override fun onAnimationEnd(animation: Animation?) {
                layer.visibility = View.GONE
            }
        })
        layer.startAnimation(fade)
    }

    /**
     * 记一次曝光/点击。**开屏进程不联网** —— 那会把 OkHttp 拉进这个进程，
     * 头屏就没那么轻了。所以这里只往文件里追加一行，由主进程下次起来时取走上报。
     */
    private fun recordAdAction(cache: SplashAdCache, action: String) {
        SplashAdStore(this).appendPendingReport(cache.id, action)
    }

    private fun openAdLink(linkUrl: String) {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(linkUrl)).apply {
                // 从 Activity 起外部应用，用 NEW_TASK 才不会被塞进头屏所在的那个 task。
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        }.onFailure {
            // 设备上可能压根没有浏览器（这是台 kiosk 面板）。跳不过去就算了，
            // 但**点击已经记下了** —— 用户确实点了。
            Log.w(TAG, "开屏跳转失败：${it.message}")
        }
    }

    companion object {
        private const val TAG = "XiaozhiSplash"

        /** MainActivity 首帧绘制完成的通知，见 MainActivity.onCreate 里的 OnPreDrawListener。 */
        const val ACTION_MAIN_DRAWN = "me.xiaozhi.androidclient.action.MAIN_DRAWN"

        /** 与帧素材生成时用的节奏一致（8 帧 ≈ 1.32s 一轮，首尾无缝）。 */
        private const val FRAME_DURATION_MS = 165L

        /** 兜底超时。正常情况下主界面 8~9 秒就起来了，留足余量。 */
        private const val MAIN_DRAWN_TIMEOUT_MS = 30_000L

        /** 开屏素材淡出的时长。短一点：用户等的是主界面，不是这条动画。 */
        private const val AD_FADE_DURATION_MS = 400L

        private val FRAME_RES = intArrayOf(
            R.drawable.splash_00,
            R.drawable.splash_01,
            R.drawable.splash_02,
            R.drawable.splash_03,
            R.drawable.splash_04,
            R.drawable.splash_05,
            R.drawable.splash_06,
            R.drawable.splash_07,
        )
    }
}
