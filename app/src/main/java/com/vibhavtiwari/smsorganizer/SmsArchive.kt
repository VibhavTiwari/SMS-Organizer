package com.vibhavtiwari.smsorganizer

import android.util.Xml
import com.vibhavtiwari.smsorganizer.core.MessageInput
import com.vibhavtiwari.smsorganizer.core.MessageSource
import com.vibhavtiwari.smsorganizer.core.classify
import java.io.FilterInputStream
import java.io.InputStream
import java.io.IOException
import org.xmlpull.v1.XmlPullParser

private const val MAX_MESSAGES = 50_000
private const val MAX_ARCHIVE_BYTES = 32L * 1024 * 1024
private const val MAX_MESSAGE_CHARACTERS = 20_000
private val serviceAddress = Regex("""[A-Z]{2}-[A-Z0-9]{6}(?:-[A-Z])?""", RegexOption.IGNORE_CASE)

/** Reads the common <smses><sms …/> XML format. This is an inbox preview, not device restore. */
internal fun readSmsArchive(input: InputStream): List<InboxMessage> {
    val parser = Xml.newPullParser()
    parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false)
    parser.setInput(ArchiveLimit(input), null)
    check(parser.nextTag() == XmlPullParser.START_TAG && parser.name == "smses") {
        "Choose an SMS XML export with an smses root."
    }
    val messages = ArrayList<InboxMessage>()
    while (true) {
        when (parser.next()) {
            XmlPullParser.START_TAG -> if (parser.depth == 2 && parser.name == "sms") {
                if (messages.size >= MAX_MESSAGES) throw IOException("Too many SMS entries.")
                val address = parser.getAttributeValue(null, "address") ?: continue
                val body = parser.getAttributeValue(null, "body") ?: continue
                if (body.length > MAX_MESSAGE_CHARACTERS) throw IOException("An SMS entry is too large.")
                val sentAt = parser.getAttributeValue(null, "date")?.toLongOrNull()?.takeIf { it >= 0 } ?: continue
                val direction = parser.getAttributeValue(null, "type")
                if (direction != "1" && direction != "2") continue
                val source = when {
                    direction == "2" -> MessageSource.KNOWN_PERSON
                    serviceAddress.matches(address) -> MessageSource.SERVICE
                    else -> MessageSource.UNKNOWN
                }
                messages += InboxMessage(messages.size.toLong(), address, body, sentAt, classify(MessageInput(body, source)).category)
            }
            XmlPullParser.END_DOCUMENT -> return messages
        }
    }
}

private class ArchiveLimit(input: InputStream) : FilterInputStream(input) {
    private var total = 0L

    override fun read(): Int = super.read().also { if (it >= 0) count(1) }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        super.read(buffer, offset, length).also { if (it >= 0) count(it) }

    private fun count(bytes: Int) {
        total += bytes
        if (total > MAX_ARCHIVE_BYTES) throw IOException("SMS export exceeds 32 MB.")
    }
}
