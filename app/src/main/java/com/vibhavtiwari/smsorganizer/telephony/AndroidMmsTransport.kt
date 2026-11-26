package com.vibhavtiwari.smsorganizer.telephony

import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.Context
import android.net.Uri
import android.os.Build
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.core.content.FileProvider
import com.vibhavtiwari.smsorganizer.core.MAX_MMS_PDU_BYTES
import com.vibhavtiwari.smsorganizer.core.MmsDecode
import com.vibhavtiwari.smsorganizer.core.MmsPart
import com.vibhavtiwari.smsorganizer.core.RetrievedMms
import com.vibhavtiwari.smsorganizer.core.decodeMmsRetrieve
import com.vibhavtiwari.smsorganizer.core.encodeMmsSend
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.URI
import java.util.UUID

internal sealed interface MmsEnqueue {
    data class Queued(val transferId: String) : MmsEnqueue
    data object Unavailable : MmsEnqueue
    data object Rejected : MmsEnqueue
}

/**
 * Carrier boundary only. Intentionally not registered or exposed by the development build.
 * Future callers must use unique explicit callbacks to a private receiver and retain the transfer
 * ID per operation. A queued operation does not mean it was sent or received.
 */
internal class AndroidMmsTransport(context: Context) {
    private val app = context.applicationContext
    private val authority = "${app.packageName}.mms"
    private val root = File(app.cacheDir, "mms-pdu")

    fun newTransferId(): String = UUID.randomUUID().toString()

    fun enqueueSend(
        transferId: String,
        subscriptionId: Int,
        recipients: List<String>,
        parts: List<MmsPart>,
        callback: PendingIntent,
    ): MmsEnqueue = enqueue(transferId, subscriptionId, callback) { manager, file, uri ->
        val pdu = encodeMmsSend(transferId, recipients, parts)
        val max = manager.carrierConfigValues.getInt(SmsManager.MMS_CONFIG_MAX_MESSAGE_SIZE, MAX_MMS_PDU_BYTES)
        require(pdu.size <= (max.takeIf { it > 0 } ?: MAX_MMS_PDU_BYTES))
        file.outputStream().use { it.write(pdu) }
        manager.sendMultimediaMessage(app, uri, null, null, callback)
    }

    // Accept only notifications parsed from Android's protected WAP_PUSH_DELIVER, not shared URLs.
    fun enqueueDownload(
        transferId: String,
        delivery: DeliveredMmsNotification,
        callback: PendingIntent,
    ): MmsEnqueue = enqueue(transferId, delivery.subscriptionId, callback) { manager, _, uri ->
        val notification = delivery.notification
        require(notification.declaredSize in 1..MAX_MMS_PDU_BYTES.toLong())
        val spec = URI(notification.contentLocation)
        require(spec.scheme.equals("http", true) || spec.scheme.equals("https", true))
        require(!spec.host.isNullOrBlank() && spec.rawUserInfo == null && spec.rawFragment == null)
        // Route only through the platform's per-carrier MMS service, never an app HTTP client.
        manager.downloadMultimediaMessage(app, spec.toASCIIString(), uri, null, callback)
    }

    /** Read only the named operation, with no path fields accepted from the carrier. Never log PDU data. */
    fun readDownloaded(transferId: String): MmsDecode<RetrievedMms> {
        if (!isDefaultSms()) return MmsDecode.Unsupported
        return try {
            val file = fileFor(transferId)
            if (!file.isFile || file.length() !in 1..MAX_MMS_PDU_BYTES.toLong()) return MmsDecode.Unsupported
            val bytes = file.inputStream().use { input ->
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(8192)
                while (true) {
                    val count = input.read(chunk)
                    if (count == -1) break
                    if (count > MAX_MMS_PDU_BYTES - output.size()) return MmsDecode.Unsupported
                    output.write(chunk, 0, count)
                }
                output.toByteArray()
            }
            if (!isDefaultSms()) return MmsDecode.Unsupported
            decodeMmsRetrieve(bytes)
        } catch (_: IOException) {
            MmsDecode.Malformed
        } catch (_: IllegalArgumentException) {
            MmsDecode.Malformed
        } catch (_: SecurityException) {
            MmsDecode.Unsupported
        }
    }

    /** Call after handling the result; don't delete unsupported inbound content before durable handoff. */
    fun discardTransfer(transferId: String): Boolean = fileFor(transferId).delete()

    private inline fun enqueue(
        transferId: String,
        subscriptionId: Int,
        callback: PendingIntent,
        operation: (SmsManager, File, Uri) -> Unit,
    ): MmsEnqueue {
        if (!isDefaultSms()) return MmsEnqueue.Unavailable
        if (callback.creatorPackage != app.packageName || callback.creatorUid != app.applicationInfo.uid ||
            !SubscriptionManager.isValidSubscriptionId(subscriptionId) ||
            subscriptionId == SubscriptionManager.DEFAULT_SUBSCRIPTION_ID
        ) return MmsEnqueue.Rejected
        if (Build.VERSION.SDK_INT >= 31 && !callback.isImmutable) return MmsEnqueue.Rejected
        var staging: File? = null
        return try {
            val manager = manager(subscriptionId) ?: return MmsEnqueue.Unavailable
            check(root.isDirectory || root.mkdir())
            val file = fileFor(transferId)
            if (!file.createNewFile()) return MmsEnqueue.Rejected
            staging = file
            val uri = FileProvider.getUriForFile(app, authority, file)
            if (!isDefaultSms()) {
                file.delete()
                return MmsEnqueue.Unavailable
            }
            operation(manager, file, uri)
            MmsEnqueue.Queued(transferId)
        } catch (_: IllegalArgumentException) {
            staging?.delete()
            MmsEnqueue.Rejected
        } catch (_: java.net.URISyntaxException) {
            staging?.delete()
            MmsEnqueue.Rejected
        } catch (_: IOException) {
            staging?.delete()
            MmsEnqueue.Unavailable
        } catch (_: IllegalStateException) {
            staging?.delete()
            MmsEnqueue.Unavailable
        } catch (_: UnsupportedOperationException) {
            staging?.delete()
            MmsEnqueue.Unavailable
        } catch (_: SecurityException) {
            staging?.delete()
            MmsEnqueue.Unavailable
        }
    }

    private fun fileFor(transferId: String): File {
        require(UUID.fromString(transferId).toString() == transferId)
        return File(root, "$transferId.pdu")
    }

    private fun isDefaultSms(): Boolean =
        app.getSystemService(RoleManager::class.java)?.isRoleHeld(RoleManager.ROLE_SMS) == true

    @Suppress("DEPRECATION")
    private fun manager(id: Int): SmsManager? =
        if (Build.VERSION.SDK_INT >= 31) app.getSystemService(SmsManager::class.java)?.createForSubscriptionId(id)
        else SmsManager.getSmsManagerForSubscriptionId(id)
}
