package me.xiaozhi.androidclient

import android.app.Activity
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ImageView

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
 * 这个 Activity 刻意只用平台类（Activity / ImageView / Handler），不碰 Compose
 * 和 AndroidX：进程起得越轻，头屏出现得越早。
 */
class SplashActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private var mainDrawnReceiver: BroadcastReceiver? = null
    private var frameIndex = 0

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
            startMainActivity()
            finish()
            return
        }

        setContentView(R.layout.activity_splash)
        listenForMainDrawn()

        // 先把主界面拉起来，让它那几秒的类校验和自己的动画并行跑。
        startMainActivity()

        // 兜底：主界面万一崩了，头屏不能一直赖着不走。
        handler.postDelayed({ finishAfterTimeout() }, MAIN_DRAWN_TIMEOUT_MS)
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

    private fun startMainActivity() {
        // MainActivity 是 singleTask，必须 NEW_TASK 起，否则会塞进头屏所在的 task。
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    /** 主界面画出第一帧后会发这个广播，收到就说明可以让位了。 */
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
        finish()
    }

    companion object {
        private const val TAG = "XiaozhiSplash"

        /** MainActivity 首帧绘制完成的通知，见 MainActivity.onCreate 里的 OnPreDrawListener。 */
        const val ACTION_MAIN_DRAWN = "me.xiaozhi.androidclient.action.MAIN_DRAWN"

        /** 与帧素材生成时用的节奏一致（8 帧 ≈ 1.32s 一轮，首尾无缝）。 */
        private const val FRAME_DURATION_MS = 165L

        /** 兜底超时。正常情况下主界面 8~9 秒就起来了，留足余量。 */
        private const val MAIN_DRAWN_TIMEOUT_MS = 30_000L

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
