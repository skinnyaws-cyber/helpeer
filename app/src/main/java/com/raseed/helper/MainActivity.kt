package com.raseed.helper

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.raseed.helper.databinding.ActivityMainBinding
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db = FirebaseFirestore.getInstance()
    
    private val EXPORTED_FLAG = 2
    
    // === متغيرات الطابور (FIFO System) ===
    private val orderQueue = ArrayDeque<Order>() // الطابور
    private var isProcessing = false // هل النظام مشغول حالياً؟
    private var currentCardOrder: Order? = null // الطلب الذي يعالج حالياً
    
    // متغيرات التحكم والإحصائيات
    private var isMaintenanceMode = false
    private var successCounter = 0
    private var failedCounter = 0
    private var lastAlertTime = 0L

    // مستقبل نتائج USSD
    private val ussdResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.raseed.helper.USSD_RESULT") {
                val status = intent.getStringExtra("status") ?: "UNKNOWN"
                val message = intent.getStringExtra("message") ?: ""
                handleUSSDResult(status, message)
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
            logToConsole("System Online. FIFO Queue Ready.")
            updateServerStatus(true)
            startPulseAnimation()
            startListeningForOrders()
            checkAndRequestPermissions()
        } catch (e: Exception) {
            logToConsole("Init Failed: ${e.message}")
            updateServerStatus(false)
        }
    }

    // === استقبال الطلبات وإضافتها للطابور ===
    private fun startListeningForOrders() {
        db.collection("orders").whereEqualTo("status", "pending")
            .addSnapshotListener { snapshots, e ->
                if (e != null) return@addSnapshotListener
                
                if (isMaintenanceMode) return@addSnapshotListener

                if (snapshots != null && !snapshots.isEmpty) {
                    for (doc in snapshots.documentChanges) {
                        if (doc.type == DocumentChange.Type.ADDED) {
                            val data = doc.document.data
                            val order = Order(
                                id = doc.document.id,
                                amount = (data["amount"] as? Number)?.toDouble() ?: 0.0,
                                commission = (data["commission"] as? Number)?.toDouble() ?: 0.0,
                                telecomProvider = data["telecomProvider"] as? String ?: "",
                                transferType = data["transferType"] as? String ?: "",
                                targetInfo = data["targetInfo"] as? String ?: "",
                                status = data["status"] as? String ?: "",
                                userPhone = data["userPhone"] as? String ?: "",
                                userFullName = data["userFullName"] as? String ?: ""
                            )
                            
                            // 1. إضافة الطلب للطابور بدلاً من معالجته فوراً
                            addToQueue(order)
                        }
                    }
                }
            }
    }

    // === إدارة الطابور (Queue Manager) ===
    private fun addToQueue(order: Order) {
        // نتأكد أن الطلب غير مكرر في الطابور الحالي
        val exists = orderQueue.any { it.id == order.id }
        if (!exists && currentCardOrder?.id != order.id) {
            orderQueue.add(order)
            logToConsole("Order Added to Queue. Queue Size: ${orderQueue.size}")
            processNextOrder() // محاولة تشغيل الطلب التالي
        }
    }

    private fun processNextOrder() {
        // إذا كان النظام مشغولاً أو الطابور فارغاً، لا تفعل شيئاً
        if (isProcessing) {
            return 
        }
        
        if (orderQueue.isEmpty()) {
            logToConsole("Queue Empty. Waiting for orders...")
            return
        }

        // سحب أول طلب وبدء المعالجة
        isProcessing = true
        val nextOrder = orderQueue.removeFirst()
        processOrderLogic(nextOrder)
    }

    // === تنفيذ الطلب الفعلي ===
    private fun processOrderLogic(order: Order) {
        logToConsole(">>> Processing Order: ${order.id}")
        
        if (order.transferType.equals("card", ignoreCase = true)) {
            analyzeAndExecuteCard(order)
        } else {
             logToConsole("Skipping unsupported order type.")
             finishProcessingAndNext() // تخطي والانتقال للتالي
        }
    }

    private fun analyzeAndExecuteCard(order: Order) {
        val ussdCode = order.targetInfo.trim()
        if (ussdCode.isEmpty()) {
            handleFailedOrder(order, "كود التعبئة فارغ")
            return
        }

        val providerName = when {
            ussdCode.startsWith("*133*") -> "Asiacell"
            ussdCode.startsWith("*101") -> "Zain"
            else -> "Unknown"
        }

        if (providerName == "Unknown") {
            handleFailedOrder(order, "كود غير معروف")
            return
        }

        currentCardOrder = order
        logToConsole("Provider: $providerName. Executing...")
        dialUSSD(ussdCode, providerName)
    }

    private fun dialUSSD(ussd: String, providerName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            logToConsole("Error: Missing CALL_PHONE permission.")
            handleFailedOrder(currentCardOrder!!, "Missing Permission")
            return
        }

        try {
            val encodedUssd = ussd.replace("#", "%23")
            val uri = Uri.parse("tel:$encodedUssd")
            val intent = Intent(Intent.ACTION_CALL, uri)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val targetPhoneAccount = getPhoneAccountHandle(providerName)
                if (targetPhoneAccount != null) {
                    intent.putExtra(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, targetPhoneAccount)
                }
            }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } catch (e: Exception) {
            logToConsole("Dial Error: ${e.message}")
            currentCardOrder?.let { handleFailedOrder(it, "فشل الاتصال: ${e.message}") }
        }
    }

    private fun handleUSSDResult(status: String, ussdResponse: String) {
        // إذا كان اختباراً يدوياً (بدون طلب في الطابور)
        if (currentCardOrder == null && !isProcessing) {
            logToConsole("Manual Test Result: $ussdResponse")
            return
        }

        if (currentCardOrder != null) {
            logToConsole("USSD Response: $status - $ussdResponse")
            if (status == "SUCCESS") {
                handleSuccessOrder(currentCardOrder!!, ussdResponse)
            } else {
                handleFailedOrder(currentCardOrder!!, ussdResponse)
            }
        }
    }

    // === إنهاء الطلبات والانتقال للتالي ===

    private fun handleSuccessOrder(order: Order, message: String) {
        successCounter++
        binding.txtSuccessCount.text = successCounter.toString()
        
        logToConsole("SUCCESS! notifying admin...")
        
        db.collection("orders").document(order.id)
            .update(mapOf("status" to "waiting_admin_confirmation", "ussdResponse" to message))
            .addOnCompleteListener { finishProcessingAndNext() } // المهم هنا: الانتقال للتالي بعد التحديث

        sendAdminAlert("new_order", "تم استلام رصيد بقيمة ${order.amount}.")
    }

    private fun handleFailedOrder(order: Order, reason: String) {
        failedCounter++
        binding.txtFailedCount.text = failedCounter.toString()
        
        logToConsole("FAILED: $reason")
        
        db.collection("orders").document(order.id)
            .update(mapOf("status" to "failed", "failureReason" to reason))
            .addOnCompleteListener { finishProcessingAndNext() } // المهم هنا: الانتقال للتالي بعد التحديث
    }

    // الدالة الأهم في نظام FIFO: إعادة تعيين الحالة وسحب التالي
    private fun finishProcessingAndNext() {
        logToConsole("--- Order Completed ---")
        currentCardOrder = null
        isProcessing = false
        processNextOrder() // هل يوجد أحد آخر في الطابور؟
    }

    // ---------------------------------------------------------
    // بقية دوال الواجهة والتحكم (لم تتغير، فقط للتكامل)
    // ---------------------------------------------------------

    private fun setupControls() {
        binding.btnMaintenance.setOnClickListener {
            isMaintenanceMode = !isMaintenanceMode
            if (isMaintenanceMode) {
                binding.btnMaintenance.text = "MAINTENANCE MODE"
                binding.btnMaintenance.setIconTintResource(android.R.color.holo_orange_light)
                binding.btnMaintenance.setTextColor(getColor(android.R.color.holo_orange_light))
                logToConsole("!!! Maintenance Mode ENABLED. Queue Paused.")
            } else {
                binding.btnMaintenance.text = "ACTIVE MODE"
                binding.btnMaintenance.setIconTintResource(R.color.neon_green)
                binding.btnMaintenance.setTextColor(getColor(R.color.white))
                logToConsole("System Active. Queue Resumed.")
                processNextOrder() // استئناف الطابور فوراً
            }
        }

        binding.btnTestMode.setOnClickListener { showManualTestDialog() }
        
        binding.btnSimConfig.setOnClickListener {
            Toast.makeText(this, "SIM Config Saved: Auto-Detect Active", Toast.LENGTH_SHORT).show()
        }

        binding.btnExportLogs.setOnClickListener { saveLogsToFile() }

        binding.btnEmergency.setOnClickListener {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(intent)
            exitProcess(0)
        }
    }

    private fun showManualTestDialog() {
        val input = EditText(this)
        input.hint = "Enter USSD (e.g. *133#)"
        AlertDialog.Builder(this)
            .setTitle("Manual USSD Test")
            .setView(input)
            .setPositiveButton("Execute") { _, _ ->
                val code = input.text.toString()
                if (code.isNotEmpty()) {
                    logToConsole("Manual Test: $code")
                    dialUSSD(code, "Zain") 
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

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
        val color = if(isOnline) R.color.status_online else R.color.status_offline
        binding.imgServerStatus.setColorFilter(ContextCompat.getColor(this, color))
    }
    
    private fun checkAndRequestPermissions() {
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
        if (!isAccessibilityServiceEnabled()) {
             val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
             startActivity(intent)
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val prefString = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        return prefString != null && prefString.contains(packageName)
    }
    
    private fun getPhoneAccountHandle(providerName: String): PhoneAccountHandle? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
                return null
            }
            val telecomManager = getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            val callCapableAccounts = telecomManager.callCapablePhoneAccounts
            val subscriptionManager = getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            val activeSubs = subscriptionManager.activeSubscriptionInfoList
            
            for (handle in callCapableAccounts) {
                for (sub in activeSubs) {
                    if (sub.subscriptionId.toString() == handle.id || sub.iccId == handle.id) {
                         val carrier = sub.carrierName.toString().lowercase()
                         val display = sub.displayName.toString().lowercase()
                         val target = providerName.lowercase()
                         if (carrier.contains(target) || display.contains(target)) {
                             return handle
                         }
                    }
                }
            }
        }
        return null
    }
    
    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(ussdResultReceiver)
        unregisterReceiver(batteryReceiver)
    }
}
