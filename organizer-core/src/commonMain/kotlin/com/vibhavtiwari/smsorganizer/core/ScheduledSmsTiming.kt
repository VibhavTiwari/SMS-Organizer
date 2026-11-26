package com.vibhavtiwari.smsorganizer.core

/** A powered-off or restricted phone must not send a stale message indefinitely later. */
internal const val SCHEDULE_GRACE_MS = 15 * 60 * 1000L
const val MAX_SCHEDULE_DELAY_MS = 366 * 24 * 60 * 60 * 1000L

enum class ScheduledSmsTiming { UPCOMING, DUE, MISSED }

fun scheduledSmsTiming(at: Long, now: Long): ScheduledSmsTiming = when {
    now < at -> ScheduledSmsTiming.UPCOMING
    now - at >= SCHEDULE_GRACE_MS -> ScheduledSmsTiming.MISSED
    else -> ScheduledSmsTiming.DUE
}

fun isValidSmsSchedule(at: Long, now: Long): Boolean =
    at > now && at - now in 1..MAX_SCHEDULE_DELAY_MS
