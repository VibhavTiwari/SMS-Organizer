package com.vibhavtiwari.smsorganizer.core

import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class MessageRemindersTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val now = LocalDateTime.parse("2026-11-27T11:00")

    @Test
    fun billUsesTheExplicitDueDateInsteadOfTheStatementDate() {
        val candidate = assertNotNull(extractMessageReminder(
            "Electricity bill issued 20 Nov 2026. Payment due by 3 Dec 2026. Amount Rs 735.", now, zone,
        ))
        assertEquals(MessageReminderKind.BILL_DUE, candidate.kind)
        assertEquals(LocalDateTime.parse("2026-12-03T09:00"), candidate.remindAt)
        assertEquals(LocalDateTime.parse("2026-12-04T00:00"), candidate.expiry)
    }

    @Test
    fun trainUsesAnExplicitDepartureAndIgnoresArrival() {
        val candidate = assertNotNull(extractMessageReminder(
            "IRCTC PNR 1234567890. Journey Date: 2026-12-01; Departure: 3:45 PM; Arrival: 22:15.", now, zone,
        ))
        assertEquals(MessageReminderKind.TRAIN_DEPARTURE, candidate.kind)
        assertEquals(LocalDateTime.parse("2026-12-01T15:45"), candidate.eventAt)
        assertEquals(LocalDateTime.parse("2026-12-01T14:45"), candidate.remindAt)
        assertEquals(candidate.eventAt, candidate.expiry)
    }

    @Test
    fun explicitTrainDateAndTimeCanShareALabel() {
        val candidate = assertNotNull(extractMessageReminder(
            "Train No. 12345 departs on 29-Nov-2026 at 06:10.", now, zone,
        ))
        assertEquals(LocalDateTime.parse("2026-11-29T06:10"), candidate.eventAt)
    }

    @Test
    fun rejectsUnclearInvalidPastOrConflictingDatesAndTimes() {
        listOf(
            "Electricity bill due on 03/12/2026", // ambiguous day/month order
            "Electricity bill due on 3 Dec", // no year
            "Electricity bill due date 29 February 2027",
            "Electricity bill due date 2026-11-26",
            "Electricity bill due on 3 Dec 2026 or due by 4 Dec 2026",
            "IRCTC journey date 29 Nov 2026; Arrival 22:35.",
            "IRCTC departure at 22:35.", // no departure date
            "IRCTC journey date 29 Nov 2026; Departure 8:30.", // no AM/PM or two-digit 24h clock
            "IRCTC journey date 29 Nov 2026; Departure 13:80.",
            "IRCTC journey date 29 Nov 2026; Departure 13:30 GMT.",
            "IRCTC journey date 29 Nov 2026; Departure 13:30 PM.",
            "IRCTC journey date 29 Nov 2026; Departure 13:30:55.",
            "IRCTC journey date 29 Nov 2026; Departure on 30 Nov 2026 at 13:30.",
            "IRCTC journey date 29 Nov 2026; Departure 10:45; Departure 16:30.",
            "Merchant sale ends on 2026-12-05 at 15:00.",
        ).forEach { message -> assertNull(extractMessageReminder(message, now, zone), message) }
    }

    @Test
    fun ignoresPaidCancelledAndRescheduledEvents() {
        listOf(
            "Electricity bill due date 2026-11-29. Payment successful.",
            "IRCTC train cancelled. Journey Date: 2026-11-29. Departure 16:30.",
            "IRCTC train rescheduled. Journey Date: 2026-11-29. Departure 16:30.",
        ).forEach { message -> assertNull(extractMessageReminder(message, now, zone), message) }
    }

    @Test
    fun rejectsUnresolvableLocalTimeDuringDaylightSavingChanges() {
        val ny = ZoneId.of("America/New_York")
        assertNull(extractMessageReminder(
            "Train 12345. Depart: 2027-03-14 at 02:30.", now, ny,
        ))
    }
}
