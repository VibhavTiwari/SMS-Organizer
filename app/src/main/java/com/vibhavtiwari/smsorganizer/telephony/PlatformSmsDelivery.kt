package com.vibhavtiwari.smsorganizer.telephony

import android.content.Intent
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.vibhavtiwari.smsorganizer.core.SmsAssembly
import com.vibhavtiwari.smsorganizer.core.SmsSegment
import com.vibhavtiwari.smsorganizer.core.assembleIncomingSms

internal sealed interface SmsDeliveryResult {
    data class Stored(val row: Uri) : SmsDeliveryResult
    data object WrongAction : SmsDeliveryResult
    data object Malformed : SmsDeliveryResult
    data object StoreUnavailable : SmsDeliveryResult
}

/**
 * Persist an SMS only from Android's protected default-app delivery callback, never from
 * an exported activity or arbitrary shared intent. Not registered until MMS is ready.
 */
internal fun persistPlatformSmsDelivery(
    intent: Intent,
    store: DeviceSmsStore,
    receivedAtMillis: Long,
): SmsDeliveryResult {
    if (intent.action != Telephony.Sms.Intents.SMS_DELIVER_ACTION) return SmsDeliveryResult.WrongAction
    val parts = try {
        Telephony.Sms.Intents.getMessagesFromIntent(intent)?.map {
            if (it == null) return SmsDeliveryResult.Malformed
            SmsSegment(it.originatingAddress, it.messageBody, it.timestampMillis)
        } ?: return SmsDeliveryResult.Malformed
    } catch (_: RuntimeException) {
        return SmsDeliveryResult.Malformed
    }
    val decoded = assembleIncomingSms(parts) as? SmsAssembly.Accepted
        ?: return SmsDeliveryResult.Malformed
    val subscriptionId = subscriptionFromDelivery(intent)
    return when (val result = store.insertIncoming(decoded.message, receivedAtMillis, subscriptionId)) {
        is SmsStoreResult.Ok -> SmsDeliveryResult.Stored(result.value)
        else -> SmsDeliveryResult.StoreUnavailable
    }
}

// SMS_DELIVER documents a Long; some framework and OEM builds put an Int instead.
@Suppress("DEPRECATION")
private fun subscriptionFromDelivery(intent: Intent): Int? {
    fun parse(extra: String): Int? {
        val raw = intent.extras?.get(extra)
        val value = when (raw) {
            is Int -> raw.toLong()
            is Long -> raw
            else -> return null
        }
        return value.takeIf { it in 0 until Int.MAX_VALUE.toLong() }?.toInt()
    }

    val platform = parse(SubscriptionManager.EXTRA_SUBSCRIPTION_INDEX)
    val legacy = parse("subscription")
    return if (platform != null && legacy != null && platform != legacy) null else platform ?: legacy
}
