package com.camrtsp.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class RtspServerService : LifecycleService() {
    companion object {
        const val LOG_FILE_NAME = "camrtsp.log"
        const val CRASH_ACTION = "com.camrtsp.app.CRASH"

        private const val MAX_LOG_BYTES = 512L * 1024L
        private val running = AtomicBoolean(false)

        @Volatile private var logSink: ((String, String) -> Unit)? = null

        val isRunning: Boolean
            get() = running.get()

        fun attachLog(sink: (String, String) -> Unit) {
            logSink = sink
        }

        fun detachLog(sink: (String, String) -> Unit) {
            if (logSink === sink) logSink = null
        }

        fun appendPersistentLog(filesDir: File, level: String, line: String) {
            synchronized(RtspServerService::class.java) {
                val file = File(filesDir, LOG_FILE_NAME)
                if (file.exists() && file.length() > MAX_LOG_BYTES) {
                    val rotated = File(filesDir, "$LOG_FILE_NAME.1")
                    if (rotated.exists()) rotated.delete()
                    file.renameTo(rotated)
                }
                file.appendText("$level ${System.currentTimeMillis()}: $line\n")
            }
        }
    }

    private val frameRate = 25
    private val colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val skipFrames = AtomicBoolean(true)
    private val drainActive = AtomicBoolean(false)
    private val codecReinitialized = AtomicBoolean(false)
    private val reinitLock = Any()
    private val lastFrameError = AtomicReference<String?>(null)

    private var rtsp: RtspServer? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var drainThread: Thread? = null
    @Volatile private var cameraProvider: ProcessCameraProvider? = null
    @Volatile private var encoderWidth: Int = 0
    @Volatile private var encoderHeight: Int = 0
    @Volatile private var frameSize: Int = 0

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        log("Service onCreate", "I")
        try {
            startForegroundNotification()
        } catch (e: Exception) {
            log("foreground setup failed: ${e.javaClass.simpleName}: ${e.message}", "E")
            log(stackToString(e), "E")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (!running.compareAndSet(false, true)) {
            log("Already running", "W")
            return START_STICKY
        }
        log("onStartCommand: launching boot thread", "I")
        Thread({ startStreamingStack() }, "RtspBoot").start()
        return START_STICKY
    }

    override fun onDestroy() {
        log("Service onDestroy", "I")
        running.set(false)
        drainActive.set(false)
        skipFrames.set(true)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Throwable) {
        }
        try {
            drainThread?.join(1_000)
        } catch (_: Throwable) {
        }
        releaseCodec()
        try {
            rtsp?.stop()
        } catch (_: Throwable) {
        }
        try {
            analyzerExecutor.shutdownNow()
        } catch (_: Throwable) {
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        log("onTaskRemoved: keeping service alive", "I")
    }

    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                val crash = "FATAL on thread ${thread.name}: ${error.javaClass.simpleName}: ${error.message}\n${stackToString(error)}"
                Log.e("CamRTSP", crash)
                appendPersistentLog(filesDir, "FATAL", crash)
                val intent = Intent(CRASH_ACTION)
                    .putExtra("crash", crash)
                    .setPackage(packageName)
                sendBroadcast(intent)
            } catch (_: Throwable) {
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun startForegroundNotification() {
        val channelId = "rtsp_ch"
        val notificationManager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            notificationManager.createNotificationChannel(
                NotificationChannel(channelId, "RTSP", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("CamRTSP")
            .setContentText("Broadcasting camera video on Wi-Fi")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true)
            .build()
        startForeground(1, notification)
    }

    private fun startStreamingStack() {
        val targetWidth = 1280
        val targetHeight = 720

        try {
            rtsp = RtspServer(targetWidth, targetHeight, frameRate).also {
                it.start()
                log("RtspServer started on port ${RtspServer.PORT}", "I")
            }
        } catch (e: Exception) {
            failStartup("RtspServer.start()", e)
            return
        }

        try {
            encoderWidth = targetWidth
            encoderHeight = targetHeight
            frameSize = targetWidth * targetHeight * 3 / 2
            codec = createCodec(targetWidth, targetHeight)
            log("Encoder ready: ${targetWidth}x${targetHeight} fmt=NV12 size=$frameSize", "I")
        } catch (e: Exception) {
            failStartup("Encoder init", e)
            return
        }

        startDrainThread()
        bindCamera()
    }

    private fun failStartup(component: String, error: Exception) {
        log("$component failed: ${error.javaClass.simpleName}: ${error.message}", "E")
        log(stackToString(error), "E")
        running.set(false)
        stopSelf()
    }

    private fun createCodec(width: Int, height: Int): MediaCodec {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun startDrainThread() {
        drainActive.set(true)
        drainThread = Thread({ drainLoop() }, "RtpDrain").also { it.start() }
        log("Drain thread started", "I")
    }

    private fun bindCamera() {
        try {
            val future = ProcessCameraProvider.getInstance(this)
            future.addListener({
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    val analysis = ImageAnalysis.Builder()
                        .setTargetResolution(Size(encoderWidth, encoderHeight))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply { setAnalyzer(analyzerExecutor, FrameAnalyzer()) }

                    provider.unbindAll()
                    provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                    skipFrames.set(false)
                    log("Camera bound (target=${encoderWidth}x${encoderHeight})", "I")
                } catch (e: Exception) {
                    log("CameraX bind failed: ${e.javaClass.simpleName}: ${e.message}", "E")
                    log(stackToString(e), "E")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            log("getCameraProvider failed: ${e.javaClass.simpleName}: ${e.message}", "E")
            log(stackToString(e), "E")
        }
    }

    private fun reinitCodec(newWidth: Int, newHeight: Int) {
        if (codecReinitialized.get()) return
        synchronized(reinitLock) {
            if (codecReinitialized.get()) return
            codecReinitialized.set(true)
            skipFrames.set(true)
            log("Reinit encoder ${encoderWidth}x${encoderHeight} -> ${newWidth}x${newHeight}", "W")

            drainActive.set(false)
            try {
                drainThread?.join(1_000)
            } catch (_: Throwable) {
            }
            releaseCodec()
            rtsp?.resetCodecConfig()

            try {
                encoderWidth = newWidth
                encoderHeight = newHeight
                frameSize = newWidth * newHeight * 3 / 2
                codec = createCodec(newWidth, newHeight)
                log("Encoder reinit OK: ${newWidth}x${newHeight}", "I")
                startDrainThread()
            } catch (e: Exception) {
                log("Encoder reinit failed: ${e.javaClass.simpleName}: ${e.message}", "E")
                log(stackToString(e), "E")
                running.set(false)
                stopSelf()
                return
            } finally {
                skipFrames.set(false)
            }
        }
    }

    private fun releaseCodec() {
        val current = codec
        codec = null
        try {
            current?.stop()
        } catch (_: Throwable) {
        }
        try {
            current?.release()
        } catch (_: Throwable) {
        }
    }

    private inner class FrameAnalyzer : ImageAnalysis.Analyzer {
        private val basePtsUs = System.nanoTime() / 1_000L

        override fun analyze(image: ImageProxy) {
            try {
                if (skipFrames.get() || !running.get()) return

                val width = image.width
                val height = image.height
                if (width != encoderWidth || height != encoderHeight) {
                    reinitCodec(width, height)
                    if (width != encoderWidth || height != encoderHeight) return
                }

                val currentCodec = codec ?: return
                val nv12 = imageToNv12(image)
                val ptsUs = (System.nanoTime() / 1_000L) - basePtsUs
                val inputIndex = dequeueInputBuffer(currentCodec) ?: return
                val inputBuffer = currentCodec.getInputBuffer(inputIndex)
                if (inputBuffer == null) {
                    currentCodec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                    return
                }

                if (inputBuffer.capacity() < nv12.size) {
                    log("encoder input buffer too small: capacity=${inputBuffer.capacity()} frame=${nv12.size}", "E")
                    currentCodec.queueInputBuffer(inputIndex, 0, 0, ptsUs, 0)
                    return
                }

                inputBuffer.clear()
                inputBuffer.put(nv12)
                currentCodec.queueInputBuffer(inputIndex, 0, nv12.size, ptsUs, 0)
            } catch (e: Exception) {
                val message = "analyze: ${e.javaClass.simpleName}: ${e.message}"
                if (lastFrameError.getAndSet(message) != message) log(message, "E")
            } finally {
                try {
                    image.close()
                } catch (_: Throwable) {
                }
            }
        }

        private fun dequeueInputBuffer(currentCodec: MediaCodec): Int? {
            repeat(5) {
                val index = try {
                    currentCodec.dequeueInputBuffer(10_000)
                } catch (e: Exception) {
                    log("dequeueInputBuffer threw: ${e.message}", "E")
                    -1
                }
                if (index >= 0) return index
            }
            return null
        }
    }

    private fun imageToNv12(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val output = ByteArray(width * height * 3 / 2)
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        copyLumaPlane(yPlane, width, height, output)
        copyChromaPlanes(uPlane, vPlane, width, height, output, width * height)
        return output
    }

    private fun copyLumaPlane(plane: ImageProxy.PlaneProxy, width: Int, height: Int, output: ByteArray) {
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        var outOffset = 0

        if (pixelStride == 1) {
            for (row in 0 until height) {
                buffer.position(row * rowStride)
                buffer.get(output, outOffset, width)
                outOffset += width
            }
        } else {
            for (row in 0 until height) {
                val base = row * rowStride
                for (col in 0 until width) output[outOffset++] = buffer.get(base + col * pixelStride)
            }
        }
    }

    private fun copyChromaPlanes(
        uPlane: ImageProxy.PlaneProxy,
        vPlane: ImageProxy.PlaneProxy,
        width: Int,
        height: Int,
        output: ByteArray,
        startOffset: Int
    ) {
        val uBuffer = uPlane.buffer.duplicate()
        val vBuffer = vPlane.buffer.duplicate()
        val uRowStride = uPlane.rowStride
        val vRowStride = vPlane.rowStride
        val uPixelStride = uPlane.pixelStride
        val vPixelStride = vPlane.pixelStride
        val chromaWidth = width / 2
        val chromaHeight = height / 2
        var outOffset = startOffset

        for (row in 0 until chromaHeight) {
            val uBase = row * uRowStride
            val vBase = row * vRowStride
            for (col in 0 until chromaWidth) {
                output[outOffset++] = uBuffer.get(uBase + col * uPixelStride)
                output[outOffset++] = vBuffer.get(vBase + col * vPixelStride)
            }
        }
    }

    private fun drainLoop() {
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var configBuffers = 0

        while (running.get() && drainActive.get()) {
            val currentCodec = codec ?: break
            val index = try {
                currentCodec.dequeueOutputBuffer(info, 10_000)
            } catch (e: Exception) {
                if (running.get() && drainActive.get()) log("dequeueOutputBuffer threw: ${e.message}", "E")
                break
            }

            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    log("Output format changed: ${currentCodec.outputFormat}", "I")
                    extractCodecConfigFromFormat(currentCodec.outputFormat)
                }
                index >= 0 -> {
                    try {
                        if (info.size > 0) {
                            val data = readOutputBuffer(currentCodec, index, info)
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                rtsp?.setSP(data)
                                configBuffers++
                                log("SPS/PPS pushed, size=${data.size}", "I")
                            } else {
                                val keyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                                rtsp?.push(data, info.presentationTimeUs, keyFrame)
                                frames++
                                if (frames % 100 == 0) log("Pushed $frames frames", "I")
                            }
                        }
                    } catch (e: Exception) {
                        if (running.get() && drainActive.get()) {
                            log("output buffer handling failed: ${e.javaClass.simpleName}: ${e.message}", "E")
                        }
                    } finally {
                        try {
                            currentCodec.releaseOutputBuffer(index, false)
                        } catch (_: Throwable) {
                        }
                    }
                }
            }
        }
        log("Drain loop ended. frames=$frames cfg=$configBuffers", "I")
    }

    private fun readOutputBuffer(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo): ByteArray {
        val buffer = codec.getOutputBuffer(index) ?: return ByteArray(0)
        val safeOffset = info.offset.coerceIn(0, buffer.capacity())
        val safeLimit = (info.offset + info.size).coerceIn(safeOffset, buffer.capacity())
        buffer.position(safeOffset)
        buffer.limit(safeLimit)
        val data = ByteArray(buffer.remaining())
        buffer.get(data)
        return data
    }

    private fun extractCodecConfigFromFormat(format: MediaFormat) {
        try {
            if (format.containsKey("csd-0")) {
                format.getByteBuffer("csd-0")?.let { rtsp?.setSP(byteBufferToArray(it)) }
            }
            if (format.containsKey("csd-1")) {
                format.getByteBuffer("csd-1")?.let { rtsp?.setSP(byteBufferToArray(it)) }
            }
        } catch (e: Exception) {
            log("extract CSD failed: ${e.javaClass.simpleName}: ${e.message}", "W")
        }
    }

    private fun byteBufferToArray(buffer: ByteBuffer): ByteArray {
        val duplicate = buffer.duplicate()
        val data = ByteArray(duplicate.remaining())
        duplicate.get(data)
        return data
    }

    private fun log(message: String, level: String = "I") {
        val priority = when (level) {
            "E", "FATAL" -> Log.ERROR
            "W" -> Log.WARN
            else -> Log.INFO
        }
        Log.println(priority, "CamRTSP", message)
        try {
            appendPersistentLog(filesDir, level, message)
        } catch (_: Throwable) {
        }
        try {
            logSink?.invoke(message, level)
        } catch (_: Throwable) {
        }
    }

    private fun stackToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }
}
