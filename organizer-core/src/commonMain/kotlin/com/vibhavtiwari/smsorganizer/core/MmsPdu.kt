package com.vibhavtiwari.smsorganizer.core

const val MAX_MMS_PDU_BYTES = 1_048_576
private const val MAX_PARTS = 16
private const val MAX_RECIPIENTS = 20
private val phone = Regex("[+]?[0-9]{3,15}")
private val token = Regex("[A-Za-z0-9_.-]{1,80}")

enum class MmsMedia(val mime: String, internal val wsp: Int) {
    TEXT("text/plain", 0x83),
    JPEG("image/jpeg", 0x9e),
    PNG("image/png", 0xa0),
    GIF("image/gif", 0x9d),
}

data class MmsPart(val media: MmsMedia, val bytes: ByteArray)
data class MmsNotification(val transactionId: String, val contentLocation: String, val declaredSize: Long)
data class RetrievedMms(val from: String?, val recipients: List<String>, val parts: List<MmsPart>)

sealed interface MmsDecode<out T> {
    data class Supported<T>(val value: T) : MmsDecode<T>
    data object Malformed : MmsDecode<Nothing>
    data object Unsupported : MmsDecode<Nothing>
}

/** OMA MMS Encapsulation 1.3 + WSP; intentionally supports text and common image parts only. */
fun encodeMmsSend(transactionId: String, recipients: List<String>, parts: List<MmsPart>): ByteArray {
    require(token.matches(transactionId))
    require(recipients.size in 1..MAX_RECIPIENTS && recipients.all(phone::matches))
    require(recipients.distinct().size == recipients.size)
    require(parts.size in 1..MAX_PARTS && parts.all { it.bytes.isNotEmpty() })
    require(parts.sumOf { it.bytes.size.toLong() } <= MAX_MMS_PDU_BYTES - 2_048)
    val out = Output()
    out.put(0x8c, 0x80, 0x98)
    out.string(transactionId)
    out.put(0x8d, 0x92, 0x89, 1, 0x81) // Version 1.2; the carrier inserts the From address.
    recipients.forEach {
        out.put(0x97)
        out.string("$it/TYPE=PLMN")
    }
    out.put(0x84, 0xa3) // multipart/mixed, no SMIL presentation.
    out.uint(parts.size)
    parts.forEachIndexed { index, part ->
        val header = Output()
        if (part.media == MmsMedia.TEXT) header.put(3, 0x83, 0x81, 0xea) // UTF-8
        else header.put(part.media.wsp)
        val extension = when (part.media) {
            MmsMedia.TEXT -> "txt"
            MmsMedia.JPEG -> "jpg"
            MmsMedia.PNG -> "png"
            MmsMedia.GIF -> "gif"
        }
        header.put(0x8e) // Content-Location, an invented basename (never a device filename).
        header.string("part-$index.$extension")
        val fields = header.result()
        out.uint(fields.size)
        out.uint(part.bytes.size)
        out.bytes(fields)
        out.bytes(part.bytes)
    }
    return out.result()
}

fun decodeMmsNotification(pdu: ByteArray): MmsDecode<MmsNotification> = decode(pdu, max = 16 * 1024) { reader ->
    val headers = reader.headers()
    if (headers.type != 0x82) throw UnsupportedPdu()
    if (headers.media != null || reader.hasRemaining()) throw InvalidPdu()
    val id = headers.transaction ?: throw InvalidPdu()
    val url = headers.location ?: throw InvalidPdu()
    val size = headers.size ?: throw InvalidPdu()
    if (size !in 1..MAX_MMS_PDU_BYTES.toLong()) throw UnsupportedPdu()
    if (id.isEmpty() || url.isEmpty()) throw InvalidPdu()
    MmsNotification(id, url, size)
}

fun decodeMmsRetrieve(pdu: ByteArray): MmsDecode<RetrievedMms> = decode(pdu) { reader ->
    val headers = reader.headers()
    if (headers.type != 0x84 || headers.retrieveStatus?.let { it != 0x80 } == true) throw UnsupportedPdu()
    if (headers.media !in setOf(0xa3, 0xb3)) throw UnsupportedPdu() // mixed / related
    val count = reader.uint()
    if (count !in 1..MAX_PARTS) throw UnsupportedPdu()
    val parts = ArrayList<MmsPart>(count)
    repeat(count) {
        val headersLength = reader.uint()
        val bytesLength = reader.uint()
        val partHeaders = reader.slice(headersLength)
        val media = partHeaders.media()
        val content = reader.slice(bytesLength)
        val supported = MmsMedia.entries.firstOrNull { it.wsp == media }
        if (supported != null) {
            if (bytesLength == 0) throw InvalidPdu()
            parts += MmsPart(supported, content.rest())
        } else if (media != SMIL_TYPE) {
            throw UnsupportedPdu() // never silently show a partial message as the whole MMS
        }
    }
    if (reader.hasRemaining()) throw InvalidPdu()
    if (parts.isEmpty()) throw UnsupportedPdu()
    RetrievedMms(headers.from, headers.recipients, parts)
}

private const val SMIL_TYPE = -2
private class InvalidPdu : Exception()
private class UnsupportedPdu : Exception()

private inline fun <T> decode(
    pdu: ByteArray,
    max: Int = MAX_MMS_PDU_BYTES,
    block: (Reader) -> T,
): MmsDecode<T> = when {
    pdu.isEmpty() -> MmsDecode.Malformed
    pdu.size > max -> MmsDecode.Unsupported
    else -> try {
        MmsDecode.Supported(block(Reader(pdu)))
    } catch (_: InvalidPdu) {
        MmsDecode.Malformed
    } catch (_: UnsupportedPdu) {
        MmsDecode.Unsupported
    }
}

private data class Headers(
    var type: Int? = null,
    var transaction: String? = null,
    var location: String? = null,
    var size: Long? = null,
    var media: Int? = null,
    var from: String? = null,
    var retrieveStatus: Int? = null,
    val recipients: MutableList<String> = mutableListOf(),
)

private class Reader(private val data: ByteArray, private var index: Int = 0, private val end: Int = data.size) {
    fun hasRemaining(): Boolean = index < end
    private fun peek(): Int {
        if (!hasRemaining()) throw InvalidPdu()
        return data[index].toInt() and 0xff
    }
    private fun byte(): Int = peek().also { index++ }
    fun uint(): Int {
        var value = 0L
        repeat(5) {
            val item = byte()
            value = (value shl 7) or (item and 0x7f).toLong()
            if (value > MAX_MMS_PDU_BYTES) throw InvalidPdu()
            if (item and 0x80 == 0) return value.toInt()
        }
        throw InvalidPdu()
    }
    fun slice(count: Int): Reader {
        if (count < 0 || count > end - index) throw InvalidPdu()
        return Reader(data, index, index + count).also { index += count }
    }
    fun rest(): ByteArray = data.copyOfRange(index, end)
    private fun valueLength(): Int = when (val length = byte()) {
        in 0..30 -> length
        31 -> uint()
        else -> throw InvalidPdu()
    }
    private fun string(): String {
        if (peek() == 0x7f) byte()
        val start = index
        while (byte() != 0) if (index - start > 4_096) throw InvalidPdu()
        return data.decodeToString(start, index - 1)
    }
    private fun integer(): Long {
        if (peek() >= 0x80) return (byte() and 0x7f).toLong()
        val size = byte()
        if (size !in 1..8) throw InvalidPdu()
        var value = 0L
        repeat(size) {
            if (value > (Long.MAX_VALUE ushr 8)) throw InvalidPdu()
            value = (value shl 8) or byte().toLong()
        }
        return value
    }
    private fun encodedString(): String {
        if (peek() >= 0x20 || peek() == 0) return string()
        val value = slice(valueLength())
        val charset = value.integer()
        if (charset != 106L && charset != 3L) throw UnsupportedPdu() // UTF-8 / US-ASCII
        return value.string().also { if (value.hasRemaining()) throw InvalidPdu() }
    }
    fun media(): Int {
        val bounded = if (peek() in 0..31) slice(valueLength()) else return mediaToken()
        return bounded.mediaToken()
    }
    private fun mediaToken(): Int {
        if (peek() >= 0x80) return byte()
        return when (val raw = string().lowercase()) {
            "application/smil", "application/smil+xml" -> SMIL_TYPE
            "application/vnd.wap.multipart.mixed" -> 0xa3
            "application/vnd.wap.multipart.related" -> 0xb3
            else -> MmsMedia.entries.firstOrNull { it.mime == raw }?.wsp ?: -1
        }
    }
    fun headers(): Headers {
        val out = Headers()
        var count = 0
        while (hasRemaining()) {
            if (++count > 128) throw InvalidPdu()
            when (byte()) {
                0x8c -> {
                    if (out.type != null) throw InvalidPdu()
                    out.type = byte()
                }
                0x98 -> {
                    if (out.transaction != null) throw InvalidPdu()
                    out.transaction = string()
                }
                0x83 -> {
                    if (out.location != null) throw InvalidPdu()
                    out.location = string()
                }
                0x8e -> {
                    if (out.size != null) throw InvalidPdu()
                    out.size = integer()
                }
                0x89 -> {
                    if (out.from != null) throw InvalidPdu()
                    val from = slice(valueLength())
                    when (from.byte()) {
                        0x80 -> out.from = from.encodedString().substringBefore("/TYPE=")
                        0x81 -> out.from = ""
                        else -> throw InvalidPdu()
                    }
                    if (from.hasRemaining()) throw InvalidPdu()
                }
                0x97, 0x82 -> {
                    if (out.recipients.size == MAX_RECIPIENTS) throw UnsupportedPdu()
                    out.recipients += encodedString().substringBefore("/TYPE=")
                }
                0x99 -> {
                    if (out.retrieveStatus != null) throw InvalidPdu()
                    out.retrieveStatus = byte()
                }
                0x84 -> {
                    out.media = media()
                    return out // Content-Type is the final header before multipart body.
                }
                0x8d, 0x86, 0x8f, 0x90, 0x91, 0x92, 0x94, 0x95, 0x9b -> byte()
                0x85 -> integer()
                0x88, 0x87 -> slice(valueLength()) // expiry or delivery time
                0x8b -> string() // message-id
                0x96, 0x9a, 0x93 -> encodedString() // subject/status text
                0x8a -> if (peek() >= 0x80) byte() else string()
                // Unknown headers cannot be skipped without knowing their value grammar.
                else -> throw UnsupportedPdu()
            }
        }
        return out
    }
}

private class Output {
    private var buffer = ByteArray(256)
    private var size = 0
    private fun allocate(count: Int) {
        require(count >= 0 && count <= MAX_MMS_PDU_BYTES - size)
        val needed = size + count
        if (needed > buffer.size) buffer = buffer.copyOf(maxOf(needed, buffer.size * 2).coerceAtMost(MAX_MMS_PDU_BYTES))
    }
    fun put(vararg octets: Int) {
        allocate(octets.size)
        octets.forEach { buffer[size++] = it.toByte() }
    }
    fun bytes(value: ByteArray) {
        allocate(value.size)
        value.copyInto(buffer, size)
        size += value.size
    }
    fun string(value: String) {
        bytes(value.encodeToByteArray())
        put(0)
    }
    fun uint(value: Int) {
        var shift = 0
        while (value ushr shift > 127) shift += 7
        while (shift > 0) {
            put((value ushr shift and 127) or 128)
            shift -= 7
        }
        put(value and 127)
    }
    fun result(): ByteArray = buffer.copyOf(size)
}
