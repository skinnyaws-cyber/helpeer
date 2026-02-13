package com.raseed.helper

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap

object OrderManager {

    private const val TAG = "OrderManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // 1. قناة التواصل مع التيرمنال (الشاشة)
    private val _terminalFlow = MutableSharedFlow<String>()
    val terminalFlow = _terminalFlow.asSharedFlow()

    // 2. قناة المايكروفون (SmsReceiver)
    private val _smsSharedFlow = MutableSharedFlow<SmsData>()
    val smsSharedFlow = _smsSharedFlow.asSharedFlow()

    private val activeAssistants = ConcurrentHashMap<String, Job>()
    private var firestoreListener: ListenerRegistration? = null

    data class SmsData(val amount: Double, val phone: String, val provider: String)

    // دالة مساعدة لطباعة النص في النظام وفي شاشة التطبيق معاً
    private fun logToUi(message: String) {
        Log.i(TAG, message) // للنظام
        scope.launch { _terminalFlow.emit(message) } // للشاشة
    }

    fun startMonitoring() {
        if (firestoreListener != null) return

        logToUi(">> System Started. Listening for orders...") // رسالة بداية
        val db = FirebaseFirestore.getInstance()

        firestoreListener = db.collection("orders")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    logToUi("!! Error: Listen failed: ${e.message}")
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    for (dc in snapshots.documentChanges) {
                        val orderId = dc.document.id
                        when (dc.type) {
                            DocumentChange.Type.ADDED -> {
                                createAssistantForOrder(dc.document)
                            }
                            DocumentChange.Type.MODIFIED -> {
                                val status = dc.document.getString("status")
                                if (status != "pending") {
                                    dismissAssistant(orderId)
                                }
                            }
                            DocumentChange.Type.REMOVED -> {
                                dismissAssistant(orderId)
                            }
                        }
                    }
                }
            }
    }

    suspend fun broadcastSmsArrival(amount: Double, phone: String, provider: String) {
        logToUi(">> SMS Received: $amount from ...${phone.takeLast(4)} ($provider)")
        _smsSharedFlow.emit(SmsData(amount, phone, provider))
    }

    private fun createAssistantForOrder(doc: com.google.firebase.firestore.DocumentSnapshot) {
        val orderId = doc.id
        if (activeAssistants.containsKey(orderId)) return

        val orderAmount = doc.getDouble("amount") ?: 0.0
        val orderPhoneFull = doc.getString("userPhone") ?: ""
        val orderProvider = doc.getString("telecomProvider") ?: ""
        val createdAt = doc.getTimestamp("timestamp") ?: Timestamp.now()

        val cleanOrderPhone = normalizePhone(orderPhoneFull)

        val assistantJob = scope.launch {
            logToUi("++ New Order Detected: $orderAmount ($orderProvider)")
            logToUi("   User Phone: ...${cleanOrderPhone.takeLast(6)}")
            logToUi("   [Assistant #$orderId] Assigned & Waiting...")

            val timeoutMillis = calculateRemainingTime(createdAt)

            if (timeoutMillis <= 0) {
                markOrderAsFailed(orderId, "Expired before processing")
                return@launch
            }

            try {
                withTimeout(timeoutMillis) {
                    smsSharedFlow.collect { sms ->
                        val cleanSmsPhone = normalizePhone(sms.phone)
                        
                        val isPhoneMatch = cleanOrderPhone.endsWith(cleanSmsPhone) || cleanSmsPhone.endsWith(cleanOrderPhone)
                        val isAmountMatch = sms.amount == orderAmount
                        val isProviderMatch = orderProvider.contains(sms.provider, ignoreCase = true)

                        if (isPhoneMatch && isAmountMatch && isProviderMatch) {
                            logToUi("$$ MATCH FOUND for Order #$orderId!")
                            logToUi("   Processing Confirmation...")
                            
                            confirmOrder(orderId, sms.amount)
                            
                            cancel() 
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                logToUi("-- Order #$orderId Timed Out!")
                markOrderAsFailed(orderId, "Timeout: No SMS received within 30 mins")
            }
        }

        activeAssistants[orderId] = assistantJob
    }

    private fun dismissAssistant(orderId: String) {
        if (activeAssistants.containsKey(orderId)) {
            activeAssistants[orderId]?.cancel()
            activeAssistants.remove(orderId)
            // logToUi("   [Assistant #$orderId] Dismissed.") // اختياري لتقليل الازعاج
        }
    }

    private fun confirmOrder(orderId: String, amount: Double) {
        FirebaseFirestore.getInstance().collection("orders").document(orderId)
            .update(mapOf(
                "status" to "waiting_admin_confirmation",
                "sms_confirmation_time" to Timestamp.now(),
                "actual_received_amount" to amount
            ))
            .addOnSuccessListener { 
                logToUi("VV Order #$orderId Confirmed Successfully.")
                dismissAssistant(orderId) 
            }
            .addOnFailureListener { e ->
                logToUi("!! Error Confirming #$orderId: ${e.message}")
            }
    }

    private fun markOrderAsFailed(orderId: String, reason: String) {
        FirebaseFirestore.getInstance().collection("orders").document(orderId)
            .update(mapOf(
                "status" to "failed",
                "failure_reason" to reason
            ))
            .addOnSuccessListener { 
                logToUi("XX Order #$orderId Failed: $reason")
                dismissAssistant(orderId) 
            }
    }

    private fun calculateRemainingTime(createdAt: Timestamp): Long {
        val expiryTime = createdAt.toDate().time + (30 * 60 * 1000)
        val currentTime = System.currentTimeMillis()
        return expiryTime - currentTime
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }
}
