package com.vibhavtiwari.smsorganizer.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InboxHygienePolicyTest {
    private val phishing = "Your account will be blocked. Update your KYC at https://bit.ly/claim"
    private val reward = "You have won. Pay a processing fee at https://tinyurl.com/win"
    private val sender = "AB-MYBANK"
    private val otp = "Your OTP is 123456. Do not share it."
    private val day = 86_400_000L
    private val now = 90 * day

    @Test
    fun requiresIndependentSignalsAndALink() {
        assertTrue(isLikelySpam(phishing, Category.OTHER, true))
        assertTrue(isLikelySpam(reward, Category.PROMOTIONS, true))
        assertFalse(isLikelySpam("Please update your KYC in our official app", Category.OTHER, true))
        assertFalse(isLikelySpam("Your account will be blocked. Update your KYC at a branch.", Category.OTHER, true))
        assertFalse(isLikelySpam("New 10% offer. Shop at https://bit.ly/shop", Category.PROMOTIONS, true))
        assertFalse(isLikelySpam(phishing.replace("bit.ly", "example.invalid"), Category.OTHER, true))
    }

    @Test
    fun doesNotAutomaticallyHideOutboundOrProtectedCategories() {
        assertFalse(isLikelySpam(phishing, Category.OTHER, false))
        for (category in listOf(Category.PERSONAL, Category.TRANSACTIONS, Category.BILLS, Category.TRAVEL, Category.OTP)) {
            assertFalse(isLikelySpam(phishing, category, true))
        }
    }

    @Test
    fun usesInclusiveAgeThresholdsAndSkipsProtectedMessages() {
        assertEquals(CleanupReason.OLD_OTP, cleanupReason(Category.OTP, true, now - 7 * day, now, sender, otp))
        assertNull(cleanupReason(Category.OTP, true, now - 7 * day + 1, now, sender, otp))
        assertEquals(CleanupReason.OLD_PROMOTION, cleanupReason(Category.PROMOTIONS, true, now - 30 * day, now, sender, otp))
        assertNull(cleanupReason(Category.PROMOTIONS, true, now - 30 * day + 1, now, sender, otp))
        assertNull(cleanupReason(Category.OTP, false, now - 7 * day, now, sender, otp))
        assertNull(cleanupReason(Category.OTP, true, now + 1, now, sender, otp))
        assertNull(cleanupReason(Category.OTP, true, 0L, now, sender, otp))
        assertNull(cleanupReason(Category.OTP, true, now - 7 * day, now, "+910000000000", otp))
        assertNull(cleanupReason(Category.OTP, true, now - 7 * day, now, sender, "Not an OTP"))
        assertNull(cleanupReason(Category.BILLS, true, now - 50 * day, now, sender, otp))
    }
}
