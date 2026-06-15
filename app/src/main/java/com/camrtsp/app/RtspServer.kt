package com.camrtsp.app

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

class RtspServer(val w: Int, val h: Int, val fr: Int, val clk: Int = 90000) {
    companion object {
        const val PORT = 8554
    }

    private val run = AtomicBoolean(false)
    private var ss: ServerSocket? = null
    private val cli = ConcurrentHashMap<String, CS>()
    private var sps: ByteArray? = null
    private var pps: ByteArray? = null

    @Volatile
    var ok: Boolean = false

    fun start() {
        if (!run.compareAndSet(false, true)) return
        thread(name = "RtspAccept", isDaemon = true) {
            try { al() } catch (e: Exception) { Log.e("RtspSrv", "accept thread: ${e.javaClass.simpleName}: ${e.message}", e) }
        }
    }

    fun stop() {
        run.set(false)
        try { ss?.close() } catch (_: Throwable) {}
        try { cli.values.toList().forEach { it.close() } } catch (_: Throwable) {}
        try { cli.clear() } catch (_: Throwable) {}
    }

    fun setSP(c: ByteArray) {
        if (ok) return
        try {
            var i = 0
            while (i < c.size - 4) {
                if (c[i].toInt() == 0 && c[i + 1].toInt() == 0 && c[i + 2].toInt() == 1) {
                    val t = c[i + 3].toInt() and 0x1F
                    if (t == 7) {
                        var e = i + 4
                        while (e < c.size - 3) {
                            if (c[e].toInt() == 0 && c[e + 1].toInt() == 0 && c[e + 2].toInt() == 1) break
                            e++
                        }
                        sps = c.copyOfRange(i + 3, e)
                        i = e
                        continue
                    } else if (t == 8) {
                        var e = i + 4
                        while (e < c.size - 3) {
                            if (c[e].toInt() == 0 && c[e + 1].toInt() == 0 && c[e + 2].toInt() == 1) break
                            e++
                        }
                        pps = c.copyOfRange(i + 3, e)
                        ok = true
                        Log.i("RtspSrv", "SPS=${sps?.size} PPS=${pps?.size} ok=true")
                        return
                    }
                }
                i++
            }
        } catch (e: Exception) { Log.e("RtspSrv", "setSP: ${e.message}", e) }
    }

    fun push(au: ByteArray, pts: Long, k: Boolean) {
        if (!run.get()) return
        try {
            val ns = mutableListOf<ByteArray>()
            var s = -1
            var i = 0
            while (i < au.size - 3) {
                if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                    if (s >= 0) ns.add(au.copyOfRange(s, i))
                    s = i + 3
                    i += 3
                } else {
                    i++
                }
            }
            if (s >= 0 && s < au.size) ns.add(au.copyOfRange(s, au.size))
            // Snapshot to avoid CME if a client disconnects mid-iteration
            val snapshot = cli.values.toList()
            for (c in snapshot) {
                try { c.send(ns, pts, k) } catch (e: Exception) { Log.w("RtspSrv", "push to client: ${e.message}") }
            }
        } catch (e: Exception) { Log.e("RtspSrv", "push: ${e.message}", e) }
    }

    private fun al() {
        val s: ServerSocket
        try { s = ServerSocket(PORT) } catch (e: Exception) { Log.e("RtspSrv", "ServerSocket: ${e.message}"); return }
        ss = s
        Log.i("RtspSrv", "RTSP listening on $PORT")
        while (run.get()) {
            val c: Socket
            try { c = s.accept() } catch (e: Exception) { break }
            thread(name = "RtspCli", isDaemon = true) {
                try { hc(c) } catch (e: Exception) { Log.e("RtspSrv", "client thread: ${e.javaClass.simpleName}: ${e.message}", e) }
            }
        }
    }

    private fun hc(s: Socket) {
        try {
            s.soTimeout = 60000
            val inp = BufferedReader(InputStreamReader(s.getInputStream()))
            val out = s.getOutputStream()
            val cs = CS(s, out)
            try {
                while (run.get() && !s.isClosed) {
                    val l: String?
                    try { l = inp.readLine() } catch (e: Exception) { break }
                    if (l == null) break
                    if (l.isBlank()) continue
                    val p = l.split(" ", limit = 3)
                    if (p.size < 2) continue
                    val m = p[0]
                    val c = p[1]
                    val u = p.getOrNull(2) ?: "/live"
                    Log.i("RtspSrv", "← $m $c $u")
                    try {
                        when (m) {
                            "OPTIONS" -> sr(out, c, "200 OK", mapOf(
                                "CSeq" to c,
                                "Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN",
                                "Server" to "CamRTSP/1.0"
                            ), null)
                            "DESCRIBE" -> {
                                if (!ok) {
                                    sr(out, c, "503", mapOf("CSeq" to c), null)
                                    continue
                                }
                                sr(out, c, "200 OK", mapOf(
                                    "CSeq" to c,
                                    "Content-Type" to "application/sdp",
                                    "Server" to "CamRTSP/1.0"
                                ), bsp())
                            }
                            "SETUP" -> {
                                val id = Random.nextInt(0x7FFFFFFF).toString()
                                cs.sid = id
                                sr(out, c, "200 OK", mapOf(
                                    "CSeq" to c,
                                    "Session" to "$id;timeout=60",
                                    "Transport" to "RTP/AVP/TCP;interleaved=0-1",
                                    "Server" to "CamRTSP/1.0"
                                ), null)
                                cli[id] = cs
                            }
                            "PLAY" -> {
                                cs.playing = true
                                sr(out, c, "200 OK", mapOf(
                                    "CSeq" to c,
                                    "Session" to (cs.sid ?: "0"),
                                    "RTP-Info" to "url=$u;seq=0;rtptime=0",
                                    "Server" to "CamRTSP/1.0"
                                ), null)
                            }
                            "TEARDOWN" -> {
                                sr(out, c, "200 OK", mapOf(
                                    "CSeq" to c,
                                    "Session" to (cs.sid ?: "0"),
                                    "Server" to "CamRTSP/1.0"
                                ), null)
                                cs.close()
                                cli.remove(cs.sid)
                            }
                            else -> sr(out, c, "501", mapOf("CSeq" to c), null)
                        }
                    } catch (e: Exception) {
                        Log.w("RtspSrv", "handle $m: ${e.javaClass.simpleName}: ${e.message}")
                    }
                }
            } finally {
                try { cs.close() } catch (_: Throwable) {}
                try { cli.remove(cs.sid) } catch (_: Throwable) {}
            }
        } catch (e: Exception) {
            Log.e("RtspSrv", "client handler: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }

    private fun sr(o: OutputStream, c: String, st: String, h: Map<String, String>, b: String?) {
        try {
            val sb = StringBuilder()
            sb.append("RTSP/1.0 ").append(st).append("\r\n")
            h.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
            if (b != null) sb.append("Content-Length: ").append(b.length).append("\r\n")
            sb.append("\r\n")
            if (b != null) sb.append(b)
            o.write(sb.toString().toByteArray())
            o.flush()
            Log.i("RtspSrv", "→ RTSP/1.0 $st [CSeq=$c]")
        } catch (e: Exception) {
            Log.w("RtspSrv", "send RTSP: ${e.message}")
        }
    }

    private fun bsp(): String {
        return try {
            val spsB = sps ?: return ""
            val ppsB = pps ?: return ""
            val sb64 = Base64.encodeToString(spsB, Base64.NO_WRAP)
            val pb64 = Base64.encodeToString(ppsB, Base64.NO_WRAP)
            val pl = String.format("%02X%02X%02X", spsB[1].toInt() and 0xFF, spsB[2].toInt() and 0xFF, spsB[3].toInt() and 0xFF)
            "v=0\r\n" +
                "o=- 0 0 IN IP4 127.0.0.1\r\n" +
                "s=CamRTSP\r\n" +
                "c=IN IP4 0.0.0.0\r\n" +
                "t=0 0\r\n" +
                "m=video 0 TCP/RTP/AVP 96\r\n" +
                "a=rtpmap:96 H264/90000\r\n" +
                "a=fmtp:96 profile-level-id=$pl;sprop-parameter-sets=$sb64,$pb64;packetization-mode=1\r\n" +
                "a=control:track1\r\n" +
                "a=framerate:$fr\r\n"
        } catch (e: Exception) {
            Log.e("RtspSrv", "bsp: ${e.message}", e); ""
        }
    }
}

class CS(val sock: Socket, private val out: OutputStream) {
    @Volatile var sid: String? = null
    @Volatile var cseq: String? = null

    @Volatile
    var playing = false

    private var seq: Int = Random.nextInt(0, 0xFFFF)
    private val ssrc: Int = Random.nextInt()
    private val lk = Any()

    fun send(ns: List<ByteArray>, pts: Long, k: Boolean) {
        if (!playing) return
        val ts = pts * 90L
        try {
            synchronized(lk) {
                for (n in ns) {
                    if (n.isEmpty()) continue
                    val t = n[0].toInt() and 0x1F
                    if (n.size <= 1400) s1(n, ts) else fa(n, ts, t)
                }
            }
        } catch (e: Exception) {
            Log.w("RtspSrv", "send: ${e.message}")
            playing = false
        }
    }

    private fun s1(n: ByteArray, ts: Long) {
        val p = ByteArray(12 + n.size)
        p[0] = 0x80.toByte()
        p[1] = 96.toByte()
        p[2] = ((seq shr 8) and 0xFF).toByte()
        p[3] = (seq and 0xFF).toByte()
        seq = (seq + 1) and 0xFFFF
        p[4] = ((ts shr 24) and 0xFF).toByte()
        p[5] = ((ts shr 16) and 0xFF).toByte()
        p[6] = ((ts shr 8) and 0xFF).toByte()
        p[7] = (ts and 0xFF).toByte()
        p[8] = ((ssrc shr 24) and 0xFF).toByte()
        p[9] = ((ssrc shr 16) and 0xFF).toByte()
        p[10] = ((ssrc shr 8) and 0xFF).toByte()
        p[11] = (ssrc and 0xFF).toByte()
        System.arraycopy(n, 0, p, 12, n.size)
        si(p)
    }

    private fun fa(n: ByteArray, ts: Long, t: Int) {
        val fi = 0x60 or (n[0].toInt() and 0x80)
        var o = 1
        var f = true
        while (o < n.size) {
            val ch = minOf(1400, n.size - o)
            val lst = (o + ch) >= n.size
            val p = ByteArray(12 + 2 + ch)
            p[0] = 0x80.toByte()
            p[1] = 96.toByte()
            p[2] = ((seq shr 8) and 0xFF).toByte()
            p[3] = (seq and 0xFF).toByte()
            seq = (seq + 1) and 0xFFFF
            p[4] = ((ts shr 24) and 0xFF).toByte()
            p[5] = ((ts shr 16) and 0xFF).toByte()
            p[6] = ((ts shr 8) and 0xFF).toByte()
            p[7] = (ts and 0xFF).toByte()
            p[8] = ((ssrc shr 24) and 0xFF).toByte()
            p[9] = ((ssrc shr 16) and 0xFF).toByte()
            p[10] = ((ssrc shr 8) and 0xFF).toByte()
            p[11] = (ssrc and 0xFF).toByte()
            p[12] = fi.toByte()
            p[13] = (if (f) 0x80 or t else if (lst) 0x40 or t else t).toByte()
            System.arraycopy(n, o, p, 14, ch)
            si(p)
            f = false
            o += ch
        }
    }

    private fun si(p: ByteArray) {
        try {
            synchronized(lk) {
                out.write(0x24)
                out.write(0x00)
                out.write(((p.size shr 8) and 0xFF))
                out.write((p.size and 0xFF))
                out.write(p)
                out.flush()
            }
        } catch (e: Exception) {
            Log.w("RtspSrv", "RTP write: ${e.message}")
            playing = false
        }
    }

    fun close() {
        playing = false
        try { sock.close() } catch (_: Throwable) {}
    }
}
