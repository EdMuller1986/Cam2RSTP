package com.camrtsp.app

import android.util.Base64
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.random.Random

class RtspServer(
    private val width: Int,
    private val height: Int,
    private val frameRate: Int,
    private val clockRate: Int = 90_000
) {
    companion object {
        const val PORT = 8554
        private const val SERVER_NAME = "CamRTSP/1.1"
    }

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val clients = ConcurrentHashMap<String, ClientSession>()

    @Volatile private var sps: ByteArray? = null
    @Volatile private var pps: ByteArray? = null
    @Volatile var ok: Boolean = false
        private set

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread(name = "RtspAccept", isDaemon = true) {
            try {
                acceptLoop()
            } catch (e: Exception) {
                Log.e("RtspSrv", "accept thread: ${e.javaClass.simpleName}: ${e.message}", e)
            }
        }
    }

    fun stop() {
        running.set(false)
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        clients.values.toList().forEach { it.close() }
        clients.clear()
    }

    fun resetCodecConfig() {
        sps = null
        pps = null
        ok = false
    }

    fun setSP(codecConfig: ByteArray) {
        try {
            val nals = splitAnnexB(codecConfig)
            for (nal in nals) {
                if (nal.isEmpty()) continue
                when (nal[0].toInt() and 0x1F) {
                    7 -> sps = nal.copyOf()
                    8 -> pps = nal.copyOf()
                }
            }
            if (sps != null && pps != null) {
                ok = true
                Log.i("RtspSrv", "SPS=${sps?.size} PPS=${pps?.size} ok=true")
            }
        } catch (e: Exception) {
            Log.e("RtspSrv", "setSP: ${e.message}", e)
        }
    }

    fun push(accessUnit: ByteArray, ptsUs: Long, keyFrame: Boolean) {
        if (!running.get()) return
        try {
            val nals = splitAnnexB(accessUnit).filter { it.isNotEmpty() }
            if (nals.isEmpty()) return

            val snapshot = clients.values.toList()
            for (client in snapshot) {
                try {
                    client.send(nals, ptsUs)
                } catch (e: Exception) {
                    Log.w("RtspSrv", "push to client: ${e.message}")
                }
            }
        } catch (e: Exception) {
            Log.e("RtspSrv", "push: ${e.message}", e)
        }
    }

    private fun acceptLoop() {
        val socket = try {
            ServerSocket(PORT)
        } catch (e: Exception) {
            Log.e("RtspSrv", "ServerSocket: ${e.message}")
            return
        }
        serverSocket = socket
        Log.i("RtspSrv", "RTSP listening on $PORT")

        while (running.get()) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            thread(name = "RtspCli", isDaemon = true) {
                try {
                    handleClient(client)
                } catch (e: Exception) {
                    Log.e("RtspSrv", "client thread: ${e.javaClass.simpleName}: ${e.message}", e)
                }
            }
        }
    }

    private fun handleClient(socket: Socket) {
        socket.soTimeout = 60_000
        val input = BufferedReader(InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
        val output = socket.getOutputStream()
        val session = ClientSession(socket, output)

        try {
            while (running.get() && !socket.isClosed) {
                val request = readRequest(input) ?: break
                Log.i("RtspSrv", "<- ${request.method} ${request.uri} ${request.version}")

                val cSeq = request.header("cseq") ?: "0"
                val commonHeaders = linkedMapOf("CSeq" to cSeq)
                var closeAfterResponse = false

                when (request.method.uppercase(Locale.US)) {
                    "OPTIONS" -> sendResponse(
                        output,
                        "200 OK",
                        commonHeaders + mapOf("Public" to "OPTIONS, DESCRIBE, SETUP, PLAY, TEARDOWN"),
                        null
                    )

                    "DESCRIBE" -> {
                        if (!ok) {
                            sendResponse(output, "503 Service Unavailable", commonHeaders, null)
                        } else {
                            sendResponse(
                                output,
                                "200 OK",
                                commonHeaders + mapOf(
                                    "Content-Type" to "application/sdp",
                                    "Content-Base" to request.uri.ensureTrailingSlash()
                                ),
                                buildSdp()
                            )
                        }
                    }

                    "SETUP" -> {
                        val transport = request.header("transport") ?: ""
                        if (!transport.contains("RTP/AVP/TCP", ignoreCase = true)) {
                            sendResponse(output, "461 Unsupported Transport", commonHeaders, null)
                        } else {
                            val interleaved = parseInterleavedChannels(transport)
                            session.rtpChannel = interleaved.first
                            session.rtcpChannel = interleaved.second
                            if (session.sessionId == null) {
                                session.sessionId = Random.nextInt(1, Int.MAX_VALUE).toString()
                            }
                            clients[session.sessionId!!] = session
                            sendResponse(
                                output,
                                "200 OK",
                                commonHeaders + mapOf(
                                    "Session" to "${session.sessionId};timeout=60",
                                    "Transport" to "RTP/AVP/TCP;unicast;interleaved=${session.rtpChannel}-${session.rtcpChannel}"
                                ),
                                null
                            )
                        }
                    }

                    "PLAY" -> {
                        val requestedSession = parseSessionHeader(request.header("session")) ?: session.sessionId
                        if (requestedSession == null || requestedSession != session.sessionId) {
                            sendResponse(output, "454 Session Not Found", commonHeaders, null)
                        } else {
                            session.playing = true
                            sendResponse(
                                output,
                                "200 OK",
                                commonHeaders + mapOf(
                                    "Session" to requestedSession,
                                    "Range" to "npt=0.000-",
                                    "RTP-Info" to "url=${request.uri};seq=${session.nextSequenceNumber};rtptime=0"
                                ),
                                null
                            )
                        }
                    }

                    "TEARDOWN" -> {
                        sendResponse(
                            output,
                            "200 OK",
                            commonHeaders + mapOf("Session" to (session.sessionId ?: "0")),
                            null
                        )
                        closeAfterResponse = true
                    }

                    else -> sendResponse(output, "501 Not Implemented", commonHeaders, null)
                }

                if (closeAfterResponse) break
            }
        } finally {
            session.sessionId?.let { clients.remove(it) }
            session.close()
        }
    }

    private fun readRequest(input: BufferedReader): RtspRequest? {
        var requestLine: String?
        do {
            requestLine = input.readLine()
        } while (requestLine != null && requestLine.isBlank())

        val firstLine = requestLine ?: return null
        val parts = firstLine.trim().split(Regex("\\s+"), limit = 3)
        if (parts.size != 3) return null

        val headers = linkedMapOf<String, String>()
        while (true) {
            val line = input.readLine() ?: return null
            if (line.isBlank()) break
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val name = line.substring(0, colon).trim().lowercase(Locale.US)
            val value = line.substring(colon + 1).trim()
            headers[name] = value
        }

        return RtspRequest(parts[0], parts[1], parts[2], headers)
    }

    private fun sendResponse(
        output: OutputStream,
        status: String,
        headers: Map<String, String>,
        body: String?
    ) {
        try {
            val bodyBytes = body?.toByteArray(StandardCharsets.UTF_8)
            val responseHeaders = linkedMapOf<String, String>()
            responseHeaders["Server"] = SERVER_NAME
            responseHeaders.putAll(headers)
            if (bodyBytes != null) responseHeaders["Content-Length"] = bodyBytes.size.toString()

            val headerText = buildString {
                append("RTSP/1.0 ").append(status).append("\r\n")
                responseHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
                append("\r\n")
            }
            output.write(headerText.toByteArray(StandardCharsets.US_ASCII))
            if (bodyBytes != null) output.write(bodyBytes)
            output.flush()
            Log.i("RtspSrv", "-> RTSP/1.0 $status [CSeq=${headers["CSeq"] ?: "0"}]")
        } catch (e: Exception) {
            Log.w("RtspSrv", "send RTSP: ${e.message}")
        }
    }

    private fun buildSdp(): String {
        val spsBytes = sps ?: return ""
        val ppsBytes = pps ?: return ""
        val spsB64 = Base64.encodeToString(spsBytes, Base64.NO_WRAP)
        val ppsB64 = Base64.encodeToString(ppsBytes, Base64.NO_WRAP)
        val profileLevelId = if (spsBytes.size >= 4) {
            String.format(
                Locale.US,
                "%02X%02X%02X",
                spsBytes[1].toInt() and 0xFF,
                spsBytes[2].toInt() and 0xFF,
                spsBytes[3].toInt() and 0xFF
            )
        } else {
            "42E01F"
        }

        return "v=0\r\n" +
            "o=- 0 0 IN IP4 127.0.0.1\r\n" +
            "s=CamRTSP\r\n" +
            "c=IN IP4 0.0.0.0\r\n" +
            "t=0 0\r\n" +
            "a=control:*\r\n" +
            "m=video 0 TCP/RTP/AVP 96\r\n" +
            "a=rtpmap:96 H264/$clockRate\r\n" +
            "a=fmtp:96 profile-level-id=$profileLevelId;sprop-parameter-sets=$spsB64,$ppsB64;packetization-mode=1\r\n" +
            "a=control:trackID=0\r\n" +
            "a=framerate:$frameRate\r\n" +
            "a=x-dimensions:${width},${height}\r\n"
    }

    private fun parseInterleavedChannels(transport: String): Pair<Int, Int> {
        val match = Regex("interleaved=(\\d+)-(\\d+)", RegexOption.IGNORE_CASE).find(transport)
        return if (match != null) {
            match.groupValues[1].toInt() to match.groupValues[2].toInt()
        } else {
            0 to 1
        }
    }

    private fun parseSessionHeader(value: String?): String? = value
        ?.substringBefore(';')
        ?.trim()
        ?.takeIf { it.isNotEmpty() }

    private fun String.ensureTrailingSlash(): String = if (endsWith('/')) this else "$this/"

    private data class RtspRequest(
        val method: String,
        val uri: String,
        val version: String,
        val headers: Map<String, String>
    ) {
        fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    }

    private fun splitAnnexB(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        val firstStart = findStartCode(data, 0)
        if (firstStart < 0) return listOf(data.copyOf())

        val nals = ArrayList<ByteArray>()
        var start = firstStart
        while (start >= 0 && start < data.size) {
            val nalStart = start + startCodeLength(data, start)
            val nextStart = findStartCode(data, nalStart)
            val nalEnd = if (nextStart >= 0) nextStart else data.size
            if (nalEnd > nalStart) nals.add(data.copyOfRange(nalStart, nalEnd))
            if (nextStart < 0) break
            start = nextStart
        }
        return nals
    }

    private fun findStartCode(data: ByteArray, from: Int): Int {
        var i = from.coerceAtLeast(0)
        while (i <= data.size - 3) {
            if (data[i].toInt() == 0 && data[i + 1].toInt() == 0) {
                if (data[i + 2].toInt() == 1) return i
                if (i <= data.size - 4 && data[i + 2].toInt() == 0 && data[i + 3].toInt() == 1) return i
            }
            i++
        }
        return -1
    }

    private fun startCodeLength(data: ByteArray, index: Int): Int =
        if (index <= data.size - 4 && data[index + 2].toInt() == 0 && data[index + 3].toInt() == 1) 4 else 3
}

private class ClientSession(
    private val socket: Socket,
    private val output: OutputStream
) {
    @Volatile var sessionId: String? = null
    @Volatile var playing: Boolean = false
    @Volatile var rtpChannel: Int = 0
    @Volatile var rtcpChannel: Int = 1

    private var sequenceNumber: Int = Random.nextInt(0, 0x10000)
    private val ssrc: Int = Random.nextInt()
    private val lock = Any()

    val nextSequenceNumber: Int
        get() = sequenceNumber and 0xFFFF

    fun send(nals: List<ByteArray>, ptsUs: Long) {
        if (!playing || nals.isEmpty()) return
        val timestamp = (ptsUs * 90_000L / 1_000_000L) and 0xFFFF_FFFFL
        try {
            synchronized(lock) {
                nals.forEachIndexed { index, nal ->
                    if (nal.isEmpty()) return@forEachIndexed
                    val marker = index == nals.lastIndex
                    writeNalUnit(nal, timestamp, marker)
                }
            }
        } catch (e: Exception) {
            Log.w("RtspSrv", "send: ${e.message}")
            playing = false
        }
    }

    private fun writeNalUnit(nal: ByteArray, timestamp: Long, marker: Boolean) {
        if (nal.size <= MAX_RTP_PAYLOAD) {
            val packet = ByteArray(RTP_HEADER_SIZE + nal.size)
            writeRtpHeader(packet, timestamp, marker)
            System.arraycopy(nal, 0, packet, RTP_HEADER_SIZE, nal.size)
            writeInterleaved(packet)
        } else {
            writeFuA(nal, timestamp, marker)
        }
    }

    private fun writeFuA(nal: ByteArray, timestamp: Long, marker: Boolean) {
        val nalHeader = nal[0].toInt() and 0xFF
        val nalType = nalHeader and 0x1F
        val fuIndicator = (nalHeader and 0xE0) or 28
        var offset = 1
        var first = true
        val maxChunk = MAX_RTP_PAYLOAD - 2

        while (offset < nal.size) {
            val chunkSize = minOf(maxChunk, nal.size - offset)
            val last = offset + chunkSize >= nal.size
            val packet = ByteArray(RTP_HEADER_SIZE + 2 + chunkSize)
            writeRtpHeader(packet, timestamp, marker && last)
            packet[RTP_HEADER_SIZE] = fuIndicator.toByte()
            packet[RTP_HEADER_SIZE + 1] = ((if (first) 0x80 else 0) or (if (last) 0x40 else 0) or nalType).toByte()
            System.arraycopy(nal, offset, packet, RTP_HEADER_SIZE + 2, chunkSize)
            writeInterleaved(packet)
            first = false
            offset += chunkSize
        }
    }

    private fun writeRtpHeader(packet: ByteArray, timestamp: Long, marker: Boolean) {
        packet[0] = 0x80.toByte()
        packet[1] = ((if (marker) 0x80 else 0) or PAYLOAD_TYPE).toByte()
        packet[2] = ((sequenceNumber shr 8) and 0xFF).toByte()
        packet[3] = (sequenceNumber and 0xFF).toByte()
        sequenceNumber = (sequenceNumber + 1) and 0xFFFF

        packet[4] = ((timestamp shr 24) and 0xFF).toByte()
        packet[5] = ((timestamp shr 16) and 0xFF).toByte()
        packet[6] = ((timestamp shr 8) and 0xFF).toByte()
        packet[7] = (timestamp and 0xFF).toByte()
        packet[8] = ((ssrc shr 24) and 0xFF).toByte()
        packet[9] = ((ssrc shr 16) and 0xFF).toByte()
        packet[10] = ((ssrc shr 8) and 0xFF).toByte()
        packet[11] = (ssrc and 0xFF).toByte()
    }

    private fun writeInterleaved(packet: ByteArray) {
        output.write(INTERLEAVED_MAGIC)
        output.write(rtpChannel and 0xFF)
        output.write((packet.size shr 8) and 0xFF)
        output.write(packet.size and 0xFF)
        output.write(packet)
        output.flush()
    }

    fun close() {
        playing = false
        try {
            socket.close()
        } catch (_: Throwable) {
        }
    }

    companion object {
        private const val RTP_HEADER_SIZE = 12
        private const val PAYLOAD_TYPE = 96
        private const val MAX_RTP_PAYLOAD = 1400
        private const val INTERLEAVED_MAGIC = 0x24
    }
}
