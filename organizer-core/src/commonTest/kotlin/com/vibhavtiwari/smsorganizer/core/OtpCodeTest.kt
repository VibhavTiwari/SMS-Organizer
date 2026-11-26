package com.vibhavtiwari.smsorganizer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class OtpCodeTest {
    @Test fun extractsOnlyAnExplicitlyBoundCodeIncludingLeadingZeroes() {
        assertEquals("001234", extractNotificationOtp("AX-ABC123-S", "Your OTP is 001234. Valid for 5 minutes."))
        assertEquals("4321", extractNotificationOtp("VM-BANKXX", "Account ending 9999. 4321 is your verification code."))
        assertEquals("12345678", extractNotificationOtp("AZ-EXAMPL", "OTP for login: 12345678"))
        assertEquals("123456", extractNotificationOtp("AZ-EXAMPL", "OTP 123456. 123456 is your OTP."))
    }

    @Test fun rejectsAmbiguousCodesAndUnrelatedNumbers() {
        for (text in listOf(
            "OTP: 123456 or 654321",
            "654321 / 123456 is your OTP",
            "OTP 123456. Security code 654321.",
            "Do not share your OTP. Account ending 1234.",
            "Verification code: A123456B",
            "Verification code: 123456789",
            "Verification code: 1234.56",
            "Verification code: 12,3456",
            "OTP: 123４567",
            "Promo code: 123456",
        )) assertNull(extractNotificationOtp("AZ-EXAMPL", text), text)
    }

    @Test fun rejectsPhoneNumberSenderAndUnrecognisedSenderFormats() {
        assertNull(extractNotificationOtp("+919876543210", "OTP: 123456"))
        assertNull(extractNotificationOtp("FRIEND", "OTP: 123456"))
    }
}
