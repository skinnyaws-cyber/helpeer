package com.raseed.helper

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.regex.Pattern

class SmsReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            
            // نستخدم Coroutine لنشر الرسالة دون تعطيل النظام
            CoroutineScope(Dispatchers.IO).launch {
                for (sms in messages) {
                    val sender = sms.originatingAddress ?: ""
                    val body = sms.messageBody ?: ""
                    Log.d("SmsReceiver", "SMS Received from $sender: $body")

                    if (sender.contains("Asiacell", ignoreCase = true)) {
                        analyzeAsiacellSms(body)
                    } else if (sender.contains("Zain", ignoreCase = true) || sender.contains("IQ", ignoreCase = true)) {
                        analyzeZainSms(body)
                    }
                }
            }
        }
    }

    private suspend fun analyzeAsiacellSms(body: String) {
        try {
            // الصيغة: لقد إستلمت 5,000 د من الرقم 7714097343
            val amountMatcher = Pattern.compile("إستلمت\\s*([\\d,،]+)").matcher(body)
            val phoneMatcher = Pattern.compile("من الرقم\\s*(\\d+)").matcher(body)

            if (amountMatcher.find() && phoneMatcher.find()) {
                val rawAmount = amountMatcher.group(1) ?: "0"
                val rawPhone = phoneMatcher.group(1) ?: ""

                val amount = cleanAndParseAmount(rawAmount)
                val phone = convertArabicNumbers(rawPhone)

                if (amount > 0) {
                    // "الصراخ" في المايكروفون ليسمع المساعدون
                    OrderManager.broadcastSmsArrival(amount, phone, "Asiacell")
                }
            }
        } catch (e: Exception) {
            Log.e("SmsReceiver", "Error parsing Asiacell: ${e.message}")
        }
    }

    private suspend fun analyzeZainSms(body: String) {
        try {
            // الصيغة: المشترك 7829390195قام بتحويل 1000دينار
            // ملاحظة: \s* تعني (مسافة أو بدون مسافة) وهذا يحل مشكلة التصاق الرقم بكلمة "قام"
            val phoneMatcher = Pattern.compile("المشترك\\s*(\\d+)").matcher(body)
            val amountMatcher = Pattern.compile("بتحويل\\s*([\\d,،]+)").matcher(body)

            if (amountMatcher.find() && phoneMatcher.find()) {
                val rawAmount = amountMatcher.group(1) ?: "0"
                val rawPhone = phoneMatcher.group(1) ?: ""

                val amount = cleanAndParseAmount(rawAmount)
                val phone = convertArabicNumbers(rawPhone)

                if (amount > 0) {
                    // "الصراخ" في المايكروفون ليسمع المساعدون
                    OrderManager.broadcastSmsArrival(amount, phone, "Zain")
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
}
