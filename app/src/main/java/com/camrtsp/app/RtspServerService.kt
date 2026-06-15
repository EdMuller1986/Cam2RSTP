package com.camrtsp.app
import android.app.NotificationChannel; import android.app.NotificationManager
import android.content.Intent; import android.media.MediaCodec; import android.media.MediaCodecInfo; import android.media.MediaFormat
import android.os.Build; import android.os.IBinder; import android.util.Log
import androidx.camera.core.*; import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat; import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.nio.ByteBuffer

class RtspServerService : LifecycleService() {
    companion object { var isRunning = false }
    private var rtsp: RtspServer? = null; private var codec: MediaCodec? = null
    private var drainThread: Thread? = null
    @Volatile private var cf: Int = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
    override fun onCreate() { super.onCreate(); fg() }
    override fun onStartCommand(i:Intent?, f:Int, s:Int): Int { super.onStartCommand(i,f,s)
        if (isRunning) return START_STICKY; isRunning=true; Thread({ss()},"RtspBoot").start(); return START_STICKY }
    override fun onDestroy() { isRunning=false; try{drainThread?.join(500)}catch(_:Throwable){}
        try{codec?.stop()}catch(_:Throwable){}; try{codec?.release()}catch(_:Throwable){}; try{rtsp?.stop()}catch(_:Throwable){}; super.onDestroy() }
    override fun onBind(i:Intent): IBinder? { super.onBind(i); return null }
    private fun fg() { val cid="rtsp_ch"; val nm=getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT>=26) nm.createNotificationChannel(NotificationChannel(cid,"RTSP",NotificationManager.IMPORTANCE_LOW))
        val n=NotificationCompat.Builder(this,cid).setContentTitle("CamRTSP").setContentText("Broadcasting on Wi-Fi")
            .setSmallIcon(android.R.drawable.presence_video_online).setOngoing(true).build()
        startForeground(1,n) }
    private fun ss() { val W=1280; val H=720; val FR=25; rtsp=RtspServer(W,H,FR).also{it.start()}
        val fmt=MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,W,H).apply {
            setInteger(MediaFormat.KEY_BIT_RATE,2_500_000); setInteger(MediaFormat.KEY_FRAME_RATE,FR)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1)
            setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) }
        codec=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply { configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE); start() }
        cf=dcf(codec!!); Log.i("RtspSrv","colorFormat=$cf")
        drainThread=Thread({dl()},"RtpDrain").also{it.start()}
        val fut=ProcessCameraProvider.getInstance(this); fut.addListener({
            val p=fut.get(); val an=ImageAnalysis.Builder().setTargetResolution(android.util.Size(W,H))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build().apply { setAnalyzer(ContextCompat.getMainExecutor(this@RtspServerService),Y()) }
            val pr=Preview.Builder().build()
            try { p.unbindAll(); p.bindToLifecycle(this,CameraSelector.DEFAULT_BACK_CAMERA,pr,an) } catch(e:Exception){Log.e("RtspSrv","bind",e)} }
        ,ContextCompat.getMainExecutor(this)) }
    private fun dcf(c:MediaCodec): Int { val cap=c.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        for (x in cap.colorFormats) if (x==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) return x
        for (x in cap.colorFormats) if (x==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) return x
        return cap.colorFormats[0] }
    private inner class Y : ImageAnalysis.Analyzer { private val pb=System.nanoTime()/1000L
        override fun analyze(im:ImageProxy) { try { if (!isRunning) return; val cd=codec?:return
            val y=im.planes[0]; val u=im.planes[1]; val v=im.planes[2]; val w=im.width; val h=im.height
            val yr=y.rowStride; val ur=u.rowStride; val vr=v.rowStride; val yp=y.pixelStride; val up=u.pixelStride; val vp=v.pixelStride
            val yb=y.buffer; val ub=u.buffer; val vb=v.buffer; val fs=w*h*3/2; val o=ByteArray(fs)
            if (yp==1) { var off=0; for (r in 0 until h) { yb.position(r*yr); yb.get(o,off,w); off+=w } }
            else { var off=0; for (r in 0 until h) { val b=r*yr; for (c in 0 until w) o[off++]=yb.get(b+c*yp) } }
            val uh=h/2; val uw=w/2
            if (cf==MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) { var off=w*h
                for (r in 0 until uh) { val ub2=r*ur; val vb2=r*vr; for (c in 0 until uw) { o[off++]=ub.get(ub2+c*up); o[off++]=vb.get(vb2+c*vp) } } }
            else { var uo=w*h; var vo=w*h+uw*uh
                for (r in 0 until uh) { val ub2=r*ur; for (c in 0 until uw) o[uo++]=ub.get(ub2+c*up) }
                for (r in 0 until uh) { val vb2=r*vr; for (c in 0 until uw) o[vo++]=vb.get(vb2+c*vp) } }
            val pts=(System.nanoTime()/1000L)-pb; var idx:Int; while(true){ idx=cd.dequeueInputBuffer(10000); if(idx>=0) break; if(!isRunning) return }
            val ib:ByteBuffer=cd.getInputBuffer(idx)?:return; ib.clear(); ib.put(o); cd.queueInputBuffer(idx,0,fs,pts,0)
        } catch(e:Exception){Log.w("RtspSrv","yuv",e)} finally{im.close()} } }
    private fun dl() { val info=MediaCodec.BufferInfo(); val cd=codec?:return
        while (isRunning) { val idx=try{cd.dequeueOutputBuffer(info,10000)}catch(e:Exception){break}
            if (idx>=0) { val b:ByteBuffer=cd.getOutputBuffer(idx)?:continue; val d=ByteArray(b.remaining()); b.get(d); cd.releaseOutputBuffer(idx,false)
                if (info.size>0) { if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) rtsp?.setSP(d) else rtsp?.push(d,info.presentationTimeUs,info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0) } } } }
}
