package com.vibhavtiwari.smsorganizer.core

enum class Category {
    PERSONAL, TRANSACTIONS, OTP, PROMOTIONS, TRAVEL, BILLS, OTHER
}

enum class MessageSource {
    KNOWN_PERSON, PERSONAL_NUMBER, SERVICE, UNKNOWN
}

data class MessageInput(val text: String, val source: MessageSource)

data class Classification(val category: Category, val matchedSignals: Set<String> = emptySet())

private val bank = Regex("""\b(?:upi|neft|imps|rtgs|a\s*/\s*c|acct|account|card|atm|bank|wallet|vpa)\b""")
private val settledMovement = Regex("""\b(?:debit(?:ed)?|credit(?:ed)?|withdrawn|deposited|spent|refunded|paid|payment (?:successful|received|completed)|(?:sent|received) (?:via|using) upi)\b""")
private val amount = Regex("""(?:₹\s*|\b(?:inr|rs)\.?\s*)[0-9][0-9,]*(?:\.[0-9]{1,2})?""")
private val bill = Regex("""\b(?:bill|statement|invoice|outstanding|minimum (?:amount )?due|minimum payment|amount due)\b""")
private val deadline = Regex("""\b(?:due\s+(?:on|by|date)|pay\s+(?:on|by|before)|payment deadline)\b""")
private val travel = Regex("""\b(?:pnr|boarding pass|flight (?:[a-z]{2}\s*[0-9]+|number|no\b)|your flight|your train|train (?:[0-9]{3,5}|number|no\b|reservation|ticket)|railway ticket|hotel (?:booking|reservation)|boarding gate)\b""")
private val itinerary = Regex("""\b(?:confirmed|confirmation|departs?|departure|boarding|rescheduled|delayed|cancelled|canceled|reservation|check[- ]in|ticket booked)\b""")
private val reference = Regex("""\bpnr\s*(?:no\.?\s*)?[:#-]?\s*[a-z0-9]{6,10}\b""")
private val promotion = Regex("""\b(?:sale|coupon|promo code|cashback offer|exclusive offer|limited[- ]time offer|shop now|flat\s+[0-9]+\s*%|buy\s+[0-9]+\s+get\s+[0-9]+|apply now|pre[- ]approved)\b""")
private val percentDiscount = Regex("""\b[0-9]{1,3}\s*%\s*off\b""")
private val otpContext = Regex("""\b(?:do not share|don't share|never share|expires? in|valid for|login|sign[- ]in|to verify)\b""")

/** Indian registered sender style; the shape alone does not authenticate the sender. */
private val registeredSender = Regex("""[A-Z]{2}-[A-Z0-9]{6}(?:-[A-Z])?""", RegexOption.IGNORE_CASE)
private val phoneNumber = Regex("""\+?[0-9][0-9 ()-]{6,20}""")
private val shortcode = Regex("""[0-9]{3,6}""")
private val alphabeticSender = Regex("""(?=[A-Za-z0-9]{3,11}$)(?=.*[A-Za-z])[A-Za-z0-9]+""")

/** Offline source inference. A sent message or confirmed contact always remains personal. */
fun messageSource(address: String, isOutgoing: Boolean = false, isKnownContact: Boolean = false): MessageSource =
    when {
        isOutgoing || isKnownContact -> MessageSource.KNOWN_PERSON
        registeredSender.matches(address) || shortcode.matches(address) ||
            (address == address.uppercase() && alphabeticSender.matches(address)) -> MessageSource.SERVICE
        phoneNumber.matches(address) && address.count(Char::isDigit) in 7..15 -> MessageSource.PERSONAL_NUMBER
        else -> MessageSource.UNKNOWN
    }

/**
 * Local, advisory classification. No extracted code or original body leaves this function.
 * Uncertain SMS are kept in Other and All, not marked as spam.
 */
fun classify(message: MessageInput): Classification {
    if (message.source == MessageSource.KNOWN_PERSON) return Classification(Category.PERSONAL)
    val normalized = message.text.lowercase().replace('\u00a0', ' ').replace('\u202f', ' ')
    val service = message.source == MessageSource.SERVICE
    return when {
        extractLabelledOtp(normalized) != null && (service || otpContext.containsMatchIn(normalized)) ->
            Classification(Category.OTP, setOf("passcode-context", "numeric-token"))
        service && bill.containsMatchIn(normalized) && deadline.containsMatchIn(normalized) ->
            Classification(Category.BILLS, setOf("bill-context", "deadline"))
        service && bank.containsMatchIn(normalized) &&
            settledMovement.containsMatchIn(normalized) && amount.containsMatchIn(normalized) ->
            Classification(Category.TRANSACTIONS, setOf("banking-context", "movement", "currency"))
        service && travel.containsMatchIn(normalized) &&
            (itinerary.containsMatchIn(normalized) || reference.containsMatchIn(normalized)) ->
            Classification(Category.TRAVEL, setOf("travel-context", "itinerary-status"))
        service && (promotion.containsMatchIn(normalized) || percentDiscount.containsMatchIn(normalized)) ->
            Classification(Category.PROMOTIONS, setOf("offer-context"))
        message.source == MessageSource.PERSONAL_NUMBER && normalized.isNotBlank() &&
            !bank.containsMatchIn(normalized) && !bill.containsMatchIn(normalized) &&
            !travel.containsMatchIn(normalized) && !promotion.containsMatchIn(normalized) &&
            !percentDiscount.containsMatchIn(normalized) ->
            Classification(Category.PERSONAL, setOf("phone-number"))
        else -> Classification(Category.OTHER)
    }
}
