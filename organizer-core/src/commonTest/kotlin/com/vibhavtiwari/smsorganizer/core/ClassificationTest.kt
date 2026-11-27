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
    @Test
    fun productionShapedInboxCategories() {
        val messages = listOf(
            "A/c XX0191 debited by INR 1,450.00 using UPI. Ref 0000098877." to Category.TRANSACTIONS,
            "Rs. 799 credited to acct XX1245. Cashback received yesterday." to Category.TRANSACTIONS,
            "Your credit card statement is ready. Minimum amount due Rs 900. Pay before 7 Dec." to Category.BILLS,
            "PNR: QXTR91. Flight AI 143 departs Delhi on Dec 2." to Category.TRAVEL,
            "Your train ticket. PNR No. 9078000111." to Category.TRAVEL,
            "Hotel booking confirmation for 12 Dec." to Category.TRAVEL,
            "Buy 2 get 1 on selected items." to Category.PROMOTIONS,
            "Get up to 20% off on your next card payment." to Category.PROMOTIONS,
            "Never share your OTP. Your account ending 4421 received INR 449." to Category.OTHER,
            "Your OTP for Rs 2450.50 expires in 5 minutes." to Category.OTHER,
            "Your OTP is 130001 or 130002." to Category.OTHER,
            "Your verification code is 012345." to Category.OTP,
        )
        for ((sample, expected) in messages) {
            assertEquals(expected, classify(MessageInput(sample, MessageSource.SERVICE)).category, sample)
        }
    }

    @Test
    fun sourceInferenceAndUntrustedPhoneNumbers() {
        assertEquals(MessageSource.SERVICE, messageSource("VM-HDFCBK-S"))
        assertEquals(MessageSource.SERVICE, messageSource("56789"))
        assertEquals(MessageSource.SERVICE, messageSource("INDBANK"))
        assertEquals(MessageSource.PERSONAL_NUMBER, messageSource("+91 98765 43210"))
        assertEquals(MessageSource.UNKNOWN, messageSource("unknown sender"))
        assertEquals(MessageSource.KNOWN_PERSON, messageSource("VM-HDFCBK", isOutgoing = true))
        assertEquals(MessageSource.KNOWN_PERSON, messageSource("919999999999", isKnownContact = true))
        assertEquals(Category.PERSONAL, classify(MessageInput("See you soon!", messageSource("+91 99999 99999"))).category)
        assertEquals(Category.OTHER, classify(MessageInput("Account XX1234 debited by INR 909.", messageSource("+91 99999 99999"))).category)
        val challenge = "Verification code: 303030. Don't share it."
        assertEquals(Category.OTP, classify(MessageInput(challenge, messageSource("+91 99999 99999"))).category)
        assertEquals(Category.PERSONAL, classify(MessageInput(challenge, messageSource("VM-HDFCBK", isOutgoing = true))).category)
    }

}
