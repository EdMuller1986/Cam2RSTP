package com.camrtsp.app

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
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
        var isRunning = false
        @Volatile var logSink: ((String, String) -> Unit)? = null
        fun attachLog(sink: (String, String) -> Unit) { logSink = sink }
    }

    private var rtsp: RtspServer? = null
    @Volatile private var codec: MediaCodec? = null
    @Volatile private var drainThread: Thread? = null
    @Volatile private var encW: Int = 0
    @Volatile private var encH: Int = 0
    @Volatile private var frameSize: Int = 0
    private val FR = 25
    private val cf: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private val analyzerExec = Executors.newSingleThreadExecutor()
    private val skipFrames = AtomicBoolean(true)
    private val codecReinitialized = AtomicBoolean(false)
    private val reinitLock = Any()
    private val lastFrameError = AtomicReference<String?>(null)

    private fun log(s: String, level: String = "I") {
        Log.println(
            when (level) { "E" -> Log.ERROR; "W" -> Log.WARN; else -> Log.INFO },
            "CamRTSP", s
        )
        try { logSink?.invoke(s, level) } catch (_: Throwable) {}
        // Persist to file (survives crash)
        try {
            val f = File(filesDir, "camrtsp.log")
            f.appendText("$level ${System.currentTimeMillis()}: $s\n")
        } catch (_: Throwable) {}
    }

    private fun stackToString(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString()
    }

    override fun onCreate() {
        super.onCreate()
        installCrashHandler()
        log("Service onCreate", "I")
        try { fg() } catch (e: Exception) {
            log("fg() failed: ${e.javaClass.simpleName}: ${e.message}", "E")
            log(stackToString(e), "E")
        }
    }

    private fun installCrashHandler() {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sw = StringWriter()
                e.printStackTrace(PrintWriter(sw))
                val crash = "FATAL on thread ${t.name}: ${e.javaClass.simpleName}: ${e.message}\n$sw"
                Log.e("CamRTSP", crash)
                try {
                    File(filesDir, "camrtsp.log").appendText("FATAL ${System.currentTimeMillis()}: $crash\n")
                } catch (_: Throwable) {}
                // Send broadcast so Activity can show it
                try {
                    val i = Intent("com.camrtsp.app.CRASH")
                        .putExtra("crash", crash)
                        .setPackage(packageName)
                    sendBroadcast(i)
                } catch (_: Throwable) {}
            } catch (_: Throwable) {}
            prev?.uncaughtException(t, e)
        }
    }

    override fun onStartCommand(i: Intent?, f: Int, s: Int): Int {
        super.onStartCommand(i, f, s)
        if (isRunning) { log("Already running", "W"); return START_STICKY }
        isRunning = true
        log("onStartCommand: launching boot thread", "I")
        Thread({ ss() }, "RtspBoot").start()
        return START_STICKY
    }

    override fun onDestroy() {
        log("Service onDestroy", "I")
        isRunning = false
        try { drainThread?.join(500) } catch (_: Throwable) {}
        try { codec?.stop() } catch (_: Throwable) {}
        try { codec?.release() } catch (_: Throwable) {}
        try { rtsp?.stop() } catch (_: Throwable) {}
        try { analyzerExec.shutdown() } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(i: Intent): IBinder? { super.onBind(i); return null }

    override fun onTaskRemoved(rootIntent: Intent?) {
        log("onTaskRemoved: keeping service alive", "I")
    }

    private fun fg() {
        val cid = "rtsp_ch"
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel(cid, "RTSP", NotificationManager.IMPORTANCE_LOW))
        }
        val n = NotificationCompat.Builder(this, cid)
            .setContentTitle("CamRTSP")
            .setContentText("Broadcasting on Wi-Fi")
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setOngoing(true).build()
        startForeground(1, n)
    }

    private fun ss() {
        val W = 1280; val H = 720
        try {
            rtsp = RtspServer(W, H, FR).also {
                it.start()
                log("RtspServer started on port ${RtspServer.PORT}", "I")
            }
        } catch (e: Exception) {
            log("RtspServer.start() failed: ${e.javaClass.simpleName}: ${e.message}", "E")
            log(stackToString(e), "E")
            isRunning = false
            stopSelf()
            return
        }

        try {
            encW = W; encH = H; frameSize = W * H * 3 / 2
            codec = createCodec(W, H)
            log("Encoder ready: ${W}x${H} fmt=NV12 size=$frameSize", "I")
        } catch (e: Exception) {
            log("Encoder init failed: ${e.javaClass.simpleName}: ${e.message}", "E")
            log(stackToString(e), "E")
            isRunning = false
            stopSelf()
            return
        }

        startDrainThread()

        try {
            val fut = ProcessCameraProvider.getInstance(this)
            fut.addListener({
                try {
                    val p = fut.get()
                    val an = ImageAnalysis.Builder()
                        .setTargetResolution(Size(encW, encH))
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply { setAnalyzer(analyzerExec, Y()) }
                    val pr = Preview.Builder().build()
                    p.unbindAll()
                    p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pr, an)
                    skipFrames.set(false)
                    log("Camera bound (target=${encW}x${encH})", "I")
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

    private fun createCodec(w: Int, h: Int): MediaCodec {
        val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, FR)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT, cf)
        }
        return MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            start()
        }
    }

    private fun startDrainThread() {
        try {
            drainThread = Thread({ dl() }, "RtpDrain").also { it.start() }
            log("Drain thread started", "I")
        } catch (e: Exception) {
            log("Drain thread start failed: ${e}", "E")
        }
    }

    private fun reinitCodec(newW: Int, newH: Int) {
        if (codecReinitialized.get()) return
        synchronized(reinitLock) {
            if (codecReinitialized.get()) return
            codecReinitialized.set(true)
            log("Reinit encoder ${encW}x${encH} → ${newW}x${newH}", "W")
            isRunning = false
            try { drainThread?.join(1000) } catch (_: Throwable) {}
            try { codec?.stop() } catch (_: Throwable) {}
            try { codec?.release() } catch (_: Throwable) {}
            rtsp?.let {
                try {
                    val field = RtspServer::class.java.getDeclaredField("ok")
                    field.isAccessible = true
                    field.setBoolean(it, false)
                    val fSps = RtspServer::class.java.getDeclaredField("sps"); fSps.isAccessible = true; fSps.set(it, null)
                    val fPps = RtspServer::class.java.getDeclaredField("pps"); fPps.isAccessible = true; fPps.set(it, null)
                } catch (e: Exception) { log("reinit: reflect failed: ${e.message}", "E") }
            }
            try {
                encW = newW; encH = newH; frameSize = newW * newH * 3 / 2
                codec = createCodec(newW, newH)
                log("Encoder reinit OK: ${newW}x${newH}", "I")
            } catch (e: Exception) {
                log("Encoder reinit FAILED: ${e.message}", "E")
                return
            }
            isRunning = true
            startDrainThread()
        }
    }

    private inner class Y : ImageAnalysis.Analyzer {
        private val pb = System.nanoTime() / 1000L
        override fun analyze(im: ImageProxy) {
            try {
                if (skipFrames.get() || !isRunning) return
                val w = im.width; val h = im.height
                if (w != encW || h != encH) {
                    reinitCodec(w, h)
                    if (w != encW || h != encH) return
                }
                val cd = codec ?: return
                val y = im.planes[0]
                val u = im.planes[1]
                val v = im.planes[2]
                val yr = y.rowStride; val ur = u.rowStride; val vr = v.rowStride
                val yp = y.pixelStride; val up = u.pixelStride; val vp = v.pixelStride
                val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
                val o = ByteArray(frameSize)
                if (yp == 1) {
                    var off = 0
                    for (r in 0 until h) {
                        yb.position(r * yr)
                        yb.get(o, off, w)
                        off += w
                    }
                } else {
                    var off = 0
                    for (r in 0 until h) {
                        val base = r * yr
                        for (c in 0 until w) o[off++] = yb.get(base + c * yp)
                    }
                }
                val uh = h / 2; val uw = w / 2
                var off = w * h
                if (up == 1 && vp == 1) {
                    for (r in 0 until uh) {
                        val ub2 = r * ur
                        val vb2 = r * vr
                        for (c in 0 until uw) {
                            o[off++] = ub.get(ub2 + c)
                            o[off++] = vb.get(vb2 + c)
                        }
                    }
                } else {
                    for (r in 0 until uh) {
                        val ub2 = r * ur
                        val vb2 = r * vr
                        for (c in 0 until uw) {
                            o[off++] = ub.get(ub2 + c * up)
                            o[off++] = vb.get(vb2 + c * vp)
                        }
                    }
                }
                val pts = (System.nanoTime() / 1000L) - pb
                var idx = -1
                var attempts = 0
                while (idx < 0 && attempts < 5 && isRunning) {
                    idx = try { cd.dequeueInputBuffer(10000) } catch (e: Exception) {
                        log("dequeueInputBuffer threw: ${e.message}", "E"); -1
                    }
                    if (idx < 0) attempts++
                }
                if (idx < 0) return
                val ib: ByteBuffer? = try { cd.getInputBuffer(idx) } catch (e: Exception) {
                    log("getInputBuffer threw: ${e.message}", "E"); null
                } ?: return
                val cap = ib.capacity()
                val toWrite = if (o.size <= cap) o.size else cap
                ib.clear()
                ib.put(o, 0, toWrite)
                try { cd.queueInputBuffer(idx, 0, toWrite, pts, 0) } catch (e: Exception) {
                    log("queueInputBuffer threw: ${e.message}", "E")
                }
            } catch (e: Exception) {
                val msg = "analyze: ${e.javaClass.simpleName}: ${e.message}"
                if (lastFrameError.get() != msg) { log(msg, "E"); lastFrameError.set(msg) }
            } finally {
                try { im.close() } catch (_: Throwable) {}
            }
        }
    }

    private fun dl() {
        val info = MediaCodec.BufferInfo()
        var frames = 0
        var cfg = 0
        while (isRunning) {
            val cd = codec ?: break
            val idx = try { cd.dequeueOutputBuffer(info, 10000) } catch (e: Exception) {
                log("dequeueOutputBuffer threw: ${e.message}", "E"); break
            }
            if (idx == MediaCodec.INFO_TRY_AGAIN_LATER) {
                // no output
            } else if (idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                log("Output format changed: ${cd.outputFormat}", "I")
            } else if (idx >= 0) {
                val b: ByteBuffer? = try { cd.getOutputBuffer(idx) } catch (e: Exception) {
                    log("getOutputBuffer threw: ${e.message}", "E"); null
                }
                if (b != null && info.size > 0) {
                    val d = ByteArray(b.remaining())
                    try { b.get(d) } catch (e: Exception) { log("read output buffer: ${e.message}", "E"); continue }
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        try { rtsp?.setSP(d); cfg++ ; log("SPS/PPS pushed, size=${d.size}", "I") }
                        catch (e: Exception) { log("setSP failed: ${e.message}", "E") }
                    } else {
                        try { rtsp?.push(d, info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) }
                        catch (e: Exception) { log("rtsp.push failed: ${e.message}", "E") }
                        frames++
                        if (frames % 100 == 0) log("Pushed $frames frames", "I")
                    }
                }
                try { cd.releaseOutputBuffer(idx, false) } catch (_: Throwable) {}
            }
        }
        log("Drain loop ended. frames=$frames cfg=$cfg", "I")
    }
}
