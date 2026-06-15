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
        tB.setOnClickListener { stopService(Intent(this, RtspServerService::class.java)); rf() }
        cB.setOnClickListener { copyLog() }
        lT.setOnLongClickListener { copyLog(); true }

        RtspServerService.attachLog { line, level -> runOnUiThread { appendLog(line, level) } }
        log("UI ready", "I")
        rf()
    }

    override fun onResume() { super.onResume(); rf() }

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
    } catch (e: Exception) { log("wip: ${e.javaClass.simpleName}: ${e.message}", "E"); "0.0.0.0" }

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
            log("Permissions granted", "I"); sv()
        } else {
            log("Permissions denied: ${r.toList()}", "E")
            Toast.makeText(this, "Нужны разрешения!", Toast.LENGTH_LONG).show()
        }
    }

    private fun sv() {
        try {
            log("Starting service...", "I")
            val i = Intent(this, RtspServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i) else startService(i)
            log("Service start command sent", "I")
        } catch (e: Exception) {
            log("sv() crashed: ${e.javaClass.simpleName}: ${e.message}", "E")
            log(stackToString(e), "E")
        }
        rf()
    }

    private fun copyLog() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("CamRTSP log", lT.text))
            Toast.makeText(this, "Log copied (${lT.text.length} chars)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun appendLog(line: String, level: String) {
        val ts = df.format(Date())
        val full = "[$ts] $line\n"
        val start = logBuf.length
        logBuf.append(full)
        val color = when (level) {
            "E" -> android.graphics.Color.RED
            "W" -> android.graphics.Color.YELLOW
            "I" -> android.graphics.Color.CYAN
            else -> android.graphics.Color.GREEN
        }
        logBuf.setSpan(ForegroundColorSpan(color), start, logBuf.length, 0)
        lT.text = logBuf
        lS.post { lS.fullScroll(View.FOCUS_DOWN) }
    }

    private fun log(s: String, level: String) = appendLog(s, level)

    private fun stackToString(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }
}
