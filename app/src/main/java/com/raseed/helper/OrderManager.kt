package com.raseed.helper

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentChange
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap

object OrderManager {

    private const val TAG = "OrderManager"
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // هذا هو "المايكروفون" الذي يتحدث فيه SmsReceiver
    // SharedFlow يسمح لعدة مساعدين بالاستماع لنفس الرسالة
    private val _smsSharedFlow = MutableSharedFlow<SmsData>()
    val smsSharedFlow = _smsSharedFlow.asSharedFlow()

    // سجل لتتبع المساعدين النشطين (لكي لا نكرر المساعد لنفس الطلب)
    private val activeAssistants = ConcurrentHashMap<String, Job>()
    
    private var firestoreListener: ListenerRegistration? = null

    // هيكل بيانات الرسالة القادمة
    data class SmsData(val amount: Double, val phone: String, val provider: String)

    // دالة بدء العمل (يجب استدعاؤها في MainActivity مرة واحدة)
    fun startMonitoring() {
        if (firestoreListener != null) return // لمنع التكرار

        Log.i(TAG, "Starting Order Monitoring System (Assistants Mode)...")
        val db = FirebaseFirestore.getInstance()

        // الاستماع الحي للطلبات المعلقة فقط
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
                                // طلب جديد وصل -> تعيين مساعد خاص له
                                createAssistantForOrder(dc.document)
                            }
                            DocumentChange.Type.MODIFIED -> {
                                // إذا تغيرت حالة الطلب لشيء غير pending، المساعد ينسحب
                                val status = dc.document.getString("status")
                                if (status != "pending") {
                                    dismissAssistant(orderId)
                                }
                            }
                            DocumentChange.Type.REMOVED -> {
                                // تم حذف الطلب -> تسريح المساعد
                                dismissAssistant(orderId)
                            }
                        }
                    }
                }
            }
    }

    // دالة يستخدمها SmsReceiver لنشر الخبر
    suspend fun broadcastSmsArrival(amount: Double, phone: String, provider: String) {
        Log.d(TAG, "Broadcasting SMS: Amount=$amount, Phone=...${phone.takeLast(4)}")
        _smsSharedFlow.emit(SmsData(amount, phone, provider))
    }

    // --- منطق المساعد الشخصي (The Assistant) ---
    private fun createAssistantForOrder(doc: com.google.firebase.firestore.DocumentSnapshot) {
        val orderId = doc.id
        if (activeAssistants.containsKey(orderId)) return // المساعد موجود بالفعل

        // قراءة بيانات العميل (الطلب)
        val orderAmount = doc.getDouble("amount") ?: 0.0
        val orderPhoneFull = doc.getString("userPhone") ?: ""
        val orderProvider = doc.getString("telecomProvider") ?: ""
        val createdAt = doc.getTimestamp("timestamp") ?: Timestamp.now()

        // تنظيف رقم هاتف العميل للمقارنة
        val cleanOrderPhone = normalizePhone(orderPhoneFull)

        // إنشاء المساعد في غرفة معزولة (Coroutine)
        val assistantJob = scope.launch {
            Log.i(TAG, "Assistant assigned to Order $orderId ($orderAmount)")

            // حساب الوقت المتبقي من الـ 30 دقيقة
            val timeoutMillis = calculateRemainingTime(createdAt)

            if (timeoutMillis <= 0) {
                // الوقت انتهى أصلاً -> فشل الطلب فوراً
                markOrderAsFailed(orderId, "Expired before processing")
                return@launch
            }

            try {
                // المساعد ينتظر لمدة محددة (withTimeout)
                withTimeout(timeoutMillis) {
                    // الاستماع للمايكروفون (SharedFlow)
                    smsSharedFlow.collect { sms ->
                        // هل هذه الرسالة تخص عميلي؟
                        val cleanSmsPhone = normalizePhone(sms.phone)
                        
                        val isPhoneMatch = cleanOrderPhone.endsWith(cleanSmsPhone) || cleanSmsPhone.endsWith(cleanOrderPhone)
                        val isAmountMatch = sms.amount == orderAmount
                        val isProviderMatch = orderProvider.contains(sms.provider, ignoreCase = true)

                        if (isPhoneMatch && isAmountMatch && isProviderMatch) {
                            Log.i(TAG, "Assistant for Order $orderId: MATCH FOUND! Processing...")
                            
                            // الموافقة على الطلب
                            confirmOrder(orderId, sms.amount)
                            
                            // الخروج من الـ collect (إنهاء المهمة)
                            cancel() 
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                // انتهى الوقت ولم تصل رسالة مطابقة
                Log.w(TAG, "Assistant for Order $orderId: Time is up! Marking as failed.")
                markOrderAsFailed(orderId, "Timeout: No SMS received within 30 mins")
            }
        }

        activeAssistants[orderId] = assistantJob
    }

    private fun dismissAssistant(orderId: String) {
        activeAssistants[orderId]?.cancel()
        activeAssistants.remove(orderId)
        Log.d(TAG, "Assistant for Order $orderId dismissed.")
    }

    private fun confirmOrder(orderId: String, amount: Double) {
        // التصحيح: نستخدم المسار الكامل بدلاً من doc.reference
        FirebaseFirestore.getInstance().collection("orders").doc(orderId)
            .update(mapOf(
                "status" to "waiting_admin_confirmation",
                "sms_confirmation_time" to Timestamp.now(),
                "actual_received_amount" to amount
            ))
            .addOnSuccessListener { dismissAssistant(orderId) }
    }

    private fun markOrderAsFailed(orderId: String, reason: String) {
        // التصحيح: نستخدم المسار الكامل بدلاً من doc.reference
        FirebaseFirestore.getInstance().collection("orders").doc(orderId)
            .update(mapOf(
                "status" to "failed",
                "failure_reason" to reason
            ))
            .addOnSuccessListener { dismissAssistant(orderId) }
    }

    private fun calculateRemainingTime(createdAt: Timestamp): Long {
        val expiryTime = createdAt.toDate().time + (30 * 60 * 1000) // وقت الإنشاء + 30 دقيقة
        val currentTime = System.currentTimeMillis()
        return expiryTime - currentTime
    }

    private fun normalizePhone(phone: String): String {
        val digits = phone.filter { it.isDigit() }
        return if (digits.length > 10) digits.takeLast(10) else digits
    }
}
