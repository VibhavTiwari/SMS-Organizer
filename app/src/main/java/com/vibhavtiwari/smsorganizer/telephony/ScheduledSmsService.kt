package com.vibhavtiwari.smsorganizer.telephony

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.vibhavtiwari.smsorganizer.core.ScheduledSmsTiming
import com.vibhavtiwari.smsorganizer.core.SmsSendStatus
import com.vibhavtiwari.smsorganizer.core.isValidSmsSchedule
import com.vibhavtiwari.smsorganizer.core.scheduledSmsTiming
import com.vibhavtiwari.smsorganizer.core.smsDestination
import java.util.UUID
import java.util.concurrent.Executors

internal data class ScheduledSmsItem(val message: ScheduledSms, val status: String, val manageable: Boolean)

internal class ScheduledSmsService(context: Context) {
    private val app = context.applicationContext
    private val sender = CarrierSmsSender(app)
    private val alarms = app.getSystemService(AlarmManager::class.java)

    fun canSetAlarms(): Boolean = alarms != null && (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms())

    fun permissionSettings(): Intent? = if (Build.VERSION.SDK_INT >= 31) Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", app.packageName, null),
    ) else null

    /** Returns null on success, or a user-facing reason. All database/carrier calls run off main. */
    fun save(number: String, body: String, simId: Int, at: Long, editingId: String? = null): String? =
        synchronized(lock) {
            val now = System.currentTimeMillis()
            if (!isValidSmsSchedule(at, now)) return@synchronized "Choose a future time within 366 days."
            val destination = smsDestination(number) ?: return@synchronized "Check the recipient’s number."
            if (!canSetAlarms()) return@synchronized "Allow Alarms & reminders in Android settings, then try again."
            if (!app.hasSmsRole()) return@synchronized "SMS Organizer must be your default texting app."
            val line = sender.activeLines().firstOrNull { it.subscriptionId == simId }
                ?: return@synchronized "That SIM is no longer active. Choose an active SIM."
            val invalid = sender.validateScheduledDraft(destination, body, simId)
            if (invalid != null) return@synchronized when (invalid) {
                SmsSubmission.NotAuthorised -> "SMS access changed. Nothing was scheduled."
                SmsSubmission.ChooseActiveSim -> "That SIM is no longer active. Choose an active SIM."
                else -> "Check the message and shorten it if needed."
            }
            // New alarm identity per revision: if this edit fails, the old alarm remains valid.
            val row = ScheduledSms(UUID.randomUUID().toString(), destination, body, simId, line.label, at)
            try {
                val saved = ScheduledSmsStore(app).use { it.put(row, editingId) { arm(row) } }
                if (saved) {
                    if (editingId != null) runCatching { cancelAlarm(editingId) }
                    null
                } else if (editingId != null) "That message is no longer pending."
                else "Scheduled is full. Clear an old entry before adding another."
            } catch (_: RuntimeException) {
                "Couldn’t save the schedule. Refresh Scheduled before trying again."
            }
        }

    fun items(): List<ScheduledSmsItem> = synchronized(lock) {
        val now = System.currentTimeMillis()
        val enabled = canSetAlarms() && sender.hasSendingAccess()
        ScheduledSmsStore(app).use { store ->
            store.list().map { row ->
                val status = when (row.state) {
                    ScheduledState.PENDING -> if (scheduledSmsTiming(row.at, now) == ScheduledSmsTiming.MISSED)
                        "Missed. It won’t send." else if (enabled) "Scheduled" else "Paused. Check texting and alarm access."
                    ScheduledState.CLAIMED -> if (now - row.claimedAt in 0..CLAIMED_TIMEOUT)
                        "Submitting to carrier…" else "Send status unknown. Check before sending again."
                    ScheduledState.SUBMITTED -> when (sender.readStatus(row.attempt.orEmpty())) {
                        SmsSendStatus.SENT -> "Sent. Delivery isn’t confirmed."
                        SmsSendStatus.WAITING -> "Waiting for carrier…"
                        SmsSendStatus.FAILED -> "Carrier reported a send failure."
                        SmsSendStatus.PARTIALLY_SENT -> "Part of the SMS was sent. Check before sending again."
                        else -> "Send status unknown. Check before sending again."
                    }
                    ScheduledState.UNKNOWN -> "Send status unknown. Check before sending again."
                    ScheduledState.FAILED -> "Couldn’t submit SMS. No automatic retry."
                    ScheduledState.BLOCKED -> "Not sent. Texting access, alarm access or the selected SIM was unavailable."
                    ScheduledState.MISSED -> "Missed. The phone couldn’t send it in time."
                }
                val activeClaim = row.state == ScheduledState.CLAIMED && now - row.claimedAt in 0..CLAIMED_TIMEOUT
                val awaitingCarrier = row.state == ScheduledState.SUBMITTED && status == "Waiting for carrier…"
                ScheduledSmsItem(row, status, !activeClaim && !awaitingCarrier)
            }
        }
    }

    fun remove(row: ScheduledSms): Boolean = synchronized(lock) {
        ScheduledSmsStore(app).use { store ->
            // A stale "Cancel" never deletes the receipt of a message that has already started.
            val deleted = store.delete(row.id, row.state)
            if (deleted && row.state == ScheduledState.PENDING) {
                runCatching { cancelAlarm(row.id) }
            }
            deleted
        }
    }

    /** Android clears alarms at reboot; rebuilding never invokes the carrier directly. */
    fun restore() = synchronized(lock) {
        ScheduledSmsStore(app).use { store ->
            val now = System.currentTimeMillis()
            for (row in store.list().filter { it.state == ScheduledState.PENDING }) {
                if (scheduledSmsTiming(row.at, now) == ScheduledSmsTiming.MISSED) {
                    store.transition(row.id, row.state, ScheduledState.MISSED)
                    runCatching { cancelAlarm(row.id) }
                } else if (canSetAlarms() && sender.hasSendingAccess()) {
                    runCatching { arm(row) }
                }
            }
        }
    }

    fun dispatch(id: String) = synchronized(lock) {
        ScheduledSmsStore(app).use { store ->
            val row = store.list(id).singleOrNull() ?: return@use
            if (row.state != ScheduledState.PENDING) return@use
            val now = System.currentTimeMillis()
            when (scheduledSmsTiming(row.at, now)) {
                ScheduledSmsTiming.UPCOMING -> {
                    // An old alarm can race a reschedule or clock adjustment.
                    if (canSetAlarms() && sender.hasSendingAccess()) arm(row)
                    return@use
                }
                ScheduledSmsTiming.MISSED -> {
                    store.transition(id, row.state, ScheduledState.MISSED)
                    return@use
                }
                ScheduledSmsTiming.DUE -> Unit
            }
            if (!canSetAlarms() || !sender.hasSendingAccess()) {
                store.transition(id, row.state, ScheduledState.BLOCKED)
                return@use
            }
            // Durable claim before contacting the carrier. Process death here is ambiguous, never retried.
            if (!store.transition(id, row.state, ScheduledState.CLAIMED, now)) return@use
            val outcome = try {
                sender.sendConfirmed(row.number, row.body, row.sim)
            } catch (_: RuntimeException) {
                null
            }
            val (state, attempt) = when (outcome) {
                is SmsSubmission.Queued -> ScheduledState.SUBMITTED to outcome.attemptId
                is SmsSubmission.OutcomeUnknown -> ScheduledState.UNKNOWN to outcome.attemptId
                SmsSubmission.NotAuthorised, SmsSubmission.ChooseActiveSim -> ScheduledState.BLOCKED to null
                SmsSubmission.CouldNotQueue, SmsSubmission.InvalidMessage -> ScheduledState.FAILED to null
                null -> ScheduledState.UNKNOWN to null
            }
            store.transition(id, ScheduledState.CLAIMED, state, attempt = attempt)
        }
    }

    private fun cancelAlarm(id: String) {
        val operation = PendingIntent.getBroadcast(app, 0, fireIntent(id),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE)
        if (operation != null) {
            alarms?.cancel(operation)
            operation.cancel()
        }
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun arm(row: ScheduledSms) {
        if (!canSetAlarms()) throw SecurityException("Alarms & reminders access required")
        checkNotNull(alarms).setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, row.at,
            PendingIntent.getBroadcast(app, 0, fireIntent(row.id), PendingIntent.FLAG_IMMUTABLE))
    }

    private fun fireIntent(id: String): Intent =
        Intent(app, ScheduledSmsReceiver::class.java).apply {
            action = SCHEDULE_FIRE
            data = Uri.Builder().scheme("sms-organizer").authority("schedule").appendPath(id).build()
        }

    private companion object {
        val lock = Any()
        const val CLAIMED_TIMEOUT = 5 * 60 * 1000L
    }
}

private const val SCHEDULE_FIRE = "com.vibhavtiwari.smsorganizer.SCHEDULE_FIRE"

class ScheduledSmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = when (intent.action) {
            SCHEDULE_FIRE -> {
                val data = intent.data ?: return
                if (data.scheme != "sms-organizer" || data.authority != "schedule") return
                val token = data.pathSegments.singleOrNull() ?: return
                if (runCatching { UUID.fromString(token).toString() }.getOrNull() != token) return
                token
            }
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED -> null
            else -> return
        }
        val pending = goAsync()
        val app = context.applicationContext
        executor.execute {
            try {
                val service = ScheduledSmsService(app)
                if (id == null) service.restore() else service.dispatch(id)
            } catch (_: RuntimeException) {
                // Never log message data or retry a possibly submitted message.
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        val executor = Executors.newSingleThreadExecutor()
    }
}
