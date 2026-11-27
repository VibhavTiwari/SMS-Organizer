package com.vibhavtiwari.smsorganizer.core

private const val DAY_MILLIS = 24 * 60 * 60 * 1000L
private val linkHost = Regex("""(?:https?://|www\.)([a-z0-9.-]+)(?=[:/?#\s]|$)""", RegexOption.IGNORE_CASE)
private val shorteners = setOf("bit.ly", "tinyurl.com", "t.co", "is.gd", "cutt.ly", "rb.gy", "shorturl.at")
private val ipAddress = Regex("""(?:[0-9]{1,3}\.){3}[0-9]{1,3}""")

private fun hasObscuredLink(text: String): Boolean = linkHost.findAll(text).any {
    val host = it.groupValues[1].trimEnd('.').lowercase().removePrefix("www.")
    host in shorteners || ipAddress.matches(host) || host.split('.').any { label -> label.startsWith("xn--") }
}
private val urgentThreat = Regex(
    """\b(?:will be (?:blocked|suspended|deactivated|disconnected|closed|seized)|will (?:expire|be frozen)|final warning|legal action)\b""",
    RegexOption.IGNORE_CASE,
)
private val credentialRequest = Regex(
    """\b(?:verify|update|submit|share|enter|complete|confirm)\s+(?:(?:your|the)\s+)?(?:kyc|pin|cvv|otp|password|bank details|card details)\b""",
    RegexOption.IGNORE_CASE,
)
private val fakeReward = Regex(
    """\b(?:you(?:'ve| have)? won|winner|claim (?:your|the) (?:prize|reward|winnings))\b""",
    RegexOption.IGNORE_CASE,
)
private val advancePayment = Regex(
    """\b(?:processing fee|registration fee|release fee|pay (?:the |a )?fee)\b""",
    RegexOption.IGNORE_CASE,
)

/** Advisory, deliberately conservative: use independent signs and never edit the source inbox. */
fun isLikelySpam(text: String, category: Category, incoming: Boolean): Boolean {
    if (!incoming || category !in setOf(Category.OTHER, Category.PROMOTIONS) ||
        !hasObscuredLink(text)
    ) return false
    return (urgentThreat.containsMatchIn(text) && credentialRequest.containsMatchIn(text)) ||
        (fakeReward.containsMatchIn(text) && advancePayment.containsMatchIn(text))
}

enum class CleanupReason { OLD_OTP, OLD_PROMOTION }

/** Suggest only incoming OTPs after seven days and promotions after thirty. */
fun cleanupReason(
    category: Category,
    incoming: Boolean,
    timestampMillis: Long,
    nowMillis: Long,
    sender: String,
    body: String,
): CleanupReason? {
    if (!incoming || timestampMillis <= 0L || timestampMillis > nowMillis) return null
    val age = nowMillis - timestampMillis
    return when {
        category == Category.OTP && age >= 7 * DAY_MILLIS &&
            extractNotificationOtp(sender, body) != null -> CleanupReason.OLD_OTP
        category == Category.PROMOTIONS && age >= 30 * DAY_MILLIS -> CleanupReason.OLD_PROMOTION
        else -> null
    }
}
