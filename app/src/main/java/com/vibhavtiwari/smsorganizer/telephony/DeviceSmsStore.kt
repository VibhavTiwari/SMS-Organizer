package com.vibhavtiwari.smsorganizer.telephony

import android.Manifest
import android.app.role.RoleManager
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.sqlite.SQLiteException
import android.net.Uri
import android.provider.Telephony
import android.telephony.SubscriptionManager
import com.vibhavtiwari.smsorganizer.core.IncomingSms

internal data class SmsPageKey(val receivedAtMillis: Long, val id: Long)

internal data class DeviceSms(
    val id: Long,
    val threadId: Long,
    val senderOrRecipient: String?,
    val body: String?,
    val dateMillis: Long,
    val read: Boolean,
    val type: Int,
    val subscriptionId: Int?,
)

internal data class DeviceSmsPage(val messages: List<DeviceSms>, val next: SmsPageKey?)

internal sealed interface SmsStoreResult<out T> {
    data class Ok<T>(val value: T) : SmsStoreResult<T>
    data object NotDefault : SmsStoreResult<Nothing>
    data object PermissionMissing : SmsStoreResult<Nothing>
    data object Unavailable : SmsStoreResult<Nothing>
}

/**
 * Synchronous Android-only adapter; invoke on an IO thread. The Android Telephony provider
 * owns actual messages; this class doesn't keep a parallel database or hold message content.
 */
internal class DeviceSmsStore(context: Context) {
    private val app = context.applicationContext
    private val resolver = app.contentResolver
    private val roleManager = app.getSystemService(RoleManager::class.java)

    private fun isDefault(): Boolean = roleManager?.let {
        it.isRoleAvailable(RoleManager.ROLE_SMS) && it.isRoleHeld(RoleManager.ROLE_SMS)
    } == true

    private fun canRead(): Boolean =
        app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun readPage(
        before: SmsPageKey? = null,
        threadId: Long? = null,
        pageSize: Int = 100,
    ): SmsStoreResult<DeviceSmsPage> {
        require(pageSize in 1..200)
        require(threadId == null || threadId >= 0)
        return access(requireRead = true) {
            val clauses = ArrayList<String>()
            val args = ArrayList<String>()
            if (threadId != null) {
                clauses += "${Telephony.Sms.THREAD_ID} = ?"
                args += threadId.toString()
            }
            if (before != null) {
                val date = Telephony.Sms.DATE
                val id = Telephony.Sms._ID
                clauses += "($date < ? OR ($date = ? AND $id < ?))"
                args += listOf(before.receivedAtMillis.toString(), before.receivedAtMillis.toString(), before.id.toString())
            }
            val cursor = resolver.query(
                Telephony.Sms.CONTENT_URI,
                projection,
                clauses.joinToString(" AND ").ifEmpty { null },
                args.toTypedArray().takeIf { it.isNotEmpty() },
                "${Telephony.Sms.DATE} DESC, ${Telephony.Sms._ID} DESC",
            ) ?: return@access SmsStoreResult.Unavailable
            cursor.use {
                val rows = ArrayList<DeviceSms>(pageSize)
                while (rows.size < pageSize && it.moveToNext()) rows += readRow(it)
                val next = if (rows.size == pageSize && it.moveToNext()) {
                    rows.last().let { last -> SmsPageKey(last.dateMillis, last.id) }
                } else null
                SmsStoreResult.Ok(DeviceSmsPage(rows, next))
            }
        }
    }

    /** Called only from a future protected SMS_DELIVER receiver after validation. */
    fun insertIncoming(
        message: IncomingSms,
        receivedAtMillis: Long,
        subscriptionId: Int?,
    ): SmsStoreResult<Uri> {
        require(message.sender.isNotBlank() && message.text.isNotEmpty())
        require(receivedAtMillis >= 0)
        return access {
            val values = ContentValues().apply {
                put(Telephony.Sms.ADDRESS, message.sender)
                put(Telephony.Sms.BODY, message.text)
                put(Telephony.Sms.DATE, receivedAtMillis)
                put(Telephony.Sms.DATE_SENT, message.sentAtMillis)
                put(Telephony.Sms.READ, 0)
                put(Telephony.Sms.SEEN, 0)
                if (subscriptionId != null && isConcreteSubscription(subscriptionId)) {
                    put(Telephony.Sms.SUBSCRIPTION_ID, subscriptionId)
                }
            }
            val uri = resolver.insert(Telephony.Sms.Inbox.CONTENT_URI, values)
                ?: return@access SmsStoreResult.Unavailable
            SmsStoreResult.Ok(uri)
        }
    }

    private inline fun <T> access(requireRead: Boolean = false, writeOrRead: () -> SmsStoreResult<T>): SmsStoreResult<T> {
        if (!isDefault()) return SmsStoreResult.NotDefault
        if (requireRead && !canRead()) return SmsStoreResult.PermissionMissing
        return try {
            val result = writeOrRead()
            when {
                !requireRead -> result
                !isDefault() -> SmsStoreResult.NotDefault
                !canRead() -> SmsStoreResult.PermissionMissing
                else -> result
            }
        } catch (_: SecurityException) {
            if (isDefault()) SmsStoreResult.PermissionMissing else SmsStoreResult.NotDefault
        } catch (_: SQLiteException) {
            SmsStoreResult.Unavailable
        } catch (_: IllegalArgumentException) {
            SmsStoreResult.Unavailable
        }
    }

    private fun isConcreteSubscription(id: Int): Boolean =
        SubscriptionManager.isValidSubscriptionId(id) && id != SubscriptionManager.DEFAULT_SUBSCRIPTION_ID

    private fun readRow(cursor: Cursor): DeviceSms = DeviceSms(
        id = cursor.getLong(0),
        threadId = cursor.getLong(1),
        senderOrRecipient = cursor.getString(2),
        body = cursor.getString(3),
        dateMillis = cursor.getLong(4),
        read = cursor.getInt(5) != 0,
        type = cursor.getInt(6),
        subscriptionId = cursor.takeUnless { it.isNull(7) }?.getInt(7)
            ?.takeIf(::isConcreteSubscription),
    )

    private companion object {
        val projection = arrayOf(
            Telephony.Sms._ID,
            Telephony.Sms.THREAD_ID,
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY,
            Telephony.Sms.DATE,
            Telephony.Sms.READ,
            Telephony.Sms.TYPE,
            Telephony.Sms.SUBSCRIPTION_ID,
        )
    }
}
