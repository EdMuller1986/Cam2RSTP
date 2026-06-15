package com.camrtsp.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.view.View
import android.widget.Button
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var sT: TextView
    private lateinit var aT: TextView
    private lateinit var sB: Button
    private lateinit var tB: Button
    private lateinit var cB: Button
    private lateinit var lT: TextView
    private lateinit var lS: ScrollView
    private val logBuf = SpannableStringBuilder()
    private val df = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logFile by lazy { java.io.File(filesDir, "camrtsp.log") }
    private val lastLines = ArrayDeque<String>(50)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        setContentView(R.layout.activity_main)
        sT = findViewById(R.id.statusText)
        aT = findViewById(R.id.addressText)
        sB = findViewById(R.id.startBtn)
        tB = findViewById(R.id.stopBtn)
        cB = findViewById(R.id.copyLogBtn)
        lT = findViewById(R.id.logText)
        lS = findViewById(R.id.logScroll)

        sB.setOnClickListener { if (hp()) sv() else rp() }
        tB.setOnClickListener {
            stopService(Intent(this, RtspServerService::class.java))
            appendLog("STOP pressed by user", "W")
            rf()
        }
        cB.setOnClickListener { copyLog() }
        lT.setOnLongClickListener { copyLog(); true }

        RtspServerService.attachLog { line, level -> runOnUiThread { appendLog(line, level) } }
        // Read back old log
        try {
            if (logFile.exists()) {
                logFile.bufferedReader().useLines { lines ->
                    val list = lines.toList()
                    val start = if (list.size > 200) list.size - 200 else 0
                    for (i in start until list.size) {
                        val l = list[i]
                        val (level, msg) = parseStoredLine(l)
                        appendLog(msg, level, silent = true)
                    }
                }
            }
        } catch (e: Exception) { appendLog("read log: ${e.message}", "E") }
        appendLog("UI ready", "I")
        rf()
    }

    override fun onResume() {
        super.onResume()
        // Re-attach in case service was killed and re-created
        RtspServerService.attachLog { line, level -> runOnUiThread { appendLog(line, level) } }
        rf()
    }

    private fun parseStoredLine(l: String): Pair<String, String> {
        // Stored as "I 2024-01-01 12:00:00: message"
        val parts = l.split(" ", limit = 3)
        return if (parts.size >= 3) parts[0] to parts[2] else "I" to l
    }

    private fun rf() {
        val ip = wip()
        aT.text = "rtsp://$ip:${RtspServer.PORT}/live"
        val r = RtspServerService.isRunning
        sT.text = if (r) "Status: STREAMING" else "Status: stopped"
        sB.isEnabled = !r
        tB.isEnabled = r
    }

    private fun wip() = try {
        NetworkInterface.getNetworkInterfaces()?.toList()
            ?.firstOrNull { it.name.equals("wlan0", true) && it.isUp }
            ?.inetAddresses?.toList()
            ?.firstOrNull { it.hostAddress?.contains('.') == true }
            ?.hostAddress ?: "0.0.0.0"
    } catch (e: Exception) { appendLog("wip: ${e.javaClass.simpleName}: ${e.message}", "E"); "0.0.0.0" }

    private fun hp(): Boolean {
        val n = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) n.add(Manifest.permission.POST_NOTIFICATIONS)
        return n.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun rp() {
        val n = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) n.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, n.toTypedArray(), 42)
    }

    override fun onRequestPermissionsResult(c: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(c, p, r)
        if (r.all { it == PackageManager.PERMISSION_GRANTED }) {
            appendLog("Permissions granted", "I"); sv()
        } else {
            appendLog("Permissions denied: ${r.toList()}", "E")
            Toast.makeText(this, "Нужны разрешения!", Toast.LENGTH_LONG).show()
        }
    }

    private fun sv() {
        try {
            appendLog("Starting service...", "I")
            val i = Intent(this, RtspServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            appendLog("Service start command sent", "I")
        } catch (e: Exception) {
            appendLog("sv() crashed: ${e.javaClass.simpleName}: ${e.message}", "E")
            appendLog(stackToString(e), "E")
        }
        rf()
    }

    private fun copyLog() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val full = java.io.File(filesDir, "camrtsp.log").readText()
            cm.setPrimaryClip(ClipData.newPlainText("CamRTSP log", full))
            Toast.makeText(this, "Log copied (${full.length} chars)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appendLog(line: String, level: String, silent: Boolean = false) {
        val ts = df.format(Date())
        val full = "[$ts] $line"
        val start = logBuf.length
        logBuf.append(full).append('\n')
        val color = when (level) {
            "E" -> android.graphics.Color.RED
            "W" -> android.graphics.Color.YELLOW
            "I" -> android.graphics.Color.CYAN
            else -> android.graphics.Color.GREEN
        }
        logBuf.setSpan(ForegroundColorSpan(color), start, logBuf.length, 0)
        lT.text = logBuf
        lS.post { lS.fullScroll(View.FOCUS_DOWN) }
        if (!silent) {
            // Persist to file
            try {
                val storable = "$level $ts: $line\n"
                logFile.appendText(storable)
            } catch (_: Exception) {}
        }
    }

    private fun stackToString(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }
}
