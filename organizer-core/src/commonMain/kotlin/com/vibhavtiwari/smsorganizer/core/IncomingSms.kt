package com.vibhavtiwari.smsorganizer.core

data class SmsSegment(val sender: String?, val text: String?, val sentAtMillis: Long)

data class IncomingSms(val sender: String, val text: String, val sentAtMillis: Long)

enum class SmsAssemblyFailure { NO_SEGMENTS, INVALID_SENDER, MIXED_SENDERS, INVALID_TEXT }

sealed interface SmsAssembly {
    data class Accepted(val message: IncomingSms) : SmsAssembly
    data class Rejected(val reason: SmsAssemblyFailure) : SmsAssembly
}

/**
 * Combines the segments delivered together by Android. Transport orders these segments;
 * this function does not reorder, discard duplicates, or merge separate deliveries.
 */
fun assembleIncomingSms(segments: List<SmsSegment>): SmsAssembly {
    val first = segments.firstOrNull() ?: return SmsAssembly.Rejected(SmsAssemblyFailure.NO_SEGMENTS)
    val sender = first.sender?.takeIf(String::isNotBlank)
        ?: return SmsAssembly.Rejected(SmsAssemblyFailure.INVALID_SENDER)
    if (segments.any { it.sender != sender }) return SmsAssembly.Rejected(SmsAssemblyFailure.MIXED_SENDERS)
    if (segments.any { it.text == null }) return SmsAssembly.Rejected(SmsAssemblyFailure.INVALID_TEXT)
    val text = segments.joinToString(separator = "") { it.text.orEmpty() }
    if (text.isEmpty()) return SmsAssembly.Rejected(SmsAssemblyFailure.INVALID_TEXT)
    return SmsAssembly.Accepted(IncomingSms(sender, text, first.sentAtMillis.coerceAtLeast(0)))
}
