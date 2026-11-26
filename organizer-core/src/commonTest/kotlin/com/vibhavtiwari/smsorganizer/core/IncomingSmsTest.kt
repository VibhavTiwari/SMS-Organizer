package com.vibhavtiwari.smsorganizer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class IncomingSmsTest {
    @Test
    fun preservesSegmentOrderWhitespaceAndRepeatedChunks() {
        val result = assembleIncomingSms(listOf(
            SmsSegment("XX-PARCEL", "Hello ", 1000),
            SmsSegment("XX-PARCEL", "हां ", 999),
            SmsSegment("XX-PARCEL", "हां ", 1001),
        ))
        assertEquals(SmsAssembly.Accepted(IncomingSms("XX-PARCEL", "Hello हां हां ", 1000)), result)
    }

    @Test
    fun rejectsMissingPiecesAndSenderMismatchRatherThanPersistingAPartialMessage() {
        val first = SmsSegment("XX-PARCEL", "Part one", 1000)
        assertEquals(SmsAssembly.Rejected(SmsAssemblyFailure.INVALID_TEXT),
            assembleIncomingSms(listOf(first, first.copy(text = null))))
        assertEquals(SmsAssembly.Rejected(SmsAssemblyFailure.MIXED_SENDERS),
            assembleIncomingSms(listOf(first, first.copy(sender = "XY-OTHER"))))
        assertEquals(SmsAssembly.Rejected(SmsAssemblyFailure.INVALID_SENDER),
            assembleIncomingSms(listOf(first.copy(sender = " "))))
        assertEquals(SmsAssembly.Rejected(SmsAssemblyFailure.NO_SEGMENTS), assembleIncomingSms(emptyList()))
    }

    @Test
    fun preservesAlphanumericSenderAndIgnoresInvalidSentTimestamp() {
        assertEquals(
            SmsAssembly.Accepted(IncomingSms("AB-EXBANK", "Test", 0)),
            assembleIncomingSms(listOf(SmsSegment("AB-EXBANK", "Test", -1))),
        )
    }
}
