package com.vibhavtiwari.smsorganizer.telephony

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Telephony
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import com.vibhavtiwari.smsorganizer.core.PartSendResult
import com.vibhavtiwari.smsorganizer.core.SmsSendStatus
import com.vibhavtiwari.smsorganizer.core.smsDestination
import java.util.UUID
import java.util.concurrent.Executors

internal data class SmsLine(val subscriptionId: Int, val label: String)

internal sealed interface SmsSubmission {
    data class Queued(val attemptId: String, val parts: Int) : SmsSubmission
    data object InvalidMessage : SmsSubmission
    data object ChooseActiveSim : SmsSubmission
    data object NotAuthorised : SmsSubmission
    data object CouldNotQueue : SmsSubmission
    data class OutcomeUnknown(val attemptId: String) : SmsSubmission
}

internal fun Context.hasSmsRole(): Boolean {
    val role = getSystemService(RoleManager::class.java) ?: return false
    return role.isRoleAvailable(RoleManager.ROLE_SMS) && role.isRoleHeld(RoleManager.ROLE_SMS)
}

internal class CarrierSmsSender(context: Context) {
    private val app = context.applicationContext

    fun hasSendingAccess(): Boolean = app.hasSmsRole() &&
        hasPermission(Manifest.permission.SEND_SMS) && hasPermission(Manifest.permission.READ_PHONE_STATE)

    /** No guessed default or slot-0 fallback, ever. */
    // Release manifest deliberately omits READ_PHONE_STATE until the native path can be enabled.
    @SuppressLint("MissingPermission")
    fun activeLines(): List<SmsLine> {
        if (!app.hasSmsRole() || !hasPermission(Manifest.permission.READ_PHONE_STATE)) return emptyList()
        return try {
            app.getSystemService(SubscriptionManager::class.java)?.activeSubscriptionInfoList.orEmpty()
                .filter {
                    SubscriptionManager.isValidSubscriptionId(it.subscriptionId) &&
                        it.subscriptionId != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
                }
                .map { SmsLine(it.subscriptionId, it.displayName?.toString()?.takeIf(String::isNotBlank) ?: "SIM ${it.simSlotIndex + 1}") }
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: UnsupportedOperationException) {
            emptyList()
        }
    }

    /** Check the chosen SIM and message without writing or contacting the carrier. */
    fun validateScheduledDraft(destination: String, message: String, subscriptionId: Int): SmsSubmission? {
        if (smsDestination(destination) == null || message.isBlank() || message.length > MAX_TEXT_CHARS) {
            return SmsSubmission.InvalidMessage
        }
        if (!hasSendingAccess()) return SmsSubmission.NotAuthorised
        if (activeLines().none { it.subscriptionId == subscriptionId }) return SmsSubmission.ChooseActiveSim
        val count = runCatching { managerForLine(subscriptionId)?.divideMessage(message)?.size }.getOrNull()
        return if (count != null && count in 1..MAX_SMS_PARTS) null else SmsSubmission.InvalidMessage
    }

    /**
     * To be called on an IO thread after a deliberately confirmed compose action. Queue in
     * the system provider before contacting the carrier. Do not retry if the outcome is unknown.
     */
    fun sendConfirmed(destination: String, message: String, subscriptionId: Int): SmsSubmission {
        val number = smsDestination(destination) ?: return SmsSubmission.InvalidMessage
        if (message.isBlank() || message.length > MAX_TEXT_CHARS) return SmsSubmission.InvalidMessage
        if (!hasSendingAccess()) return SmsSubmission.NotAuthorised
        if (activeLines().none { it.subscriptionId == subscriptionId }) return SmsSubmission.ChooseActiveSim

        val manager = try { managerForLine(subscriptionId) } catch (_: RuntimeException) { null }
            ?: return SmsSubmission.ChooseActiveSim
        val parts = try { manager.divideMessage(message) } catch (_: RuntimeException) { null }
            ?: return SmsSubmission.InvalidMessage
        if (parts.size !in 1..MAX_SMS_PARTS) return SmsSubmission.InvalidMessage

        val now = System.currentTimeMillis()
        val token = UUID.randomUUID().toString()
        val row = try {
            app.contentResolver.insert(Telephony.Sms.Outbox.CONTENT_URI, ContentValues().apply {
                put(Telephony.Sms.ADDRESS, number)
                put(Telephony.Sms.BODY, message)
                put(Telephony.Sms.DATE, now)
                put(Telephony.Sms.READ, 1)
                put(Telephony.Sms.SEEN, 1)
                put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
            })
        } catch (_: RuntimeException) { null } ?: return SmsSubmission.CouldNotQueue
        val rowId = row.lastPathSegment?.toLongOrNull() ?: return SmsSubmission.CouldNotQueue
        try {
            SmsSendJournal(app).use { it.start(token, rowId, parts.size, now) }
        } catch (_: RuntimeException) {
            markRowType(app, rowId, Telephony.Sms.MESSAGE_TYPE_FAILED)
            return SmsSubmission.CouldNotQueue
        }

        if (!app.hasSmsRole() || !hasPermission(Manifest.permission.SEND_SMS) ||
            activeLines().none { it.subscriptionId == subscriptionId }
        ) {
            markRowType(app, rowId, Telephony.Sms.MESSAGE_TYPE_FAILED)
            return SmsSubmission.NotAuthorised
        }
        return try {
            val callbacks = ArrayList(parts.indices.map { part ->
                val callback = Intent(app, SmsSendResultReceiver::class.java).apply {
                    action = SMS_SENT_PART
                    data = Uri.Builder().scheme("sms-organizer").authority("send").appendPath(token)
                        .appendPath(part.toString()).build()
                }
                PendingIntent.getBroadcast(app, 0, callback, PendingIntent.FLAG_IMMUTABLE)
            })
            if (parts.size == 1) manager.sendTextMessage(number, null, parts.single(), callbacks.single(), null)
            else manager.sendMultipartTextMessage(number, null, parts, callbacks, null)
            SmsSubmission.Queued(token, parts.size)
        } catch (_: RuntimeException) {
            runCatching { SmsSendJournal(app).use { it.uncertain(token) } }
            SmsSubmission.OutcomeUnknown(token)
        }
    }

    fun readStatus(attemptId: String): SmsSendStatus? = if (app.hasSmsRole()) {
        runCatching { SmsSendJournal(app).use { it.status(attemptId, System.currentTimeMillis())?.second } }.getOrNull()
    } else null

    private fun hasPermission(name: String): Boolean =
        app.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun managerForLine(id: Int): SmsManager? = if (Build.VERSION.SDK_INT >= 31)
        app.getSystemService(SmsManager::class.java)?.createForSubscriptionId(id)
    else SmsManager.getSmsManagerForSubscriptionId(id)

    private companion object {
        const val MAX_SMS_PARTS = 20
        const val MAX_TEXT_CHARS = 3_200
    }
}

private const val SMS_SENT_PART = "com.vibhavtiwari.smsorganizer.SMS_SENT_PART"

private fun markRowType(app: Context, rowId: Long, type: Int) {
    if (!app.hasSmsRole()) return
    runCatching {
        app.contentResolver.update(
            ContentUris.withAppendedId(Telephony.Sms.CONTENT_URI, rowId),
            ContentValues().apply { put(Telephony.Sms.TYPE, type) }, null, null,
        )
    }
}

/** Explicit pending-intent target only. The manifest does not export this receiver. */
class SmsSendResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val data = intent.data ?: return
        if (intent.action != SMS_SENT_PART || data.scheme != "sms-organizer" || data.authority != "send") return
        val segments = data.pathSegments
        if (segments.size != 2) return
        val token = segments[0]
        if (runCatching { UUID.fromString(token).toString() }.getOrNull() != token) return
        val part = segments[1].toIntOrNull() ?: return
        val outcome = when {
            resultCode == Activity.RESULT_OK -> PartSendResult.ACCEPTED
            resultCode > 0 -> PartSendResult.REJECTED
            else -> return
        }
        val pending = goAsync()
        val app = context.applicationContext
        callbackExecutor.execute {
            try {
                SmsSendJournal(app).use { journal ->
                    journal.addCallback(token, part, outcome)
                    val (row, status) = journal.status(token, System.currentTimeMillis()) ?: return@use
                    when (status) {
                        SmsSendStatus.SENT -> markRowType(app, row.providerRow, Telephony.Sms.MESSAGE_TYPE_SENT)
                        SmsSendStatus.FAILED -> markRowType(app, row.providerRow, Telephony.Sms.MESSAGE_TYPE_FAILED)
                        else -> Unit // Leave the outbox row and preserve per-part status.
                    }
                }
            } catch (_: RuntimeException) {
                // Missing callback becomes uncertain; never retry or log message data.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val callbackExecutor = Executors.newSingleThreadExecutor()
    }
}
