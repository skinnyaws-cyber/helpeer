package com.raseed.helper

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat

class KeepAliveService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val CHANNEL_ID = "KeepAliveServiceChannel"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        
        // استخدام WakeLock لإجبار المعالج على البقاء مستيقظاً 24/7 (بما أن الهاتف متصل بالشاحن دائماً)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RaseedHelper::KeepAliveWakeLock")
        wakeLock?.acquire()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // إنشاء إشعار مستمر يخبر النظام أن التطبيق يعمل ولا يجب إغلاقه
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Raseed Helper Active")
            .setContentText("النظام متصل ويستمع للرسائل 24/7...")
            .setSmallIcon(android.R.drawable.ic_dialog_info) // يمكنك تغييره لأيقونة تطبيقك إذا أردت
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true) // لا يمكن للمستخدم سحب الإشعار لإغلاقه
            .build()

        // تشغيل كخدمة واجهة أمامية (Foreground Service) لمنع القتل من النظام
        startForeground(1, notification)

        // START_STICKY: تعني أنه إذا قام النظام بقتل الخدمة لسبب طارئ جداً، سيقوم بإعادة تشغيلها فوراً
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        // تحرير الـ WakeLock عند إغلاق الخدمة لتجنب تسريب الذاكرة
        if (wakeLock?.isHeld == true) {
            wakeLock?.release()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        // لا نحتاج للربط مع واجهة، لذلك نرجع null
        return null
    }

    private fun createNotificationChannel() {
        // نظام أندرويد 8.0 فما فوق يتطلب إنشاء "قناة" للإشعارات
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Keep Alive Service Channel",
                NotificationManager.IMPORTANCE_HIGH
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(serviceChannel)
        }
    }
}
