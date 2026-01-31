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
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import android.telephony.SubscriptionManager
import android.view.View
import android.view.animation.Animation
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.raseed.helper.databinding.ActivityMainBinding
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val db = FirebaseFirestore.getInstance()
    private var currentCardOrder: Order? = null
    private val EXPORTED_FLAG = 2
    
    // متغيرات العدادات
    private var successCounter = 0
    private var failedCounter = 0

    private val ussdResultReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.raseed.helper.USSD_RESULT") {
                val status = intent.getStringExtra("status") ?: "UNKNOWN"
                val message = intent.getStringExtra("message") ?: ""
                handleUSSDResult(status, message)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val filter = IntentFilter("com.raseed.helper.USSD_RESULT")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            registerReceiver(ussdResultReceiver, filter, EXPORTED_FLAG)
        } else {
            registerReceiver(ussdResultReceiver, filter)
        }

        try {
            FirebaseApp.initializeApp(this)
            logToConsole("System Online. Monitoring Orders...")
            updateServerStatus(true)
            startPulseAnimation() // تشغيل النبض
            startListeningForOrders()
        } catch (e: Exception) {
            logToConsole("Firebase Init Failed: ${e.message}")
            updateServerStatus(false)
        }

        binding.btnPermissions.setOnClickListener { checkAndRequestPermissions() }
    }

    // === دالة النبض (Pulse Animation) ===
    private fun startPulseAnimation() {
        val scaleX = PropertyValuesHolder.ofFloat(View.SCALE_X, 1.2f)
        val scaleY = PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.2f)
        val animator = ObjectAnimator.ofPropertyValuesHolder(binding.imgServerStatus, scaleX, scaleY)
        animator.repeatCount = ObjectAnimator.INFINITE
        animator.repeatMode = ObjectAnimator.REVERSE
        animator.duration = 1000 // سرعة النبض (1 ثانية)
        animator.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(ussdResultReceiver)
    }

    private fun startListeningForOrders() {
        db.collection("orders").whereEqualTo("status", "pending")
            .addSnapshotListener { snapshots, e ->
                if (e != null) { return@addSnapshotListener }

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
                            if (currentCardOrder == null) { processNewOrder(order) }
                        }
                    }
                }
            }
    }

    private fun processNewOrder(order: Order) {
        logToConsole("Processing Order: ${order.id}")
        if (order.transferType.equals("direct", ignoreCase = true)) {
             OrderManager.addOrder(order)
             logToConsole("-> Direct Transfer detected.")
        } else if (order.transferType.equals("card", ignoreCase = true)) {
            logToConsole("-> Card Recharge detected...")
            analyzeAndExecuteCard(order)
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
            handleFailedOrder(order, "صيغة الكود غير معروفة")
            return
        }

        currentCardOrder = order
        logToConsole("Detected: $providerName. Dialing...")
        dialUSSD(ussdCode, providerName)
    }

    private fun dialUSSD(ussd: String, providerName: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            logToConsole("Error: Missing CALL_PHONE permission.")
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
            currentCardOrder?.let { handleFailedOrder(it, "فشل الاتصال: ${e.message}") }
            currentCardOrder = null
        }
    }

    private fun handleUSSDResult(status: String, ussdResponse: String) {
        if (currentCardOrder == null) return
        logToConsole("USSD Result: $status")

        if (status == "SUCCESS") {
            handleSuccessOrder(currentCardOrder!!, ussdResponse)
        } else {
            handleFailedOrder(currentCardOrder!!, ussdResponse)
        }
        currentCardOrder = null
    }

    private fun handleSuccessOrder(order: Order, message: String) {
        // تحديث العداد
        successCounter++
        binding.txtSuccessCount.text = successCounter.toString()
        
        logToConsole("Order SUCCESS! Notifying Admin...")
        db.collection("orders").document(order.id)
            .update(mapOf("status" to "waiting_admin_confirmation", "ussdResponse" to message))

        val adminNotification = hashMapOf(
            "orderId" to order.id,
            "type" to "card_recharge_success",
            "message" to "تم شحن الكارت بنجاح. المبلغ: ${order.amount}",
            "userPhone" to order.userPhone,
            "amount" to order.amount,
            "timestamp" to FieldValue.serverTimestamp(),
            "status" to "unread"
        )
        db.collection("admin_notifications").add(adminNotification)
    }

    private fun handleFailedOrder(order: Order, reason: String) {
        // تحديث العداد
        failedCounter++
        binding.txtFailedCount.text = failedCounter.toString()
        
        logToConsole("Order FAILED! Updating User...")
        db.collection("orders").document(order.id)
            .update(mapOf("status" to "failed", "failureReason" to reason))
    }

    fun logToConsole(message: String) {
        val currentTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        runOnUiThread {
            binding.txtConsole.append("\n[$currentTime] $message")
            binding.scrollView.post { binding.scrollView.fullScroll(android.view.View.FOCUS_DOWN) }
        }
    }
    
    private fun updateServerStatus(isOnline: Boolean) {
        val text = if(isOnline) "ONLINE" else "OFFLINE"
        val color = if(isOnline) R.color.status_online else R.color.status_offline
        binding.txtServerStatus.text = text
        binding.txtServerStatus.setTextColor(ContextCompat.getColor(this, color))
        binding.imgServerStatus.setColorFilter(ContextCompat.getColor(this, color))
    }
    
    // ... (دوال الصلاحيات و getPhoneAccountHandle تبقى كما هي في الكود السابق) ...
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
}
