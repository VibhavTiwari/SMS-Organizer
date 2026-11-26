package com.vibhavtiwari.smsorganizer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScheduledSmsTimingTest {
    @Test fun doesNotSendEarlyEvenAfterClockChange() {
        assertEquals(ScheduledSmsTiming.UPCOMING, scheduledSmsTiming(50_000, 49_999))
        assertEquals(ScheduledSmsTiming.DUE, scheduledSmsTiming(50_000, 50_000))
    }

    @Test fun boundedLateWindow() {
        assertEquals(ScheduledSmsTiming.DUE, scheduledSmsTiming(50_000, 50_000 + SCHEDULE_GRACE_MS - 1))
        assertEquals(ScheduledSmsTiming.MISSED, scheduledSmsTiming(50_000, 50_000 + SCHEDULE_GRACE_MS))
    }

    @Test fun requiresFutureDateWithinHorizon() {
        assertFalse(isValidSmsSchedule(1_000, 1_000))
        assertFalse(isValidSmsSchedule(999, 1_000))
        assertTrue(isValidSmsSchedule(1_001, 1_000))
        assertTrue(isValidSmsSchedule(1_000 + MAX_SCHEDULE_DELAY_MS, 1_000))
        assertFalse(isValidSmsSchedule(1_001 + MAX_SCHEDULE_DELAY_MS, 1_000))
    }
}
