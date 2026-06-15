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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

class RtspServerService : LifecycleService() {

    companion object {
        var isRunning = false
        @Volatile var logSink: ((String, String) -> Unit)? = null
        fun attachLog(sink: (String, String) -> Unit) { logSink = sink }
    }

    private var rtsp: RtspServer? = null
    private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    @Volatile private var cf: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    private val analyzerExec = Executors.newSingleThreadExecutor()
    private val lastFrameError = AtomicReference<String?>(null)

    private fun log(s: String, level: String = "I") {
        Log.println(
            when (level) { "E" -> Log.ERROR; "W" -> Log.WARN; else -> Log.INFO },
            "CamRTSP", s
        )
        try { logSink?.invoke(s, level) } catch (_: Throwable) {}
    }

    private fun stackToString(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    override fun onCreate() {
        super.onCreate()
        log("Service onCreate", "I")
        try { fg() } catch (e: Exception) {
            log("fg() failed: ${e.javaClass.simpleName}: ${e.message}", "E")
            log(stackToString(e), "E")
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
        val W = 1280; val H = 720; val FR = 25
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
            val fmt = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, W, H).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, 2_500_000)
                setInteger(MediaFormat.KEY_FRAME_RATE, FR)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                setInteger(MediaFormat.KEY_COLOR_FORMAT,
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            }
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
                configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                start()
            }
            cf = dcf(codec!!)
            log("Encoder ready, colorFormat=0x${Integer.toHexString(cf)}", "I")
        } catch (e: Exception) {
            log("Encoder init failed: ${e.javaClass.simpleName}: ${e.message}", "E")
            log(stackToString(e), "E")
            isRunning = false
            stopSelf()
            return
        }

        try {
            drainThread = Thread({ dl() }, "RtpDrain").also { it.start() }
            log("Drain thread started", "I")
        } catch (e: Exception) {
            log("Drain thread start failed: ${e}", "E")
        }

        try {
            val fut = ProcessCameraProvider.getInstance(this)
            fut.addListener({
                try {
                    val p = fut.get()
                    val an = ImageAnalysis.Builder()
                        .setTargetResolution(Size(W, H))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply { setAnalyzer(analyzerExec, Y()) }
                    val pr = Preview.Builder().build()
                    p.unbindAll()
                    p.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, pr, an)
                    log("Camera bound: ${W}x${H} @${FR}fps", "I")
                } catch (e: Exception) {
                    log("CameraX bind failed: ${e.javaClass.simpleName}: ${e.message}", "E")
                    log(stackToString(e), "E")
                    lastFrameError.set("bind: ${e.message}")
                }
            }, ContextCompat.getMainExecutor(this))
        } catch (e: Exception) {
            log("getCameraProvider failed: ${e.javaClass.simpleName}: ${e.message}", "E")
            log(stackToString(e), "E")
        }
    }

    private fun dcf(c: MediaCodec): Int {
        val cap = c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val supported = cap.colorFormats.toList()
        log("Encoder supports color formats: ${supported.map { "0x${Integer.toHexString(it)}" }}", "I")
        for (x in supported) {
            if (x == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return x
        }
        for (x in supported) {
            if (x == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return x
        }
        for (x in supported) {
            if (x == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) return x
        }
        return supported.first()
    }

    private inner class Y : ImageAnalysis.Analyzer {
        private val pb = System.nanoTime() / 1000L
        override fun analyze(im: ImageProxy) {
            try {
                if (!isRunning) return
                val cd = codec ?: return
                val y = im.planes[0]
                val u = im.planes[1]
                val v = im.planes[2]
                val w = im.width; val h = im.height
                val yr = y.rowStride; val ur = u.rowStride; val vr = v.rowStride
                val yp = y.pixelStride; val up = u.pixelStride; val vp = v.pixelStride
                val yb = y.buffer; val ub = u.buffer; val vb = v.buffer
                val fs = w * h * 3 / 2
                val o = ByteArray(fs)
                if (yp == 1) {
                    var off = 0
                    for (r in 0 until h) { yb.position(r * yr); yb.get(o, off, w); off += w }
                } else {
                    var off = 0
                    for (r in 0 until h) {
                        val b = r * yr
                        for (c in 0 until w) o[off++] = yb.get(b + c * yp)
                    }
                }
                val uh = h / 2; val uw = w / 2
                if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) {
                    var off = w * h
                    for (r in 0 until uh) {
                        val ub2 = r * ur
                        val vb2 = r * vr
                        for (c in 0 until uw) {
                            o[off++] = ub.get(ub2 + c * up)
                            o[off++] = vb.get(vb2 + c * vp)
                        }
                    }
                } else if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                    var uo = w * h
                    var vo = w * h + uw * uh
                    for (r in 0 until uh) {
                        val ub2 = r * ur
                        for (c in 0 until uw) o[uo++] = ub.get(ub2 + c * up)
                    }
                    for (r in 0 until uh) {
                        val vb2 = r * vr
                        for (c in 0 until uw) o[vo++] = vb.get(vb2 + c * vp)
                    }
                } else if (cf == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) {
                    var off = w * h
                    for (r in 0 until uh) {
                        val ub2 = r * ur
                        val vb2 = r * vr
                        for (c in 0 until uw) {
                            o[off++] = ub.get(ub2 + c * up)
                            o[off++] = vb.get(vb2 + c * vp)
                        }
                    }
                } else {
                    val err = "Unsupported colorFormat=0x${Integer.toHexString(cf)}"
                    if (lastFrameError.get() != err) { log(err, "E"); lastFrameError.set(err) }
                    return
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
                val ib: ByteBuffer = try { cd.getInputBuffer(idx) } catch (e: Exception) {
                    log("getInputBuffer threw: ${e.message}", "E"); null
                } ?: return
                ib.clear()
                val cap = ib.capacity()
                val toWrite = if (o.size <= cap) o.size else cap
                if (toWrite < o.size) {
                    val msg = "Truncating frame: o=${o.size} cap=$cap (colorFormat=0x${Integer.toHexString(cf)})"
                    if (lastFrameError.get() != msg) { log(msg, "W"); lastFrameError.set(msg) }
                }
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
        val cd = codec ?: return
        while (isRunning) {
            val idx = try { cd.dequeueOutputBuffer(info, 10000) } catch (e: Exception) {
                log("dequeueOutputBuffer threw: ${e.message}", "E"); break
            }
            if (idx >= 0) {
                val b: ByteBuffer = try { cd.getOutputBuffer(idx) } catch (e: Exception) {
                    log("getOutputBuffer threw: ${e.message}", "E"); null
                } ?: continue
                val d = ByteArray(b.remaining())
                try { b.get(d) } catch (e: Exception) { log("read output buffer: ${e.message}", "E"); continue }
                try { cd.releaseOutputBuffer(idx, false) } catch (_: Throwable) {}
                if (info.size > 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        try { rtsp?.setSP(d); log("SPS/PPS pushed, size=${d.size}", "I") }
                        catch (e: Exception) { log("setSP failed: ${e.message}", "E") }
                    } else {
                        try { rtsp?.push(d, info.presentationTimeUs,
                            info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) }
                        catch (e: Exception) { log("rtsp.push failed: ${e.message}", "E") }
                    }
                }
            }
        }
    }
}
