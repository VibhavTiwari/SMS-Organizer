package com.vibhavtiwari.smsorganizer.telephony

import android.content.Intent
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.vibhavtiwari.smsorganizer.core.MmsDecode
import com.vibhavtiwari.smsorganizer.core.MmsNotification
import com.vibhavtiwari.smsorganizer.core.decodeMmsNotification

internal data class DeliveredMmsNotification(val subscriptionId: Int, val notification: MmsNotification)

/** Decode only inside a future BROADCAST_WAP_PUSH-protected receiver. This doesn't register one. */
@Suppress("DEPRECATION")
internal fun decodePlatformMmsNotification(intent: Intent): MmsDecode<DeliveredMmsNotification> {
    if (intent.action != Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION ||
        intent.type != "application/vnd.wap.mms-message"
    ) return MmsDecode.Unsupported
    return try {
        val extras = intent.extras ?: return MmsDecode.Malformed
        fun parse(value: Any?): Int? {
            val number = when (value) {
                is Int -> value.toLong()
                is Long -> value
                else -> return null
            }
            return number.takeIf { it in 0 until Int.MAX_VALUE.toLong() }?.toInt()
        }
        val modern = parse(extras.get(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX))
        val legacy = parse(extras.get("subscription"))
        if (modern != null && legacy != null && modern != legacy) return MmsDecode.Malformed
        val subscriptionId = modern ?: legacy ?: return MmsDecode.Unsupported
        val pdu = extras.getByteArray("data") ?: return MmsDecode.Malformed
        when (val decoded = decodeMmsNotification(pdu)) {
            is MmsDecode.Supported -> MmsDecode.Supported(DeliveredMmsNotification(subscriptionId, decoded.value))
            MmsDecode.Malformed -> MmsDecode.Malformed
            MmsDecode.Unsupported -> MmsDecode.Unsupported
        }
    } catch (_: RuntimeException) {
        MmsDecode.Malformed
    }
}
