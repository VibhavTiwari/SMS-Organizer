package com.vibhavtiwari.smsorganizer.core

import java.time.DateTimeException
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.util.Locale

enum class MessageReminderKind { BILL_DUE, TRAIN_DEPARTURE }

data class MessageReminderCandidate(
    val kind: MessageReminderKind,
    val eventAt: LocalDateTime,
    val remindAt: LocalDateTime,
    val expiry: LocalDateTime,
    val zone: ZoneId,
)

private val billContext = Regex(
    """\b(?:bill|invoice|statement|outstanding|(?:minimum|total|amount|payment)\s+due)\b""",
    RegexOption.IGNORE_CASE,
)
private val trainContext = Regex(
    """\b(?:railway[s]?|irctc|train(?:\s+(?:no\.?|number))?\s*[:#-]?\s*[0-9]{5}|pnr\s*[:#-]?\s*[0-9]{10})\b""",
    RegexOption.IGNORE_CASE,
)
private val disabledEvent = Regex(
    """\b(?:cancel(?:led|ed|lation)|already\s+paid|bill\s+paid|payment\s+(?:received|successful|confirmed)|rescheduled|postponed|not\s+due|no\s+payment\s+due|do\s+not\s+pay|refund(?:ed)?|old\s+due\s+date)\b""",
    RegexOption.IGNORE_CASE,
)
private const val DATE = """[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{1,2}[/-][0-9]{1,2}[/-][0-9]{4}|[0-9]{1,2}[- /]+[a-z]{3,9}[- ,/]+[0-9]{4}"""
private const val TIME = """[0-9]{1,2}:[0-9]{2}(?:[ \t]*[ap]m)?"""
private const val SEP = """\s*(?:(?:is\b|[:=-])\s*)?"""
private val billDate = Regex(
    """\b(?:due\s+(?:date|on|by)|(?:pay|payable)\s+(?:on|by))$SEP($DATE)(?![a-z0-9/\-])""",
    RegexOption.IGNORE_CASE,
)
private val trainJourneyDate = Regex(
    """\b(?:journey\s+date|date\s+of\s+journey|doj|(?:departure|boarding)\s+date)$SEP($DATE)(?![a-z0-9/\-])""",
    RegexOption.IGNORE_CASE,
)
private val trainDeparture = Regex(
    """\b(?:depart(?:s|ure)?|departs?\s+at|departure\s+time|boarding\s+time|dep\.?)(?![a-z])$SEP(?:(?:on\s+)?($DATE)\s*(?:,|\bat\b)?\s*)?($TIME)(?![a-z0-9:/]|[ \t]*[ap]m\b)""",
    RegexOption.IGNORE_CASE,
)
private val explicitTimeZone = Regex(
    """(?<![a-z0-9])(?:UTC|GMT|[ECMP][SD]T|CET|CEST|BST|A[EW][SD]T|JST|KST|IST)(?![a-z])""",
    RegexOption.IGNORE_CASE,
)
private val indianTimeZones = setOf("Asia/Kolkata", "Asia/Calcutta")
private val monthlyNames = listOf(
    "january", "february", "march", "april", "may", "june", "july", "august",
    "september", "october", "november", "december",
)
private val splitNamed = Regex("""[- ,/]+""")
private val numericDate = Regex("""([0-9]{1,2})([/-])([0-9]{1,2})\2([0-9]{4})""")
private val parsedTime = Regex("""([0-9]{1,2}):([0-9]{2})\s*(am|pm)?""", RegexOption.IGNORE_CASE)

private fun parseDate(value: String): LocalDate? = try {
    if (value.getOrNull(4) == '-') {
        LocalDate.parse(value)
    } else {
        val numeric = numericDate.matchEntire(value)
        if (numeric != null) {
            val day = numeric.groupValues[1].toInt()
            val month = numeric.groupValues[3].toInt()
            if (day <= 12 && day != month) null
            else LocalDate.of(numeric.groupValues[4].toInt(), month, day)
        } else {
            val parts = value.lowercase(Locale.ROOT).split(splitNamed)
            val month = monthlyNames.indexOfFirst { it == parts[1] || it.take(3) == parts[1] } + 1
            LocalDate.of(parts[2].toInt(), month, parts[0].toInt())
        }
    }
} catch (_: DateTimeException) {
    null
}

private fun parseTime(value: String): LocalTime? {
    val parts = parsedTime.matchEntire(value)?.groupValues ?: return null
    val rawHour = parts[1].toInt()
    val hour = when (parts[3].lowercase(Locale.ROOT)) {
        "" -> if (parts[1].length == 2) rawHour else return null
        "am" -> if (rawHour in 1..12) rawHour % 12 else return null
        else -> if (rawHour in 1..12) rawHour % 12 + 12 else return null
    }
    return try {
        LocalTime.of(hour, parts[2].toInt())
    } catch (_: DateTimeException) {
        null
    }
}

/**
 * Suggests only an explicit future date, not an inferred year, time or ambiguous day/month order.
 * Text is parsed locally. The caller must confirm before arming anything.
 */
fun extractMessageReminder(
    body: String,
    now: LocalDateTime,
    zone: ZoneId,
): MessageReminderCandidate? {
    if (body.length !in 1..2_000 || disabledEvent.containsMatchIn(body)) return null
    val hasTrain = trainContext.containsMatchIn(body)
    val hasBill = billContext.containsMatchIn(body)
    if (hasTrain && hasBill) return null
    val candidate = when {
        hasTrain -> {
            val departures = trainDeparture.findAll(body).toList()
            if (departures.size != 1) return null
            val match = departures.single()
            val dates = trainJourneyDate.findAll(body).map { parseDate(it.groupValues[1]) }.toMutableList()
            if (match.groupValues[1].isNotBlank()) dates += parseDate(match.groupValues[1])
            val date = dates.distinct().singleOrNull() ?: return null
            val time = parseTime(match.groupValues[2]) ?: return null
            // Do not silently reinterpret a foreign or offset timezone as the handset's timezone.
            if (explicitTimeZone.findAll(body).any {
                    !it.value.equals("IST", ignoreCase = true) || zone.id !in indianTimeZones
                }
            ) return null
            val departure = date.atTime(time)
            MessageReminderCandidate(MessageReminderKind.TRAIN_DEPARTURE, departure, departure.minusHours(1), departure, zone)
        }
        hasBill -> {
            val date = billDate.findAll(body).map { parseDate(it.groupValues[1]) }.distinct().singleOrNull() ?: return null
            // The message supplies a date, not a payment cutoff time. 09:00 is our proposed reminder time.
            val due = date.atTime(9, 0)
            MessageReminderCandidate(MessageReminderKind.BILL_DUE, due, due, date.plusDays(1).atStartOfDay(), zone)
        }
        else -> return null
    }
    return candidate.takeIf {
        it.remindAt > now && it.eventAt <= now.plusYears(1) &&
            listOf(it.eventAt, it.remindAt, it.expiry).all { time -> zone.rules.getValidOffsets(time).size == 1 }
    }
}
