package com.vibhavtiwari.smsorganizer.telephony

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.vibhavtiwari.smsorganizer.otp.OtpNotifications
import java.util.concurrent.Executors

/**
 * Register only when the complete default-SMS path is ready, with a manifest-level
 * android.permission.BROADCAST_SMS sender permission and only the SMS_DELIVER action.
 */
class PlatformSmsDeliveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION || !app.hasSmsRole()) return
        val receivedAt = System.currentTimeMillis()
        val pending = goAsync()
        deliveryExecutor.execute {
            try {
                val stored = persistPlatformSmsDelivery(intent, DeviceSmsStore(app), receivedAt)
                if (stored is SmsDeliveryResult.Stored) {
                    OtpNotifications(app).post(stored.message)
                }
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val deliveryExecutor = Executors.newSingleThreadExecutor()
    }
}
