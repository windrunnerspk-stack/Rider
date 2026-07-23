package com.diego.rider

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var log: TextView
    private lateinit var status: TextView
    private lateinit var scroll: ScrollView
    private val handler = Handler(Looper.getMainLooper())
    private val clockFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            when (i?.action) {
                RiderService.ACTION_LOG -> appendLog(i.getStringExtra("line") ?: "")
                RiderService.ACTION_STATE -> status.text = i.getStringExtra("state") ?: ""
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        log = findViewById(R.id.log)
        status = findViewById(R.id.status)
        scroll = findViewById(R.id.scroll)
        val apiKey = findViewById<EditText>(R.id.apiKey)
        val prefs = getSharedPreferences("rider", MODE_PRIVATE)
        if (prefs.getString("apiKey", "")!!.isNotEmpty()) apiKey.hint = "Clave guardada ✓ (puedes cambiarla)"

        findViewById<Button>(R.id.saveKey).setOnClickListener {
            val k = apiKey.text.toString().trim()
            if (k.isEmpty()) { Toast.makeText(this, "Pega tu clave primero", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
            prefs.edit().putString("apiKey", k).apply()
            apiKey.setText(""); apiKey.hint = "Clave guardada ✓"
            appendLog("Clave del cerebro guardada.")
        }

        findViewById<Button>(R.id.toggle).setOnClickListener {
            val b = it as Button
            if (b.text == "Encender") {
                if (!checkPerms()) return@setOnClickListener
                startForegroundService(Intent(this, RiderService::class.java))
                b.text = "Apagar"
                askBatteryExemption()
            } else {
                stopService(Intent(this, RiderService::class.java))
                b.text = "Encender"
                status.text = "SISTEMA APAGADO"
            }
        }

        val cmd = findViewById<EditText>(R.id.cmd)
        findViewById<Button>(R.id.send).setOnClickListener {
            val t = cmd.text.toString().trim()
            if (t.isEmpty()) return@setOnClickListener
            cmd.setText("")
            val i = Intent(this, RiderService::class.java)
            i.action = RiderService.ACTION_COMMAND
            i.putExtra("text", t)
            startForegroundService(i)
        }

        tickClock()
    }

    private fun tickClock() {
        findViewById<TextView>(R.id.clock).text = clockFmt.format(Date())
        handler.postDelayed({ tickClock() }, 500)
    }

    private fun appendLog(line: String) {
        log.append("\n$line")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun checkPerms(): Boolean {
        val need = mutableListOf<String>()
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS)
        if (need.isNotEmpty()) { requestPermissions(need.toTypedArray(), 1); return false }
        return true
    }

    private fun askBatteryExemption() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isIgnoringBatteryOptimizations(packageName)) {
            try {
                startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")))
            } catch (_: Exception) {}
        }
    }

    override fun onResume() {
        super.onResume()
        val f = IntentFilter().apply { addAction(RiderService.ACTION_LOG); addAction(RiderService.ACTION_STATE) }
        if (Build.VERSION.SDK_INT >= 33)
            registerReceiver(receiver, f, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(receiver, f)
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
