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

/**
 * Minimal RTSP/RTP server. Serves a single H.264 video track.
 * SDP is published in DESCRIBE, RTP frames are pushed in [push] after SPS/PPS arrive.
 */
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
    private var ok = false

    fun start() {
        if (!run.compareAndSet(false, true)) return
        thread(name = "RtspAccept") { al() }
    }

    fun stop() {
        run.set(false)
        try { ss?.close() } catch (_: Throwable) {}
        cli.values.forEach { it.close() }
        cli.clear()
    }

    /** Parse codec config (SPS + PPS) from MediaCodec CSD-0. */
    fun setSP(c: ByteArray) {
        if (ok) return
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
                    Log.i("RtspSrv", "SPS/PPS parsed, ok=true")
                    return
                }
            }
            i++
        }
    }

    /** Push a single access unit (one or more NALs) to all PLAYING clients. */
    fun push(au: ByteArray, pts: Long, k: Boolean) {
        if (!run.get()) return
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
        cli.values.forEach {
            try { it.send(ns, pts, k) } catch (_: Exception) {}
        }
    }

    private fun al() {
        val s = try { ServerSocket(PORT) } catch (e: Exception) { return }
        ss = s
        Log.i("RtspSrv", "RTSP listening on $PORT")
        while (run.get()) {
            val c = try { s.accept() } catch (e: Exception) { break }
            thread(name = "RtspCli") { hc(c) }
        }
    }

    private fun hc(s: Socket) {
        s.soTimeout = 60000
        val inp = BufferedReader(InputStreamReader(s.getInputStream()))
        val out = s.getOutputStream()
        val cs = CS(s, out)
        try {
            while (run.get() && !s.isClosed) {
                val l = try { inp.readLine() } catch (e: Exception) { break } ?: break
                if (l.isBlank()) continue
                val p = l.split(" ", limit = 3)
                if (p.size < 2) continue
                val m = p[0]
                val c = p[1]
                val u = p.getOrNull(2) ?: "/live"
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
                        cs.cseq = c
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
                            "Session" to cs.sid!!,
                            "RTP-Info" to "url=$u;seq=0;rtptime=0",
                            "Server" to "CamRTSP/1.0"
                        ), null)
                    }
                    "TEARDOWN" -> {
                        sr(out, c, "200 OK", mapOf(
                            "CSeq" to c,
                            "Session" to cs.sid!!,
                            "Server" to "CamRTSP/1.0"
                        ), null)
                        cs.close()
                        cli.remove(cs.sid)
                    }
                    else -> sr(out, c, "501", mapOf("CSeq" to c), null)
                }
            }
        } catch (_: Exception) {
        } finally {
            cs.close()
            cli.remove(cs.sid)
        }
    }

    private fun sr(o: OutputStream, c: String, st: String, h: Map<String, String>, b: String?) {
        val sb = StringBuilder()
        sb.append("RTSP/1.0 ").append(st).append("\r\n")
        h.forEach { (k, v) -> sb.append(k).append(": ").append(v).append("\r\n") }
        if (b != null) sb.append("Content-Length: ").append(b.length).append("\r\n")
        sb.append("\r\n")
        if (b != null) sb.append(b)
        try {
            o.write(sb.toString().toByteArray())
            o.flush()
        } catch (_: Exception) {}
    }

    private fun bsp(): String {
        val sb64 = Base64.encodeToString(sps, Base64.NO_WRAP)
        val pb64 = Base64.encodeToString(pps, Base64.NO_WRAP)
        val pl = String.format("%02x%02x%02x", sps!![1], sps!![2], sps!![3])
        return "v=0\r\no=- 0 0 IN IP4 127.0.0.1\r\ns=CamRTSP\r\nc=IN IP4 0.0.0.0\r\nt=0 0\r\n" +
            "m=video 0 TCP/RTP/AVP 96\r\na=rtpmap:96 H264/90000\r\n" +
            "a=fmtp:96 profile-level-id=$pl;sprop-parameter-sets=$sb64,$pb64\r\n" +
            "a=control:track1\r\na=framerate:$fr\r\n"
    }
}

/** Per-client session: tracks RTSP state and writes RTP frames on the TCP socket. */
class CS(val sock: Socket, private val out: OutputStream) {
    var sid: String? = null
    var cseq: String? = null

    @Volatile
    var playing = false

    private var seq: Int = Random.nextInt(0, 0xFFFF)
    private val ssrc: Int = Random.nextInt()
    private val lk = Any()

    fun send(ns: List<ByteArray>, pts: Long, k: Boolean) {
        if (!playing) return
        val ts = pts * 90L
        synchronized(lk) {
            for (n in ns) {
                if (n.isEmpty()) continue
                val t = n[0].toInt() and 0x1F
                if (n.size <= 1400) s1(n, ts) else fa(n, ts, t)
            }
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
        out.write(0x24)
        out.write(0x00)
        out.write(((p.size shr 8) and 0xFF))
        out.write((p.size and 0xFF))
        out.write(p)
        out.flush()
    }

    fun close() {
        playing = false
        try { sock.close() } catch (_: Throwable) {}
    }
}
