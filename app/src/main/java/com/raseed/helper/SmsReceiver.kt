package com.raseed.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import java.util.regex.Pattern

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            for (sms in messages) {
                val sender = sms.originatingAddress ?: ""
                val body = sms.messageBody ?: ""
                Log.d("SmsReceiver", "SMS: $sender, Body: $body")

                if (sender.contains("Asiacell", ignoreCase = true)) {
                    analyzeAsiacellSms(context, body)
                } else if (sender.contains("Zain", ignoreCase = true) || sender.contains("IQ", ignoreCase = true)) {
                    analyzeZainSms(context, body)
                }
            }
        }
    }

    private fun analyzeAsiacellSms(context: Context, body: String) {
        try {
            val amountMatcher = Pattern.compile("إستلمت\\s*([\\d,،]+)").matcher(body)
            val phoneMatcher = Pattern.compile("من الرقم\\s*(\\d+)").matcher(body)

            if (amountMatcher.find() && phoneMatcher.find()) {
                val rawAmount = amountMatcher.group(1) ?: "0"
                val rawPhone = phoneMatcher.group(1) ?: ""

                val amount = cleanAndParseAmount(rawAmount)
                val phone = convertArabicNumbers(rawPhone)

                if (amount > 0) {
                    checkMatch(context, amount, phone, "Asiacell")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error parsing Asiacell: ${e.message}")
        }
    }

    private fun analyzeZainSms(context: Context, body: String) {
        try {
            val amountMatcher = Pattern.compile("بتحويل\\s*([\\d,،]+)").matcher(body)
            val phoneMatcher = Pattern.compile("المشترك\\s*(\\d+)").matcher(body)

            if (amountMatcher.find() && phoneMatcher.find()) {
                val rawAmount = amountMatcher.group(1) ?: "0"
                val rawPhone = phoneMatcher.group(1) ?: ""

                val amount = cleanAndParseAmount(rawAmount)
                val phone = convertArabicNumbers(rawPhone)

                if (amount > 0) {
                    checkMatch(context, amount, phone, "Zain")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error parsing Zain: ${e.message}")
        }
    }

    private fun cleanAndParseAmount(rawAmount: String): Double {
        var clean = convertArabicNumbers(rawAmount)
        clean = clean.replace(",", "").replace("،", "").trim()
        return clean.toDoubleOrNull() ?: 0.0
    }

    private fun convertArabicNumbers(str: String): String {
        return str.replace("٠", "0").replace("١", "1").replace("٢", "2").replace("٣", "3").replace("٤", "4")
                  .replace("٥", "5").replace("٦", "6").replace("٧", "7").replace("٨", "8").replace("٩", "9")
    }

    private fun checkMatch(context: Context, amount: Double, phone: String, provider: String) {
        val matchingOrder = OrderManager.findMatchingOrder(amount, phone, provider)
        if (matchingOrder != null) {
            Log.i("SmsReceiver", "MATCH FOUND: ${matchingOrder.id}")
            
            val db = FirebaseFirestore.getInstance()
            val data = hashMapOf(
                "orderId" to matchingOrder.id,
                "type" to "confirmation_request",
                "status" to "waiting_admin_approval",
                "message" to "تم استلام رصيد ${matchingOrder.amount} من ${matchingOrder.userPhone}",
                "amountReceived" to matchingOrder.amount,
                "senderPhone" to matchingOrder.userPhone,
                "userFullName" to matchingOrder.userFullName,
                "targetCard" to matchingOrder.receivingCard,
                "timestamp" to FieldValue.serverTimestamp()
            )
            
            db.collection("admin_notifications").add(data)
                .addOnSuccessListener { 
                    OrderManager.removeOrder(matchingOrder.id)
                }
                .addOnFailureListener { e ->
                    Log.e("SmsReceiver", "Failed to send notification: ${e.message}")
                }
        }
    }
}
