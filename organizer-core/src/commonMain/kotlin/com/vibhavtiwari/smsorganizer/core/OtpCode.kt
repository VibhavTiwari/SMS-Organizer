package com.vibhavtiwari.smsorganizer.core

private val serviceSender = Regex("""[A-Z]{2}-[A-Z0-9]{6}(?:-[A-Z])?""", RegexOption.IGNORE_CASE)
private const val label =
    """(?:otp|one[- ]time (?:password|passcode|code)|verification code|security code)"""
private const val digits = """(?<![\p{L}\p{N}_])(?<![\p{N}][.,])([0-9]{4,8})(?![\p{L}\p{N}_]|[.,][\p{N}])"""
private const val join = """[ \t]*[:=–—-]?[ \t]*"""
private val afterLabel = Regex(
    """\b(?:your[ \t]+)?$label\b(?:[ \t]+(?:for (?:login|sign[- ]in)|to (?:verify|log[ -]in|sign[ -]in))\b)?(?:[ \t]+is\b)?$join$digits""",
    RegexOption.IGNORE_CASE,
)
private val beforeLabel = Regex(
    """$digits[ \t]+(?:is[ \t]+)?(?:your[ \t]+)?$label\b""",
    RegexOption.IGNORE_CASE,
)
private val trailingAlternative = Regex("""^[ \t]*(?:/|\bor\b|\band\b)[ \t]*$digits""", RegexOption.IGNORE_CASE)
private val leadingAlternative = Regex("""$digits[ \t]*(?:/|\bor\b|\band\b)[ \t]*$""", RegexOption.IGNORE_CASE)

/**
 * Deliberately narrower than inbox categorisation: extract a single clearly labelled numeric code
 * only from a service-style SMS sender. A sender's format is not proof of authenticity.
 */
fun extractNotificationOtp(sender: String, text: String): String? =
    if (serviceSender.matches(sender)) extractLabelledOtp(text) else null

/** Used by inbox rules without changing sender trust at the notification boundary. */
internal fun extractLabelledOtp(text: String): String? {
    if (text.length !in 1..2_048) return null
    val normalized = text.replace('\u00a0', ' ').replace('\u202f', ' ')
    val codes = mutableSetOf<String>()
    for (match in afterLabel.findAll(normalized) + beforeLabel.findAll(normalized)) {
        val code = match.groups[1] ?: continue
        if (trailingAlternative.containsMatchIn(normalized.substring(code.range.last + 1)) ||
            leadingAlternative.containsMatchIn(normalized.substring(0, code.range.first))
        ) return null
        codes += code.value
        if (codes.size > 1) return null
    }
    return codes.singleOrNull()
}
