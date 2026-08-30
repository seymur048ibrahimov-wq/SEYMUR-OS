package com.seymur.os

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {

    private lateinit var time: TextView
    private lateinit var stats: TextView

    private val uiHandler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            updateClock()
            uiHandler.postDelayed(this, 30_000)
        }
    }
    private val statsTick = object : Runnable {
        override fun run() {
            updateStats()
            uiHandler.postDelayed(this, 5_000)
        }
    }

    // Fənər vəziyyəti
    private var cameraId: String? = null
    private var torchOn = false

    private val requestCameraPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) toggleFlash()
            else Toast.makeText(this, "Fənər üçün kamera icazəsi lazımdır", Toast.LENGTH_SHORT).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        time = findViewById(R.id.time)
        stats = findViewById(R.id.stats)

        setupTorchId()
        updateClock()
        updateStats()

        findViewById<Button>(R.id.apps).setOnClickListener { showApps() }
        findViewById<Button>(R.id.settings).setOnClickListener { openSettings() }
        findViewById<Button>(R.id.privacy).setOnClickListener {
            safeStart(Intent(Settings.ACTION_PRIVACY_SETTINGS))
        }
        findViewById<Button>(R.id.wifi).setOnClickListener {
            safeStart(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        findViewById<Button>(R.id.bright).setOnClickListener {
            safeStart(Intent(Settings.ACTION_DISPLAY_SETTINGS))
        }
        findViewById<Button>(R.id.flash).setOnClickListener { onFlashClicked() }
        findViewById<EditText>(R.id.search).setOnEditorActionListener { v, _, _ ->
            launchSearch(v.text.toString())
            true
        }

        // Home ekranı olduğu üçün "geri" düyməsi tətbiqi bağlamasın
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* heç nə etmə */ }
        })
    }

    override fun onResume() {
        super.onResume()
        uiHandler.post(clockTick)
        uiHandler.post(statsTick)
    }

    override fun onPause() {
        super.onPause()
        uiHandler.removeCallbacks(clockTick)
        uiHandler.removeCallbacks(statsTick)
    }

    private fun updateClock() {
        time.text = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
    }

    private fun updateStats() {
        try {
            val bm = getSystemService(BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val usedRam = (mi.totalMem - mi.availMem) / (1024 * 1024)
            val totalRam = mi.totalMem / (1024 * 1024)
            stats.text = "🔋 Batareya: $battery%\n🧠 RAM: $usedRam / $totalRam MB\n🛡 Sistem: Android ${Build.VERSION.RELEASE}"
        } catch (e: Exception) {
            stats.text = "Məlumat alına bilmədi"
        }
    }

    private fun showApps() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString().lowercase() }

        val inflater = LayoutInflater.from(this)
        val adapter = object : ArrayAdapter<ApplicationInfo>(this, 0, apps) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row = convertView ?: inflater.inflate(android.R.layout.simple_list_item_1, parent, false)
                val app = apps[position]
                val text = row.findViewById<TextView>(android.R.id.text1)
                text.text = pm.getApplicationLabel(app)
                text.setCompoundDrawablesWithIntrinsicBounds(pm.getApplicationIcon(app), null, null, null)
                text.compoundDrawablePadding = 24
                return row
            }
        }

        AlertDialog.Builder(this)
            .setTitle("Seymur App Drawer")
            .setAdapter(adapter) { _, which ->
                safeStart(pm.getLaunchIntentForPackage(apps[which].packageName))
            }
            .setNegativeButton("Bağla", null)
            .show()
    }

    private fun launchSearch(q: String) {
        if (q.isBlank()) return
        val pm = packageManager
        val match = pm.getInstalledApplications(0).firstOrNull {
            pm.getLaunchIntentForPackage(it.packageName) != null &&
                pm.getApplicationLabel(it).toString().contains(q, true)
        }
        if (match != null) safeStart(pm.getLaunchIntentForPackage(match.packageName))
        else Toast.makeText(this, "Tapılmadı: $q", Toast.LENGTH_SHORT).show()
    }

    private fun openSettings() {
        safeStart(Intent(Settings.ACTION_SETTINGS))
    }

    private fun setupTorchId() {
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            cameraId = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        } catch (e: Exception) {
            cameraId = null
        }
    }

    private fun onFlashClicked() {
        if (cameraId == null) {
            Toast.makeText(this, "Bu cihazda fənər tapılmadı", Toast.LENGTH_SHORT).show()
            return
        }
        if (checkSelfPermission(android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission.launch(android.Manifest.permission.CAMERA)
            return
        }
        toggleFlash()
    }

    private fun toggleFlash() {
        val id = cameraId ?: return
        try {
            val cm = getSystemService(CAMERA_SERVICE) as CameraManager
            torchOn = !torchOn
            cm.setTorchMode(id, torchOn)
        } catch (e: Exception) {
            torchOn = false
            Toast.makeText(this, "Fənər idarəsi cihaz tərəfindən məhdudlaşdırıldı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun safeStart(intent: Intent?) {
        if (intent == null) return
        try {
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Açıla bilmədi", Toast.LENGTH_SHORT).show()
        }
    }
}
