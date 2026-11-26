package com.vibhavtiwari.smsorganizer.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ClassificationTest {
    @Test
    fun syntheticIndianServiceMessages() {
        val messages = listOf(
            "INR 1,249.00 debited from your bank account via UPI." to Category.TRANSACTIONS,
            "Your card has been credited with Rs. 2,500." to Category.TRANSACTIONS,
            "OTP 408129 for your card purchase of INR 2100. Do not share." to Category.OTP,
            "Your electricity bill is due on 05 Dec. Amount payable INR 880." to Category.BILLS,
            "Your train reservation is confirmed. PNR 9000000001." to Category.TRAVEL,
            "Get 40% off until Friday." to Category.PROMOTIONS,
            "Please update your profile when convenient." to Category.OTHER,
        )
        for ((sample, expected) in messages) {
            assertEquals(expected, classify(MessageInput(sample, MessageSource.SERVICE)).category)
        }
    }

    @Test
    fun contactsStayPersonalAndUncertainMessagesRemainVisible() {
        val text = "I received INR 1500 in my bank account, thanks"
        assertEquals(Category.PERSONAL, classify(MessageInput(text, MessageSource.KNOWN_PERSON)).category)
        assertEquals(Category.OTHER, classify(MessageInput(text, MessageSource.UNKNOWN)).category)
        assertEquals(Category.OTHER, classify(MessageInput("", MessageSource.UNKNOWN)).category)
    }

    @Test
    fun otpClassificationDoesNotExposeCodeInResult() {
        val result = classify(MessageInput("Your verification code is 424242.", MessageSource.SERVICE))
        assertEquals(Classification(Category.OTP, setOf("passcode-context", "numeric-token")), result)
    }
}
