package com.camrtsp.app
import android.Manifest; import android.content.Intent; import android.content.pm.PackageManager; import android.os.Build; import android.os.Bundle
import android.widget.Button; import android.widget.TextView; import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat; import androidx.core.content.ContextCompat
import java.net.NetworkInterface
class MainActivity : AppCompatActivity() {
    private lateinit var sT: TextView; private lateinit var aT: TextView
    private lateinit var sB: Button; private lateinit var tB: Button
    override fun onCreate(s: Bundle?) { super.onCreate(s); setContentView(R.layout.activity_main)
        sT=findViewById(R.id.statusText); aT=findViewById(R.id.addressText)
        sB=findViewById(R.id.startBtn); tB=findViewById(R.id.stopBtn)
        sB.setOnClickListener { if (hp()) sv() else rp() }
        tB.setOnClickListener { stopService(Intent(this, RtspServerService::class.java)); rf() }
        rf() }
    override fun onResume() { super.onResume(); rf() }
    private fun rf() { val ip = wip(); aT.text = "rtsp://$ip:${RtspServer.PORT}/live"
        val r = RtspServerService.isRunning
        sT.text = if (r) "Status: STREAMING" else "Status: stopped"
        sB.isEnabled = !r; tB.isEnabled = r }
    private fun wip() = try { NetworkInterface.getNetworkInterfaces().toList()
        .firstOrNull { it.name.equals("wlan0", true) && it.isUp }?.inetAddresses?.toList()
        ?.firstOrNull { it.hostAddress?.contains('.')==true }?.hostAddress ?: "0.0.0.0" } catch(e:Exception){ "0.0.0.0" }
    private fun hp(): Boolean { val n = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT>=33) n.add(Manifest.permission.POST_NOTIFICATIONS)
        return n.all { ContextCompat.checkSelfPermission(this,it)==PackageManager.PERMISSION_GRANTED } }
    private fun rp() { val n = mutableListOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT>=33) n.add(Manifest.permission.POST_NOTIFICATIONS)
        ActivityCompat.requestPermissions(this, n.toTypedArray(), 42) }
    override fun onRequestPermissionsResult(c:Int, p:Array<out String>, r:IntArray) { super.onRequestPermissionsResult(c,p,r)
        if (r.all{it==PackageManager.PERMISSION_GRANTED}) sv() else Toast.makeText(this,"Нужны разрешения!",Toast.LENGTH_LONG).show() }
    private fun sv() { val i = Intent(this, RtspServerService::class.java)
        if (Build.VERSION.SDK_INT>=26) startForegroundService(i) else startService(i); rf() }
}
