package com.vibhavtiwari.smsorganizer.core

enum class Category {
    PERSONAL, TRANSACTIONS, OTP, PROMOTIONS, TRAVEL, BILLS, OTHER
}

enum class MessageSource {
    KNOWN_PERSON, SERVICE, UNKNOWN
}

data class MessageInput(val text: String, val source: MessageSource)

data class Classification(val category: Category, val matchedSignals: Set<String> = emptySet())

private val passcodeWord = Regex("""\b(?:otp|one[- ]time (?:password|passcode|code)|verification code|security code)\b""")
private val standaloneDigits = Regex("""(?<!\d)\d{4,8}(?!\d|[.,]\d)""")
private val bankingWord = Regex("""\b(?:upi|neft|imps|rtgs|a/c|account|card|atm|bank|wallet)\b""")
private val moneyMovement = Regex("""\b(?:debited|credited|withdrawn|deposited|sent|received|spent|refunded|paid|payment successful)\b""")
private val amount = Regex("""(?:₹\s*|\b(?:inr|rs)\.?\s*)\d[\d,]*(?:\.\d{1,2})?""")
private val bill = Regex("""\b(?:bill|statement|invoice|outstanding|minimum payment|amount due)\b""")
private val deadline = Regex("""\b(?:due (?:on|by|date)|pay (?:on|by|before)|payment deadline)\b""")
private val travel = Regex("""\b(?:pnr|boarding pass|flight|train|railway|check[- ]in)\b""")
private val itinerary = Regex("""\b(?:confirmed|departs?|departure|boarding|rescheduled|cancelled|reservation)\b""")
private val promotion = Regex("""\b(?:%\s*off|sale|coupon|promo code|exclusive offer|limited[- ]time offer|shop now)\b""")
private val percentDiscount = Regex("""\b\d{1,3}\s*%\s*off\b""")

/**
 * Local, advisory classification. No extracted code or original body leaves this function.
 * The caller must retain the original message in the All inbox independently of the category.
 */
fun classify(message: MessageInput): Classification {
    if (message.source == MessageSource.KNOWN_PERSON) return Classification(Category.PERSONAL)
    val normalized = message.text.lowercase().replace('\u00a0', ' ').replace('\u202f', ' ')
    val fromService = message.source == MessageSource.SERVICE
    return when {
        passcodeWord.containsMatchIn(normalized) && standaloneDigits.containsMatchIn(normalized) ->
            Classification(Category.OTP, setOf("passcode-context", "numeric-token"))
        fromService && bankingWord.containsMatchIn(normalized) &&
            moneyMovement.containsMatchIn(normalized) && amount.containsMatchIn(normalized) ->
            Classification(Category.TRANSACTIONS, setOf("banking-context", "movement", "currency"))
        fromService && bill.containsMatchIn(normalized) && deadline.containsMatchIn(normalized) ->
            Classification(Category.BILLS, setOf("bill-context", "deadline"))
        fromService && travel.containsMatchIn(normalized) && itinerary.containsMatchIn(normalized) ->
            Classification(Category.TRAVEL, setOf("travel-context", "itinerary-status"))
        fromService && (promotion.containsMatchIn(normalized) || percentDiscount.containsMatchIn(normalized)) ->
            Classification(Category.PROMOTIONS, setOf("offer-context"))
        else -> Classification(Category.OTHER)
    }
}
