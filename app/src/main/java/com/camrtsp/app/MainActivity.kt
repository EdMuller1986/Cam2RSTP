package com.camrtsp.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
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
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private lateinit var statusText: TextView
    private lateinit var addressText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var copyLogButton: Button
    private lateinit var logText: TextView
    private lateinit var logScroll: ScrollView

    private val logBuffer = SpannableStringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    private val logFile by lazy { File(filesDir, RtspServerService.LOG_FILE_NAME) }

    private val serviceLogSink: (String, String) -> Unit = { line, level ->
        runOnUiThread { appendLog(line, level, persist = false) }
    }

    private val crashReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val crash = intent?.getStringExtra("crash") ?: return
            appendLog("=== CRASH RECEIVED ===", "E")
            crash.lineSequence().forEach { appendLog(it, "E") }
            appendLog("=== END CRASH ===", "E")
            Toast.makeText(this@MainActivity, "App crashed; log updated", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        addressText = findViewById(R.id.addressText)
        startButton = findViewById(R.id.startBtn)
        stopButton = findViewById(R.id.stopBtn)
        copyLogButton = findViewById(R.id.copyLogBtn)
        logText = findViewById(R.id.logText)
        logScroll = findViewById(R.id.logScroll)

        startButton.setOnClickListener { if (hasPermissions()) startStream() else requestPermissions() }
        stopButton.setOnClickListener {
            stopService(Intent(this, RtspServerService::class.java))
            appendLog("STOP pressed", "W")
            refreshUi()
        }
        copyLogButton.setOnClickListener { copyLog() }
        logText.setOnLongClickListener { copyLog(); true }

        registerCrashReceiver()
        RtspServerService.attachLog(serviceLogSink)
        loadRecentLogLines()
        appendLog("UI ready", "I")
        refreshUi()
    }

    override fun onResume() {
        super.onResume()
        RtspServerService.attachLog(serviceLogSink)
        refreshUi()
    }

    override fun onPause() {
        RtspServerService.detachLog(serviceLogSink)
        super.onPause()
    }

    override fun onDestroy() {
        RtspServerService.detachLog(serviceLogSink)
        try {
            unregisterReceiver(crashReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private fun registerCrashReceiver() {
        val filter = IntentFilter(RtspServerService.CRASH_ACTION)
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(crashReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(crashReceiver, filter)
        }
    }

    private fun refreshUi() {
        val ip = localIpv4Address()
        addressText.text = "rtsp://$ip:${RtspServer.PORT}/live"
        val running = RtspServerService.isRunning
        statusText.text = if (running) "Status: STREAMING" else "Status: stopped"
        startButton.isEnabled = !running
        stopButton.isEnabled = running
    }

    private fun localIpv4Address(): String {
        val activeNetworkIp = try {
            val cm = getSystemService(ConnectivityManager::class.java)
            val network = cm.activeNetwork
            val props = network?.let { cm.getLinkProperties(it) }
            props?.linkAddresses
                ?.mapNotNull { it.address }
                ?.firstOrNull { address ->
                    address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
                }
                ?.hostAddress
        } catch (e: Exception) {
            appendLog("active network IP lookup failed: ${e.javaClass.simpleName}: ${e.message}", "W")
            null
        }
        if (!activeNetworkIp.isNullOrBlank()) return activeNetworkIp

        return try {
            NetworkInterface.getNetworkInterfaces()?.toList()
                ?.asSequence()
                ?.filter { it.isUp && !it.isLoopback }
                ?.flatMap { it.inetAddresses.toList().asSequence() }
                ?.firstOrNull { address ->
                    address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress
                }
                ?.hostAddress ?: "0.0.0.0"
        } catch (e: Exception) {
            appendLog("fallback IP lookup failed: ${e.javaClass.simpleName}: ${e.message}", "E")
            "0.0.0.0"
        }
    }

    private fun hasPermissions(): Boolean {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        return needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun requestPermissions() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQUEST_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            appendLog("Permissions granted", "I")
            startStream()
        } else {
            appendLog("Permissions denied: ${grantResults.toList()}", "E")
            Toast.makeText(this, "Camera permission is required", Toast.LENGTH_LONG).show()
        }
    }

    private fun startStream() {
        try {
            appendLog("Starting service...", "I")
            val intent = Intent(this, RtspServerService::class.java)
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
            appendLog("Service start command sent", "I")
        } catch (e: Exception) {
            appendLog("startStream crashed: ${e.javaClass.simpleName}: ${e.message}", "E")
            appendLog(stackToString(e), "E")
        }
        refreshUi()
    }

    private fun copyLog() {
        try {
            val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = if (logFile.exists()) logFile.readText() else logBuffer.toString()
            cm.setPrimaryClip(ClipData.newPlainText("CamRTSP log", text))
            Toast.makeText(this, "Log copied (${text.length} chars)", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Copy failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun loadRecentLogLines() {
        try {
            if (!logFile.exists()) return
            val lines = logFile.readLines().takeLast(MAX_LOG_LINES)
            for (line in lines) {
                val parts = line.split(" ", limit = 3)
                if (parts.size >= 3) appendLog(parts[2], parts[0], persist = false)
            }
        } catch (e: Exception) {
            appendLog("read log failed: ${e.message}", "E")
        }
    }

    private fun appendLog(line: String, level: String, persist: Boolean = true) {
        val timestamp = timeFormat.format(Date())
        val start = logBuffer.length
        logBuffer.append("[$timestamp] $line\n")
        val color = when (level) {
            "E" -> android.graphics.Color.RED
            "W" -> android.graphics.Color.YELLOW
            "I" -> android.graphics.Color.CYAN
            else -> android.graphics.Color.GREEN
        }
        logBuffer.setSpan(ForegroundColorSpan(color), start, logBuffer.length, 0)
        logText.text = logBuffer
        logScroll.post { logScroll.fullScroll(View.FOCUS_DOWN) }

        if (persist) {
            try {
                RtspServerService.appendPersistentLog(filesDir, level, line)
            } catch (_: Exception) {
            }
        }
    }

    private fun stackToString(t: Throwable): String {
        val sw = java.io.StringWriter()
        t.printStackTrace(java.io.PrintWriter(sw))
        return sw.toString()
    }

    companion object {
        private const val REQUEST_PERMISSIONS = 42
        private const val MAX_LOG_LINES = 200
    }
}
