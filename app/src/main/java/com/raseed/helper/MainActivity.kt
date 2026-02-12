package com.raseed.helper

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.raseed.helper.databinding.ActivityMainBinding
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db = FirebaseFirestore.getInstance()
    
    private val EXPORTED_FLAG = 2
    
    // متغيرات التحكم والإحصائيات
    private var isMaintenanceMode = false
    private var lastAlertTime = 0L

    // مستقبل نتائج USSD (تم الإبقاء عليه لهيكل النظام لكنه غير مستخدم في المنطق الجديد حالياً)
    private val ussdResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.raseed.helper.USSD_RESULT") {
                val status = intent.getStringExtra("status") ?: "UNKNOWN"
                val message = intent.getStringExtra("message") ?: ""
                logToConsole("USSD Event: $status - $message")
            }
        }
    }

    // مستقبل حالة البطارية
    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            val temp = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
            
            val batteryPct = level * 100 / scale.toFloat()
            val tempC = temp / 10.0 

            updateDeviceHealthUI(batteryPct, tempC)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filterUSSD = IntentFilter("com.raseed.helper.USSD_RESULT")
        val filterBattery = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(ussdResultReceiver, filterUSSD, EXPORTED_FLAG)
            registerReceiver(batteryReceiver, filterBattery, EXPORTED_FLAG)
        } else {
            registerReceiver(ussdResultReceiver, filterUSSD)
            registerReceiver(batteryReceiver, filterBattery)
        }

        setupControls()

        try {
            FirebaseApp.initializeApp(this)
            logToConsole("System Online. Assistants Mode Ready.")
            updateServerStatus(true)
            startPulseAnimation()
            
            // --- تفعيل النظام الجديد (المساعدين) ---
            // لا نحتاج لمؤقت هنا، المساعدون يديرون وقتهم ذاتياً
            OrderManager.startMonitoring()
            
            // التحقق السريع عند البدء
            checkPermissionsOnStart()
            
        } catch (e: Exception) {
            logToConsole("Init Failed: ${e.message}")
            updateServerStatus(false)
        }
    }

    // ---------------------------------------------------------
    // دوال الواجهة والتحكم
    // ---------------------------------------------------------

    private fun setupControls() {
        // زر الصيانة
        binding.btnMaintenance.setOnClickListener {
            isMaintenanceMode = !isMaintenanceMode
            if (isMaintenanceMode) {
                binding.btnMaintenance.text = "MAINTENANCE MODE"
                binding.btnMaintenance.setTextColor(Color.YELLOW)
                logToConsole("!!! Maintenance Mode ENABLED.")
            } else {
                binding.btnMaintenance.text = "ACTIVE MODE"
                binding.btnMaintenance.setTextColor(Color.WHITE)
                logToConsole("System Active.")
            }
        }

        // زر التصدير
        binding.btnExportLogs.setOnClickListener { saveLogsToFile() }

        // زر الطوارئ
        binding.btnEmergency.setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            exitProcess(0)
        }
        
        // --- الزر الجديد: إدارة الأذونات ---
        // ملاحظة: تأكد من إضافة زر بهذا الاسم في ملف activity_main.xml
        // android:id="@+id/btnPermissions"
        // إذا لم يكن موجوداً، قم بإضافته أولاً ليعمل هذا الكود
        binding.btnSimConfig.setOnClickListener { 
            // قمت بربطه مؤقتاً بزر Config القديم، يمكنك تغيير الربط لزر جديد
            showPermissionsDashboard() 
        }
    }

    // === لوحة التحكم بالأذونات (Dashboard) ===
    private fun showPermissionsDashboard() {
        val scrollView = ScrollView(this)
        val layout = LinearLayout(this)
        layout.orientation = LinearLayout.VERTICAL
        layout.setPadding(40, 40, 40, 40)
        scrollView.addView(layout)

        val title = TextView(this)
        title.text = "Required Permissions Check"
        title.textSize = 20f
        title.setTypeface(null, Typeface.BOLD)
        title.gravity = Gravity.CENTER
        title.setPadding(0, 0, 0, 30)
        layout.addView(title)

        // 1. SMS Permission
        val hasSms = ContextCompat.checkSelfPermission(this, Manifest.permission.RECEIVE_SMS) == PackageManager.PERMISSION_GRANTED &&
                     ContextCompat.checkSelfPermission(this, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        addPermissionRow(layout, "SMS Access (Receive/Read)", hasSms) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        }

        // 2. Battery Optimization (Background Work)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val isIgnoringBattery = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
        addPermissionRow(layout, "Ignore Battery Opt (Keep Alive)", isIgnoringBattery) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

        // 3. Overlay Permission (For Alerts/USSD)
        val hasOverlay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.canDrawOverlays(this) else true
        addPermissionRow(layout, "Display Over Other Apps", hasOverlay) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivity(intent)
            }
        }

        // 4. Phone State (Reading SIM)
        val hasPhone = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED
        addPermissionRow(layout, "Read Phone State (SIM)", hasPhone) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            intent.data = Uri.fromParts("package", packageName, null)
            startActivity(intent)
        }
        
        // 5. Accessibility Service (Optional but good for USSD)
        val hasAccess = isAccessibilityServiceEnabled()
        addPermissionRow(layout, "Accessibility Service", hasAccess) {
             val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
             startActivity(intent)
        }

        AlertDialog.Builder(this)
            .setView(scrollView)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun addPermissionRow(parent: LinearLayout, labelText: String, isGranted: Boolean, onEnable: () -> Unit) {
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.weightSum = 3f
        row.setPadding(0, 15, 0, 15)

        val label = TextView(this)
        label.text = labelText
        label.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f)
        label.textSize = 14f
        row.addView(label)

        val statusBtn = Button(this)
        statusBtn.layoutParams = LinearLayout.LayoutParams(0, 100, 1f) // height fixed for consistency
        statusBtn.textSize = 12f
        
        if (isGranted) {
            statusBtn.text = "Disable" // المعنى: هو مفعل حالياً، اضغط للتعطيل (أو للعرض فقط)
            statusBtn.setBackgroundColor(Color.parseColor("#4CAF50")) // Green
            statusBtn.setTextColor(Color.WHITE)
            statusBtn.isEnabled = false // لا حاجة للضغط إذا كان مفعلاً (أو يمكن تفعيله ليفتح الإعدادات أيضاً)
        } else {
            statusBtn.text = "Enable"
            statusBtn.setBackgroundColor(Color.parseColor("#F44336")) // Red
            statusBtn.setTextColor(Color.WHITE)
            statusBtn.setOnClickListener { 
                onEnable() 
                Toast.makeText(this, "Please enable: $labelText", Toast.LENGTH_LONG).show()
            }
        }
        
        row.addView(statusBtn)
        parent.addView(row)
        
        // فاصل خطي
        val divider = View(this)
        divider.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 2)
        divider.setBackgroundColor(Color.LTGRAY)
        
        parent.addView(row)
        parent.addView(divider)
    }

    private fun checkPermissionsOnStart() {
        val requiredPermissions = arrayOf(
            Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS,
            Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE
        )
        val missingPermissions = requiredPermissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), 100)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val prefString = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return prefString != null && prefString.contains(packageName)
    }
    
    // === وظائف الصحة والتنبيهات (لم تتغير) ===

    private fun updateDeviceHealthUI(batteryPct: Float, tempC: Double) {
        binding.txtBatteryLevel.text = "${batteryPct.toInt()}%"
        binding.txtTempLevel.text = "${tempC}°C"
        
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastAlertTime > 30 * 60 * 1000) {
            if (tempC > 45.0 || batteryPct < 15.0) {
                sendAdminAlert("CRITICAL HEALTH", "Battery: $batteryPct%, Temp: $tempC°C")
                lastAlertTime = currentTime
            }
        }
    }

    private fun sendAdminAlert(type: String, message: String) {
        val notification = hashMapOf(
            "type" to type,
            "message" to message,
            "timestamp" to FieldValue.serverTimestamp(),
            "status" to "unread"
        )
        db.collection("admin_notifications").add(notification)
    }
    
    private fun saveLogsToFile() {
        try {
            val fileName = "logs_${System.currentTimeMillis()}.txt"
            val file = File(getExternalFilesDir(null), fileName)
            val writer = FileWriter(file)
            writer.append(binding.txtConsole.text)
            writer.flush()
            writer.close()
            Toast.makeText(this, "Logs saved", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {}
    }

    fun logToConsole(message: String) {
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            if (binding.txtConsole.text.length > 5000) {
                binding.txtConsole.text = "> Clearing old logs...\n"
            }
            binding.txtConsole.append("\n[$currentTime] $message")
            binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }
    
    private fun startPulseAnimation() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.2f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.2f)
        val animator = ObjectAnimator.ofPropertyValuesHolder(binding.imgServerStatus, scaleX, scaleY)
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.duration = 1000
        animator.start()
    }
    
    private fun updateServerStatus(isOnline: Boolean) {
        val color = if(isOnline) com.raseed.helper.R.color.neon_green else android.R.color.holo_red_dark
        // ملاحظة: تأكد من أن أسماء الألوان تطابق ما في ملف الـ XML
        // إذا كان لديك لون معرف باسم status_online استخدمه
        try {
             binding.imgServerStatus.setColorFilter(ContextCompat.getColor(this, color))
        } catch (e: Exception) {
             binding.imgServerStatus.setColorFilter(if(isOnline) Color.GREEN else Color.RED)
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(ussdResultReceiver)
        unregisterReceiver(batteryReceiver)
    }
}
