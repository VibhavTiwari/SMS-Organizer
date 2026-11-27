package com.vibhavtiwari.smsorganizer.reminders

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import com.vibhavtiwari.smsorganizer.MainActivity
import com.vibhavtiwari.smsorganizer.R
import com.vibhavtiwari.smsorganizer.core.MessageReminderCandidate
import com.vibhavtiwari.smsorganizer.core.MessageReminderKind
import java.time.Instant
import java.time.ZoneId
import org.json.JSONObject
import java.util.concurrent.Executors

internal data class ConfirmedReminder(
    val key: String,
    val kind: MessageReminderKind,
    val eventAt: Long,
    val remindAt: Long,
    val expiresAt: Long,
    val zone: String,
)

internal enum class ReminderConfirmation { SAVED, ALREADY_SET, NOTIFICATIONS_OFF, EXPIRED, FAILED }

internal class ReminderService(context: Context) {
    private val app = context.applicationContext
    private val store = app.getSharedPreferences("confirmed_message_reminders", Context.MODE_PRIVATE)
    private val alarms = app.getSystemService(AlarmManager::class.java)
    private val notifications = app.getSystemService(NotificationManager::class.java)

    fun notificationsEnabled(): Boolean {
        ensureChannel()
        return notifications.areNotificationsEnabled() &&
            notifications.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE
    }

    fun exactEnabled(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()

    fun confirm(sourceKey: String, candidate: MessageReminderCandidate): ReminderConfirmation = synchronized(lock) {
        val item = ConfirmedReminder(
            key = sourceKey,
            kind = candidate.kind,
            eventAt = candidate.eventAt.atZone(candidate.zone).toInstant().toEpochMilli(),
            remindAt = candidate.remindAt.atZone(candidate.zone).toInstant().toEpochMilli(),
            expiresAt = candidate.expiry.atZone(candidate.zone).toInstant().toEpochMilli(),
            zone = candidate.zone.id,
        )
        if (item.remindAt <= System.currentTimeMillis()) return@synchronized ReminderConfirmation.EXPIRED
        if (!notificationsEnabled()) return@synchronized ReminderConfirmation.NOTIFICATIONS_OFF
        if (store.contains(sourceKey)) return@synchronized ReminderConfirmation.ALREADY_SET
        val payload = JSONObject().apply {
            put("kind", item.kind.name)
            put("eventAt", item.eventAt)
            put("remindAt", item.remindAt)
            put("expiresAt", item.expiresAt)
            put("zone", item.zone)
        }.toString()
        if (!store.edit().putString(item.key, payload).commit()) return@synchronized ReminderConfirmation.FAILED
        if (!arm(item)) {
            store.edit().remove(item.key).commit()
            return@synchronized ReminderConfirmation.FAILED
        }
        ReminderConfirmation.SAVED
    }

    fun upcoming(): List<ConfirmedReminder> = synchronized(lock) {
        val now = System.currentTimeMillis()
        entries().filter { it.expiresAt > now }.sortedBy { it.remindAt }
    }

    fun cancel(key: String): Boolean = synchronized(lock) {
        if (!store.edit().remove(key).commit()) return@synchronized false
        alarms.cancel(alarmIntent(key))
        notifications.cancel(key, NOTIFICATION_ID)
        true
    }

    fun restore() = synchronized(lock) {
        val now = System.currentTimeMillis()
        for (item in entries()) {
            if (item.expiresAt <= now) cancel(item.key)
            else arm(item)
        }
    }

    fun deliver(key: String) = synchronized(lock) {
        val item = read(key) ?: return@synchronized
        val now = System.currentTimeMillis()
        if (now >= item.expiresAt) {
            cancel(key)
            return@synchronized
        }
        if (now < item.remindAt) {
            arm(item)
            return@synchronized
        }
        if (!notificationsEnabled()) return@synchronized
        val title = when (item.kind) {
            MessageReminderKind.BILL_DUE -> "Bill due date reminder"
            MessageReminderKind.TRAIN_DEPARTURE -> "Train departure reminder"
        }
        val open = PendingIntent.getActivity(
            app, 0, Intent(app, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val note = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.drawable.ic_otp_notification)
            .setContentTitle(title)
            .setContentText("Open SMS Organizer to check your message.")
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .setTimeoutAfter(item.expiresAt - now)
            .build()
        try {
            notifications.notify(item.key, NOTIFICATION_ID, note)
            store.edit().remove(item.key).commit()
        } catch (_: SecurityException) {
            // Permission can change between checking and posting. Keep the row for restoration.
        }
    }

    private fun ensureChannel() {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL, "Bill and train reminders", NotificationManager.IMPORTANCE_DEFAULT)
                .apply { lockscreenVisibility = Notification.VISIBILITY_PRIVATE },
        )
    }

    private fun arm(item: ConfirmedReminder): Boolean {
        val at = maxOf(item.remindAt, System.currentTimeMillis())
        val intent = alarmIntent(item.key)
        return try {
            if (exactEnabled()) {
                try {
                    alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                } catch (_: SecurityException) {
                    alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
                }
            } else {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, intent)
            }
            true
        } catch (_: IllegalStateException) {
            false
        } catch (_: SecurityException) {
            false
        }
    }

    private fun alarmIntent(key: String) = PendingIntent.getBroadcast(
        app,
        0,
        Intent(app, ReminderReceiver::class.java).apply {
            action = ACTION
            data = Uri.Builder().scheme("smsorganizer").authority("reminder").appendPath(key).build()
        },
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun entries() = store.all.keys.mapNotNull(::read)

    private fun read(key: String): ConfirmedReminder? = runCatching {
        val content = store.getString(key, null) ?: return null
        val json = JSONObject(content)
        val item = ConfirmedReminder(
            key,
            MessageReminderKind.valueOf(json.getString("kind")),
            json.getLong("eventAt"),
            json.getLong("remindAt"),
            json.getLong("expiresAt"),
            json.getString("zone"),
        )
        ZoneId.of(item.zone)
        Instant.ofEpochMilli(item.remindAt)
        require(item.remindAt <= item.eventAt && item.eventAt <= item.expiresAt)
        item
    }.getOrNull()

    companion object {
        internal const val ACTION = "com.vibhavtiwari.smsorganizer.FIRE_MESSAGE_REMINDER"
        internal const val CHANNEL = "confirmed_sms_reminders"
        private const val NOTIFICATION_ID = 100
        private val lock = Any()
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != ReminderService.ACTION && action !in restoreActions) return
        val pending = goAsync()
        executor.execute {
            try {
                val service = ReminderService(context)
                if (action == ReminderService.ACTION) {
                    intent.data?.lastPathSegment?.let(service::deliver)
                } else {
                    service.restore()
                }
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private val restoreActions = setOf(
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_TIME_CHANGED,
            AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED,
        )
        private val executor = Executors.newSingleThreadExecutor()
    }
}
