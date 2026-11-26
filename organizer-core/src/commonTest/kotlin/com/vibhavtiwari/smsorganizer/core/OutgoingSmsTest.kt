package com.vibhavtiwari.smsorganizer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class OutgoingSmsTest {
    private val accepted = PartSendResult.ACCEPTED
    private val rejected = PartSendResult.REJECTED

    @Test
    fun waitsForEveryPartAndDeDuplicatesCallbacksInAnyOrder() {
        assertEquals(SmsSendStatus.WAITING, summariseSmsSend(2, listOf(1 to accepted, 1 to accepted)))
        assertEquals(SmsSendStatus.SENT, summariseSmsSend(2, listOf(1 to accepted, 0 to accepted, 1 to accepted)))
    }

    @Test
    fun retainsPartialSendAndDistinguishesTotalFailure() {
        assertEquals(SmsSendStatus.PARTIALLY_SENT, summariseSmsSend(2, listOf(1 to rejected, 0 to accepted)))
        assertEquals(SmsSendStatus.FAILED, summariseSmsSend(2, listOf(0 to rejected, 1 to rejected)))
    }

    @Test
    fun neverTreatsTimeoutOrConflictAsSafeToRetry() {
        assertEquals(SmsSendStatus.UNCERTAIN, summariseSmsSend(2, listOf(0 to accepted), timedOut = true))
        assertEquals(SmsSendStatus.UNCERTAIN, summariseSmsSend(1, listOf(0 to rejected, 0 to accepted)))
        assertEquals(SmsSendStatus.WAITING, summariseSmsSend(1, listOf(99 to accepted)))
    }
}
