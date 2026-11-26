package com.vibhavtiwari.smsorganizer.core

enum class PartSendResult { ACCEPTED, REJECTED }
enum class SmsSendStatus { WAITING, SENT, FAILED, PARTIALLY_SENT, UNCERTAIN }

/**
 * Summarises modem send callbacks, not delivery receipts. Duplicate callbacks are harmless.
 * Conflicting results or missing callbacks after a timeout must never trigger an auto-retry:
 * the carrier may have sent part or all of the message already.
 */
fun summariseSmsSend(
    partCount: Int,
    callbacks: List<Pair<Int, PartSendResult>>,
    timedOut: Boolean = false,
): SmsSendStatus {
    require(partCount > 0)
    val results = mutableMapOf<Int, PartSendResult>()
    for ((part, result) in callbacks) {
        if (part !in 0 until partCount) continue
        val previous = results.put(part, result)
        if (previous != null && previous != result) return SmsSendStatus.UNCERTAIN
    }
    if (results.size < partCount) return if (timedOut) SmsSendStatus.UNCERTAIN else SmsSendStatus.WAITING
    val accepted = results.values.count { it == PartSendResult.ACCEPTED }
    return when (accepted) {
        0 -> SmsSendStatus.FAILED
        partCount -> SmsSendStatus.SENT
        else -> SmsSendStatus.PARTIALLY_SENT
    }
}
