package com.vibhavtiwari.smsorganizer.otp

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import com.vibhavtiwari.smsorganizer.MainActivity
import com.vibhavtiwari.smsorganizer.R
import com.vibhavtiwari.smsorganizer.core.IncomingSms
import com.vibhavtiwari.smsorganizer.core.extractNotificationOtp
import com.vibhavtiwari.smsorganizer.telephony.hasSmsRole

internal class OtpNotifications(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NotificationManager::class.java)
    private val prefs = app.getSharedPreferences("otp_notifications", Context.MODE_PRIVATE)

    fun showCode(): Boolean = prefs.getBoolean(SHOW_CODE, false)

    fun setShowCode(enabled: Boolean): Unit = synchronized(changeLock) {
        prefs.edit().putBoolean(SHOW_CODE, enabled).apply()
        if (!enabled) manager?.cancel(TAG, ID)
    }

    fun createChannel() {
        manager?.createNotificationChannel(
            NotificationChannel(CHANNEL, "Verification codes", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Alerts when a verification code arrives by SMS"
                setShowBadge(false)
            },
        )
    }

    fun canNotify(): Boolean = permissionGranted() && manager?.areNotificationsEnabled() == true &&
        manager.getNotificationChannel(CHANNEL)?.importance != NotificationManager.IMPORTANCE_NONE

    fun permissionGranted(): Boolean = Build.VERSION.SDK_INT < 33 ||
        app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun settingsIntent(): Intent = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, app.packageName)
        .putExtra(Settings.EXTRA_CHANNEL_ID, CHANNEL)

    /** Called only after the platform SMS was stored, never on imported, sent, or replayed messages. */
    @SuppressLint("MissingPermission") // Capability is re-checked immediately before building and posting.
    fun post(message: IncomingSms) {
        if (!app.hasSmsRole() || !permissionGranted()) return
        val code = extractNotificationOtp(message.sender, message.text) ?: return
        synchronized(changeLock) {
            if (!app.hasSmsRole()) return
            runCatching {
                createChannel()
                if (!canNotify()) return@runCatching
                val open = PendingIntent.getActivity(
                    app, 0, Intent(app, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                fun base() = Notification.Builder(app, CHANNEL)
                    .setSmallIcon(R.drawable.ic_otp_notification)
                    .setCategory(Notification.CATEGORY_MESSAGE)
                    .setShowWhen(false)
                    .setLocalOnly(true)
                    .setTimeoutAfter(TIMEOUT_MILLIS)
                    .setAutoCancel(true)
                    .setContentIntent(open)

                val generic = base()
                    .setVisibility(Notification.VISIBILITY_PRIVATE)
                    .setContentTitle("Verification code received")
                    .setContentText("Open SMS Organizer to see it.")
                    .build()
                // Omit the code entirely without opt-in, regardless of the phone's preview preferences.
                val notification = if (showCode()) base()
                    .setContentTitle("Verification code: $code")
                    .setVisibility(Notification.VISIBILITY_PUBLIC)
                    .setPublicVersion(generic)
                    .build() else generic
                manager?.notify(TAG, ID, notification)
            } // Permission/channel can change mid-delivery; the SMS is already in the inbox.
        }
    }

    private companion object {
        const val CHANNEL = "otp_codes"
        const val SHOW_CODE = "show_code_on_lock_screen"
        const val TAG = "otp"
        const val ID = 1
        const val TIMEOUT_MILLIS = 90_000L
        val changeLock = Any()
    }
}
