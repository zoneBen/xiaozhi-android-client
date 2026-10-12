package me.xiaozhi.androidclient.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.util.Range
import android.view.WindowManager
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private const val CAMERA_CAPTURE_TIMEOUT_MS = 12_000L
private const val TAG = "XiaozhiClient"
private const val PHOTO_DIR = "photos"

/**
 * 拍照被用户关掉时的拒绝语。
 *
 * 说的是"用户关的"而不是"出错了"——后者会让模型去劝用户检查摄像头接线。
 *
 * 注意**它只有一条出口了**：现在只有模型经 MCP 调 `self.camera.take_photo` 时才会读到
 * 这句话。监督那条路（`MainViewModel.runSupervisionVerification`）在相机已关时**进入就
 * 短路了**，根本不走到 [CameraVisionTool.takePhotoAndExplain]，它报的是
 * `SupervisionPolicy` 自己的那句"相机已被用户关闭"。
 * 两句话都指向同一个原因，但**不是同一个字符串**，日志断言别把它们混起来。
 */
internal const val CAMERA_DISABLED_MESSAGE = "本机的拍照功能已被用户关闭，无法拍照。"

/**
 * 上传前把像素**真的转正**，而不是只改 EXIF 方向标签。
 *
 * 注意：不要用 `CaptureRequest.JPEG_ORIENTATION` 来做这件事——它只改 EXIF 标签、
 * 不动像素数据。实测设成 180 之后，文件仍是 1280x720 的原始朝向 + Orientation=3，
 * 云端视觉链路一旦不读 EXIF，模型看到的照样是倒的。
 * 所以这里保持 EXIF 为正常朝向，自己把像素转正后再上传。
 *
 * 转多少度由 [CameraSelection.pixelRotationDegrees] 按摄像头类型算，不再是一个写死的
 * 常数：外接模组是倒装的（API 里查不到，只能写死 180），内建摄像头的
 * `SENSOR_ORIENTATION` 可信，要按屏幕方向算。对 TV5701 来说这个区别就是"能看"和
 * "看到一张躺着的图"的区别。
 */
private const val CAMERA_JPEG_ORIENTATION = 0
private const val CAMERA_JPEG_QUALITY = 92
data class VisionEndpoint(
    val url: String,
    val token: String,
)

class CameraVisionTool(
    private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val deviceId: () -> String,
    private val clientId: () -> String,
    /**
     * 每拍一张就回调一次，带上刚存好的文件，交给界面贴进对话。
     *
     * 放在这个类里而不是让各调用方自己贴，是因为拍照有**两个**入口——[McpCameraServer]
     * 的工具调用、以及监督提醒的定时核验（`runSupervisionVerification`）。用户要的是
     * "每次拍照后都能在对话里核对"，那就得是拍摄这个动作本身负责回调，而不是靠调用方
     * 各自记得。默认空实现，单测/无界面的场景不受影响。
     */
    private val onPhotoCaptured: (File) -> Unit = {},
    /**
     * 用户在设置里选了用哪一台内置摄像头（前置 / 后置）。
     *
     * 惰性读，和 [enabled] 一样每次拍摄现查：用户改完设置不该等下个会话才生效。
     * 外接摄像头**不**受它影响（`CameraSelection.choose` 里外接无条件优先）。
     */
    private val preferredFacing: () -> CameraFacing = { CameraFacing.BACK },
    /**
     * 用户有没有关掉拍照。false 时 [takePhotoAndExplain] 直接拒绝，**不开相机**。
     *
     * 围栏放在这里而不是放在调用方，是因为拍照有**两个**入口，而它们在用户眼里
     * 是同一件事：
     *  - [McpCameraServer] 的模型工具调用（不声明 schema 就调不到，那一层已经拦了）；
     *  - 监督提醒的定时核验 `runSupervisionVerification`——它**根本不查 `tools/list`**，
     *    是设备自己到点去拍的。
     *
     * 只拦前一条的话，用户关掉相机之后监督流程照样在拍他：界面上写着"已关闭"，
     * 实际还在拍照。收在这里做单点，将来再多出第三个入口也绕不过去。
     */
    private val enabled: () -> Boolean = { true },
) {
    @Volatile
    private var visionEndpoint: VisionEndpoint? = null

    fun updateVisionEndpoint(url: String?, token: String?) {
        visionEndpoint = url?.takeIf { it.isNotBlank() }?.let {
            VisionEndpoint(url = it, token = token.orEmpty())
        }
    }

    suspend fun takePhotoAndExplain(question: String): String {
        // 先查开关再查权限：相机被用户关掉时，"已被用户关闭"才是他想知道的答案，
        // 报一句权限缺失只会把人引向设置里找一个没坏的东西。
        check(enabled()) { CAMERA_DISABLED_MESSAGE }
        require(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Android camera permission is not granted"
        }
        val endpoint = requireNotNull(visionEndpoint) {
            "Vision upload endpoint has not been provided by the server"
        }
        val jpeg = captureJpeg()
        // 先落盘、先贴进对话，再上传。上传失败（网络、云端超时）时用户照样能在对话里
        // 看到"当时到底拍到了什么"——那正是最需要这张图的时候；反过来先上传的话，
        // 失败路径上这张图就永远丢了。
        storePhoto(jpeg)?.let(onPhotoCaptured)
        return uploadForExplanation(endpoint, question, jpeg)
    }

    /**
     * 拍一张照片并落盘，返回那个文件。**不碰服务端的视觉上传**——"拍照发邮件"要的是
     * 图片本身，走上传等于绕一大圈把图交给云端再拿一段文字描述，图的像素反而拿不到。
     *
     * 和 [takePhotoAndExplain] 共用同一个开关闸和同一段采集代码，所以"用户在设置里关了
     * 拍照，设备就真的一张也不拍"对两个入口同时成立——这是把闸设在工具自己身上的收益，
     * 将来再多一个入口也绕不过去。
     *
     * 落盘失败会抛 [IOException] 而**不是**返回 null：这个方法存在的意义就是给调用方
     * 一个能当附件的文件，拿不到文件时调用方没有任何可继续的动作。这和
     * [takePhotoAndExplain] 那边"落盘失败不影响主流程"的取舍正好相反，因为那边的主流程
     * 是上传，这边的主流程是"把这张图发出去"。
     */
    suspend fun captureToFile(): File {
        check(enabled()) { CAMERA_DISABLED_MESSAGE }
        require(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            "Android camera permission is not granted"
        }
        val jpeg = captureJpeg()
        val file = storePhoto(jpeg) ?: throw IOException("照片存不下来，可能是存储空间满了")
        onPhotoCaptured(file)
        return file
    }

    /**
     * 把这一张存成独立文件，返回给界面用。
     *
     * 存不下就返回 null——**不能因为写文件失败就把拍照整条挂掉**，上传识别才是主流程。
     */
    private fun storePhoto(jpeg: ByteArray): File? = runCatching {
        val dir = context.getExternalFilesDir(PHOTO_DIR) ?: File(context.filesDir, PHOTO_DIR)
        if (!dir.exists()) dir.mkdirs()
        val file = File(dir, PhotoStore.photoFileName(System.currentTimeMillis()))
        file.writeBytes(jpeg)
        PhotoStore.pruneOldPhotos(dir)
        file
    }
        .onFailure { Log.w(TAG, "[CAMERA] 照片落盘失败，这次的图不会出现在对话里：${it.message}") }
        .getOrNull()

    private suspend fun uploadForExplanation(
        endpoint: VisionEndpoint,
        question: String,
        jpeg: ByteArray,
    ): String = withContext(Dispatchers.IO) {
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("question", question)
            .addFormDataPart(
                "file",
                "camera.jpg",
                jpeg.toRequestBody("image/jpeg".toMediaType()),
            )
            .build()

        val request = Request.Builder()
            .url(endpoint.url)
            .header("Device-Id", deviceId())
            .header("Client-Id", clientId())
            .apply {
                if (endpoint.token.isNotBlank()) {
                    header("Authorization", "Bearer ${endpoint.token}")
                }
            }
            .post(body)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Vision upload failed: HTTP ${response.code}")
            }
            response.body?.string()?.takeIf { it.isNotBlank() }
                ?: throw IOException("Vision service returned an empty response")
        }
    }

    /**
     * 把拍摄到的 JPEG 像素真正旋转到位（而不是只写 EXIF 方向标签），
     * 这样无论云端视觉链路读不读 EXIF，模型看到的都是正的。
     */
    private fun rotatePixels(bytes: ByteArray, degrees: Int): ByteArray {
        if (degrees == 0) return bytes
        return runCatching {
            val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return bytes
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
            val output = ByteArrayOutputStream()
            rotated.compress(Bitmap.CompressFormat.JPEG, CAMERA_JPEG_QUALITY, output)
            if (rotated !== source) rotated.recycle()
            source.recycle()
            output.toByteArray()
        }.getOrDefault(bytes)
    }

    /**
     * 把最近一次拍摄的 JPEG 写到应用外部私有目录，供开发期核对画面方向。
     *
     * 转正前后的**都**留一份：`last_capture_raw.jpg` 是传感器原始朝向，
     * `last_capture.jpg` 是实际上传的那张。只保留最近一帧，不累积。
     * 方向这事光看代码看不出来，得把图拉下来看。
     */
    private fun dumpCaptureForDebug(bytes: ByteArray, name: String) {
        runCatching {
            val dir = context.getExternalFilesDir("captures") ?: return
            if (!dir.exists()) dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun captureJpeg(): ByteArray = suspendCancellableCoroutine { continuation ->
        val cameraManager = context.getSystemService(CameraManager::class.java)
        val camera = try {
            selectCamera(cameraManager)
        } catch (error: Exception) {
            continuation.resumeWith(Result.failure(error))
            return@suspendCancellableCoroutine
        }
        val rotationDegrees = CameraSelection.pixelRotationDegrees(camera, deviceRotationDegrees())
        val frameSize = supportedJpegSize(cameraManager, camera.id) ?: CameraSelection.PREFERRED_JPEG_SIZE
        Log.d(
            TAG,
            "[CAMERA] 选中摄像头 ${camera.id}（${camera.facing.label}，" +
                "传感器 ${camera.sensorOrientationDegrees}°），上传前转 $rotationDegrees°，" +
                "输出 ${frameSize.width}x${frameSize.height}",
        )

        val thread = HandlerThread("xiaozhi-camera").apply { start() }
        val handler = Handler(thread.looper)
        val reader = ImageReader.newInstance(frameSize.width, frameSize.height, ImageFormat.JPEG, 2)
        val finished = AtomicBoolean(false)
        // 真正的那一帧只发一次。`captureStill()` 有两条来路——AE 预热完成、以及拿不到
        // 预热流时的退路——各自加锁会在同一次拍摄里重复发请求，白拍几帧还多占队列。
        val stillRequested = AtomicBoolean(false)

        // 曝光时间上限由**帧率区间**决定（1/上限 秒）。HAL 默认区间把帧率钉在 30fps，
        // 等于把曝光封顶在 1/30 秒，而本机传感器允许到 0.4 秒——暗房里差 12 倍进光量。
        // 预热和正式拍摄都要带上这一项，否则单给正式那张设也来不及。
        val fpsRange = lowLightFpsRange(cameraManager, camera.id)
        // 单独一路预览流，只为了让 AE/AWB 在按快门前收敛，它出的帧不进任何照片。
        val primer = primingReader(cameraManager, camera.id, handler)
        Log.d(
            TAG,
            "[CAMERA] AE 帧率区间 ${fpsRange?.let { "[${it.lower}, ${it.upper}]" } ?: "机型未上报"}，" +
                "预热流 ${primer?.let { "${it.width}x${it.height}" } ?: "无（退回直接拍）"}",
        )
        // 这两个句柄跨线程读写：`onOpened` / `onDisconnected` / `onError` 在 HandlerThread
        // 的回调线程上写，而 `closeResources()` 还会在**取消该协程的线程**上执行
        // （`invokeOnCancellation` 不在回调线程上跑）。
        //
        // 它们是被闭包捕获的**局部变量**，Kotlin 会编译成引用对象，所以加不了 @Volatile；
        // 而普通读写在 Android 内存模型下不保证跨线程可见 —— 那会让
        // `if (device == null) device = disconnectedDevice` 白写，
        // 本该被关掉的相机设备仍被 closeResources() 跳过，正是要修的那个句柄泄漏。
        // 用 AtomicReference 一次解决两件事：可见性，以及"读-判-写"的原子性。
        val device = java.util.concurrent.atomic.AtomicReference<CameraDevice?>(null)
        val session = java.util.concurrent.atomic.AtomicReference<CameraCaptureSession?>(null)

        fun closeResources() {
            runCatching { session.get()?.close() }
            runCatching { device.get()?.close() }
            runCatching { reader.close() }
            runCatching { primer?.close() }
            thread.quitSafely()
        }

        fun complete(result: Result<ByteArray>) {
            if (finished.compareAndSet(false, true)) {
                closeResources()
                continuation.resumeWith(result)
            }
        }

        /**
         * 发一次真正的静帧拍摄。
         *
         * 这里带 `AE_PRECAPTURE_TRIGGER = START` 不是可选项：本机内核参数
         * `camera.disable_zsl_mode = 1`，关掉 ZSL 之后 `TEMPLATE_STILL_CAPTURE`
         * **不再自动跑 precapture 序列**，得由应用显式触发，否则拿到的仍是暗片。
         */
        fun captureStill() {
            if (!stillRequested.compareAndSet(false, true)) return
            val openedDevice = device.get() ?: return
            val captureSession = session.get() ?: return
            try {
                val request = openedDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    .apply {
                        addTarget(reader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        set(CaptureRequest.JPEG_ORIENTATION, CAMERA_JPEG_ORIENTATION)
                        fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        set(
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                            CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START,
                        )
                    }
                    .build()
                captureSession.capture(
                    request,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureFailed(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            failure: CaptureFailure,
                        ) {
                            complete(Result.failure(IOException("Camera capture failed: ${failure.reason}")))
                        }

                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) = Unit
                    },
                    handler,
                )
            } catch (error: Exception) {
                complete(Result.failure(error))
            }
        }

        /**
         * 先跑一路重复的预览请求，等 AE 收敛再按键。
         *
         * 这就是"参考项目很清楚、我们全黑"的根因所在：那边是**持续推流**，重复请求一刻不停，
         * AE 一直在收敛；我们原来打开会话就发一次性静帧请求，AE 压根没启动，
         * 拍到的是传感器上电后的初始帧。这里补上这一步，并在
         * [AutoExposure.MAX_WAIT_MS] 到点后放弃等待——宁可欠曝一点，也不能卡到超时。
         */
        fun startPriming(captureSession: CameraCaptureSession, primingReader: ImageReader) {
            val openedDevice = device.get() ?: return
            val startedAt = SystemClock.elapsedRealtime()
            try {
                val request = openedDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
                    .apply {
                        addTarget(primingReader.surface)
                        set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                        set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
                        set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
                        fpsRange?.let { set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, it) }
                        // 这里**故意不设** AE_PRECAPTURE_TRIGGER：这是重复请求，每帧都下
                        // START 会把 precapture 序列一遍遍重启，AE 永远到不了 CONVERGED，
                        // 正好退回我们要修的那个症状。START 只留给 captureStill()。
                    }
                    .build()
                captureSession.setRepeatingRequest(
                    request,
                    object : CameraCaptureSession.CaptureCallback() {
                        override fun onCaptureCompleted(
                            session: CameraCaptureSession,
                            request: CaptureRequest,
                            result: TotalCaptureResult,
                        ) {
                            val elapsedMs = SystemClock.elapsedRealtime() - startedAt
                            val readiness = AutoExposure.readinessOfAeState(result.get(CaptureResult.CONTROL_AE_STATE))
                            if (!AutoExposure.shouldCapture(readiness, elapsedMs)) return
                            Log.d(TAG, "[CAMERA] AE $readiness（预热 ${elapsedMs}ms），开始拍摄")
                            runCatching { captureSession.stopRepeating() }
                            captureStill()
                        }
                    },
                    handler,
                )
            } catch (error: Exception) {
                complete(Result.failure(error))
            }
        }

        reader.setOnImageAvailableListener({ imageReader ->
            try {
                imageReader.acquireLatestImage().use { image ->
                    val buffer = image.planes.firstOrNull()?.buffer
                        ?: throw IOException("Camera returned an empty JPEG frame")
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    val normalized = rotatePixels(bytes, rotationDegrees)
                    dumpCaptureForDebug(bytes, "last_capture_raw.jpg")
                    dumpCaptureForDebug(normalized, "last_capture.jpg")
                    complete(Result.success(normalized))
                }
            } catch (error: Exception) {
                complete(Result.failure(error))
            }
        }, handler)

        val timeout = Runnable {
            complete(Result.failure(IOException("Camera capture timed out after ${CAMERA_CAPTURE_TIMEOUT_MS}ms")))
        }
        handler.postDelayed(timeout, CAMERA_CAPTURE_TIMEOUT_MS)

        continuation.invokeOnCancellation {
            handler.removeCallbacks(timeout)
            if (finished.compareAndSet(false, true)) {
                closeResources()
            }
        }

        try {
            cameraManager.openCamera(camera.id, object : CameraDevice.StateCallback() {
                override fun onOpened(openedDevice: CameraDevice) {
                    if (finished.get()) {
                        runCatching { openedDevice.close() }
                        return
                    }
                    device.set(openedDevice)
                    // 这里整个包 try：`createCaptureSession` 会抛 CameraAccessException /
                    // IllegalStateException（例如 USB 摄像头在打开后、建会话前掉线，
                    // 或 HAL 拒绝会话配置）。此刻代码已经跑在 HandlerThread 的回调里，
                    // 异常逃出去**没有任何人接**，会被线程的默认异常处理器抓住并
                    // **直接结束整个应用进程**。注意外层那个 try 只包住了
                    // `openCamera()` 调用本身，捕不到这个稍后才执行的回调。
                    try {
                        openedDevice.createCaptureSession(
                            // 预热流和 JPEG 流**一起**开会话：两路都挂在同一个会话里，
                            // 之后从预热切到拍摄只需换请求，不用重建会话（重建要几百毫秒，
                            // 而且有些 HAL 重建时会把 AE 状态一起清掉，白预热）。
                            listOfNotNull(reader.surface, primer?.surface),
                            object : CameraCaptureSession.StateCallback() {
                                override fun onConfigured(captureSession: CameraCaptureSession) {
                                    if (finished.get()) {
                                        runCatching { captureSession.close() }
                                        return
                                    }
                                    session.set(captureSession)
                                    val primingReader = primer
                                    if (primingReader == null) {
                                        // 机型连一路预览尺寸都报不出来，没法预热。退回直接拍
                                        // （captureStill() 仍会带 precapture 触发），别让拍照直接失败。
                                        captureStill()
                                    } else {
                                        startPriming(captureSession, primingReader)
                                    }
                                }

                                override fun onConfigureFailed(captureSession: CameraCaptureSession) {
                                    complete(Result.failure(IOException("Camera capture session configuration failed")))
                                }
                            },
                            handler,
                        )
                    } catch (error: Exception) {
                        complete(Result.failure(error))
                    }
                }

                override fun onDisconnected(disconnectedDevice: CameraDevice) {
                    // 走到这里说明设备掉线或出错。若 `onOpened` 从没执行过，
                    // closeResources() 里的 device 还是 null，**回调传进来的这个设备
                    // 就没人关了**，会一直占着相机句柄。先把它记下来再收尾。
                    device.compareAndSet(null, disconnectedDevice)
                    complete(Result.failure(IOException("Camera was disconnected")))
                }

                override fun onError(errorDevice: CameraDevice, error: Int) {
                    device.compareAndSet(null, errorDevice)
                    complete(Result.failure(IOException("Camera failed to open: $error")))
                }
            }, handler)
        } catch (error: Exception) {
            complete(Result.failure(error))
        }
    }

    /**
     * 枚举本机摄像头并挑一台。
     *
     * 照 `workdayAlarmClockAndroid` 的 `AvcCameraPipeline` 那样**遍历 `cameraIdList` 全部条目**
     * （那边是 `enumerateTargets(...).getOrNull(cameraIndex)`），不拿 `LENS_FACING` 当门槛。
     * 原来这里只认 `LENS_FACING_EXTERNAL`，于是在 TV5701 上直接抛
     * `No external USB camera is available`——那台机器只有一台 `internal/0`、
     * `LENS_FACING_BACK` 的内置摄像头，`/dev/video*` 根本不存在。设了门槛就等于"没有摄像头"。
     *
     * 挑哪一台交给 [CameraSelection.choose]：有外接仍然优先外接，保住 RK3568 的既有行为；
     * 没有外接时按用户在设置里选的朝向（前置 / 后置），没得选才用第一台。
     */
    private fun selectCamera(cameraManager: CameraManager): CameraCandidate {
        val candidates = cameraManager.cameraIdList.mapNotNull { id ->
            runCatching {
                val characteristics = cameraManager.getCameraCharacteristics(id)
                CameraCandidate(
                    id = id,
                    facing = facingOf(characteristics.get(CameraCharacteristics.LENS_FACING)),
                    sensorOrientationDegrees =
                        characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0,
                )
            }
                .onFailure { Log.w(TAG, "[CAMERA] 读不到摄像头 $id 的特征：${it.message}") }
                .getOrNull()
        }
        return CameraSelection.choose(candidates, preferredFacing())
            ?: throw IOException("本机没有可用的摄像头")
    }

    /**
     * 本机有几个**内置**摄像头的朝向可选，给设置页的"用哪一个"那一行用。
     *
     * 只报内置（前置 / 后置）：外接在 `CameraSelection.choose` 里是**无条件优先**的，
     * 把它摆进选择器会造出"你选了后置、实际拍的还是 USB"这种假选项。
     * 返回不足两项时设置页不显示这一行——只有一台可选的机器上没什么可选的。
     *
     * 只读系统列表、不开相机，所以随时可以调。顺序固定为前置在前，不跟系统的枚举顺序走，
     * 免得同一个选择器在不同机器上左右位置相反。
     */
    fun availableBuiltinFacings(): List<CameraFacing> {
        val cameraManager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
        return runCatching {
            cameraManager.cameraIdList
                .mapNotNull { id ->
                    runCatching {
                        facingOf(cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING))
                    }.getOrNull()
                }
                .filter { it == CameraFacing.FRONT || it == CameraFacing.BACK }
                .distinct()
                .sortedBy { if (it == CameraFacing.FRONT) 0 else 1 }
        }.getOrElse {
            Log.w(TAG, "[CAMERA] 读不到摄像头朝向，前/后置选择这一行不显示：${it.message}")
            emptyList()
        }
    }

    /**
     * `LENS_FACING` 到 [CameraFacing] 的映射。
     *
     * 读不到（老 HAL 不报这一项）落到 [CameraFacing.UNKNOWN]，它按后置那套公式转——
     * 这条映射之前就是"不是外接即内建"的二分，认不出来按后置算和它一致。
     */
    private fun facingOf(lensFacing: Int?): CameraFacing = when (lensFacing) {
        CameraCharacteristics.LENS_FACING_EXTERNAL -> CameraFacing.EXTERNAL
        CameraCharacteristics.LENS_FACING_FRONT -> CameraFacing.FRONT
        CameraCharacteristics.LENS_FACING_BACK -> CameraFacing.BACK
        else -> CameraFacing.UNKNOWN
    }

    /** 这台摄像头支持哪些 JPEG 输出尺寸。读不到就返回 null，由调用方退回首选尺寸。 */
    private fun supportedJpegSize(cameraManager: CameraManager, cameraId: String): FrameSize? =
        supportedSizes(cameraManager, cameraId, ImageFormat.JPEG)?.let { CameraSelection.chooseJpegSize(it) }

    /**
     * 开着摄像头能出的所有尺寸。
     *
     * 读不到（老 HAL 不报 `SCALER_STREAM_CONFIGURATION_MAP`、或摄像头刚被拔掉）返回 null，
     * 让调用方各自决定退路——JPEG 那边退到写死的首选尺寸，预热那边退到"不预热直接拍"。
     */
    private fun supportedSizes(cameraManager: CameraManager, cameraId: String, format: Int): List<FrameSize>? =
        runCatching {
            cameraManager.getCameraCharacteristics(cameraId)
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                ?.getOutputSizes(format)
                ?.map { FrameSize(it.width, it.height) }
        }.getOrNull()

    /**
     * 建一路只用来让 AE 收敛的预览流：`YUV_420_888`，尺寸取支持的最小值。
     *
     * 用 YUV 而不是再开一路 JPEG：JPEG 编码要现做，白费 CPU；YUV 是传感器直出的格式，
     * 几乎所有机型都支持。帧拿到就丢（见下面的监听器），它只负责把 AE/AWB 喂起来。
     * 一路尺寸都报不出来时返回 null，调用方退回"不预热直接拍"。
     */
    private fun primingReader(cameraManager: CameraManager, cameraId: String, handler: Handler): ImageReader? =
        supportedSizes(cameraManager, cameraId, ImageFormat.YUV_420_888)
            ?.let { CameraSelection.choosePrimingSize(it) }
            ?.let { size ->
                ImageReader.newInstance(size.width, size.height, ImageFormat.YUV_420_888, 2).apply {
                    // 必须消费，否则队列满了 HAL 会停推，预热就白做了。
                    setOnImageAvailableListener({ priming ->
                        runCatching { priming.acquireLatestImage()?.close() }
                    }, handler)
                }
            }

    /**
     * 本机允许的帧率区间里，**曝光时间能放最长**的那一个。
     *
     * `CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` 给的是
     * `android.util.Range`，而纯规则那层只认 `IntRange`（好单测），所以在这里转换。
     * 读不到就返回 null，调用方别设这一项、用 HAL 默认值。
     */
    private fun lowLightFpsRange(cameraManager: CameraManager, cameraId: String): Range<Int>? = runCatching {
        val ranges = cameraManager.getCameraCharacteristics(cameraId)
            .get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            .orEmpty()
            .map { it.lower..it.upper }
        AutoExposure.lowLightFpsRange(ranges)?.let { Range(it.first, it.last) }
    }.getOrNull()

    /**
     * 当前屏幕方向。App 跑在 kiosk 里、方向由系统定（TV5701 实测 rotation 3 = 270°），
     * 取不到就按 0 算——宁可图歪一点，也不能因为读个屏幕方向就把拍照整条挂掉。
     */
    @Suppress("DEPRECATION")
    private fun deviceRotationDegrees(): Int = runCatching {
        val windowManager = context.getSystemService(WindowManager::class.java)
        CameraSelection.rotationDegreesOf(windowManager.defaultDisplay.rotation)
    }.getOrDefault(0)
}
