package com.vibhavtiwari.smsorganizer

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Telephony
import com.vibhavtiwari.smsorganizer.core.MessageInput
import com.vibhavtiwari.smsorganizer.core.MessageSource
import com.vibhavtiwari.smsorganizer.core.classify
import com.vibhavtiwari.smsorganizer.telephony.CarrierSmsSender
import com.vibhavtiwari.smsorganizer.telephony.DeviceSmsPage
import com.vibhavtiwari.smsorganizer.telephony.DeviceSmsStore
import com.vibhavtiwari.smsorganizer.telephony.SmsPageKey
import com.vibhavtiwari.smsorganizer.telephony.SmsStoreResult
import com.vibhavtiwari.smsorganizer.telephony.hasSmsRole

internal class LiveSmsSession(context: Context) {
    private val app = context.applicationContext
    private val store = DeviceSmsStore(app)
    val sender = CarrierSmsSender(app)

    fun canRead(): Boolean = app.hasSmsRole() &&
        app.checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED

    fun page(before: SmsPageKey? = null): SmsStoreResult<DeviceSmsPage> = store.readPage(before)
}

internal fun DeviceSmsPage.toInboxMessages(): List<InboxMessage> = messages.map {
    val address = it.senderOrRecipient.orEmpty()
    val body = it.body.orEmpty()
    val source = when {
        it.type == Telephony.Sms.MESSAGE_TYPE_SENT -> MessageSource.KNOWN_PERSON
        serviceAddress.matches(address) -> MessageSource.SERVICE
        else -> MessageSource.UNKNOWN
    }
    InboxMessage(it.id, address.ifBlank { "Unknown sender" }, body, it.dateMillis, classify(MessageInput(body, source)).category)
}

private val serviceAddress = Regex("""[A-Z]{2}-[A-Z0-9]{6}(?:-[A-Z])?""", RegexOption.IGNORE_CASE)
