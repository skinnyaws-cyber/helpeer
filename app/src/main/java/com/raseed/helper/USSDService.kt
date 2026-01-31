package com.raseed.helper

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class USSDService : AccessibilityService() {

    private val TAG = "USSDService"

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "USSD Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        // نحن مهتمون فقط بتغير النوافذ (ظهور رسالة الرصيد)
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            val source = event.source ?: return
            
            // استخراج النص من النافذة
            val textList = source.findAccessibilityNodeInfosByText("")
            val fullText = StringBuilder()
            
            for (node in textList) {
                if (node.text != null) {
                    fullText.append(node.text).append("\n")
                }
            }

            val message = fullText.toString()
            Log.d(TAG, "Window Text Detected: $message")

            // تحليل النص لمعرفة هل نجح الشحن أم فشل
            if (message.isNotEmpty()) {
                processUSSDMessage(message, source)
            }
        }
    }

    private fun processUSSDMessage(message: String, rootNode: AccessibilityNodeInfo) {
        // كلمات تدل على النجاح (يمكنك إضافة المزيد حسب الحاجة)
        val successKeywords = listOf("تم تعبئة", "رصيدك الحالي", "Success", "recharged")
        
        // كلمات تدل على الفشل
        val failureKeywords = listOf("خطأ", "فشل", "Error", "Failed", "غير صحيح")

        var status = "UNKNOWN"
        var finalMessage = message

        // التحقق من النجاح
        for (keyword in successKeywords) {
            if (message.contains(keyword, ignoreCase = true)) {
                status = "SUCCESS"
                break
            }
        }

        // التحقق من الفشل (إذا لم يكن ناجحاً بعد)
        if (status == "UNKNOWN") {
            for (keyword in failureKeywords) {
                if (message.contains(keyword, ignoreCase = true)) {
                    status = "FAILED"
                    break
                }
            }
        }

        // إذا تم التعرف على الحالة، نقوم بإرسال النتيجة وإغلاق النافذة
        if (status != "UNKNOWN") {
            Log.d(TAG, "USSD Status Identified: $status")
            broadcastResult(status, finalMessage)
            performGlobalAction(GLOBAL_ACTION_BACK) // إغلاق النافذة
        }
    }

    private fun broadcastResult(status: String, message: String) {
        val intent = Intent("com.raseed.helper.USSD_RESULT")
        intent.putExtra("status", status)
        intent.putExtra("message", message)
        intent.setPackage(packageName) // لضمان وصولها لتطبيقنا فقط
        sendBroadcast(intent)
    }

    override fun onInterrupt() {
        Log.d(TAG, "USSD Service Interrupted")
    }
}
