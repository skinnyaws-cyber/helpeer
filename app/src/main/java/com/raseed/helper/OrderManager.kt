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
    
    // المايكروفون الذي يتحدث فيه SmsReceiver
    private val _smsSharedFlow = MutableSharedFlow<SmsData>()
    val smsSharedFlow = _smsSharedFlow.asSharedFlow()

    private val activeAssistants = ConcurrentHashMap<String, Job>()
    private var firestoreListener: ListenerRegistration? = null

    data class SmsData(val amount: Double, val phone: String, val provider: String)

    fun startMonitoring() {
        if (firestoreListener != null) return

        Log.i(TAG, "Starting Order Monitoring System (Assistants Mode)...")
        val db = FirebaseFirestore.getInstance()

        firestoreListener = db.collection("orders")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { snapshots, e ->
                if (e != null) {
                    Log.e(TAG, "Listen failed: $e")
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
        Log.d(TAG, "Broadcasting SMS: Amount=$amount, Phone=...${phone.takeLast(4)}")
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
            Log.i(TAG, "Assistant assigned to Order $orderId ($orderAmount)")

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
                            Log.i(TAG, "Assistant for Order $orderId: MATCH FOUND! Processing...")
                            
                            confirmOrder(orderId, sms.amount)
                            
                            cancel() 
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                Log.w(TAG, "Assistant for Order $orderId: Time is up!")
                markOrderAsFailed(orderId, "Timeout: No SMS received within 30 mins")
            }
        }

        activeAssistants[orderId] = assistantJob
    }

    private fun dismissAssistant(orderId: String) {
        activeAssistants[orderId]?.cancel()
        activeAssistants.remove(orderId)
    }

    private fun confirmOrder(orderId: String, amount: Double) {
        // تم التصحيح: استخدام orderId مباشرة للوصول للوثيقة
        FirebaseFirestore.getInstance().collection("orders").doc(orderId)
            .update(mapOf(
                "status" to "waiting_admin_confirmation",
                "sms_confirmation_time" to Timestamp.now(),
                "actual_received_amount" to amount
            ))
            .addOnSuccessListener { dismissAssistant(orderId) }
    }

    private fun markOrderAsFailed(orderId: String, reason: String) {
        // تم التصحيح: استخدام orderId مباشرة للوصول للوثيقة
        FirebaseFirestore.getInstance().collection("orders").doc(orderId)
            .update(mapOf(
                "status" to "failed",
                "failure_reason" to reason
            ))
            .addOnSuccessListener { dismissAssistant(orderId) }
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
