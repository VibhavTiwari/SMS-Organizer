package com.vibhavtiwari.smsorganizer.core

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class MmsPduTest {
    @Test
    fun encodesGroupWithAsciiAddressesAndMixedTextPhoto() {
        val actual = encodeMmsSend(
            "test-1", listOf("+15550100", "+15550101"),
            listOf(MmsPart(MmsMedia.TEXT, "नमस्ते".encodeToByteArray()),
                MmsPart(MmsMedia.JPEG, octets(0xff, 0xd8, 0xff, 0xd9))),
        )
        val expected = octets(0x8c, 0x80, 0x98) + value("test-1") +
            octets(0x8d, 0x92, 0x89, 1, 0x81, 0x97) + value("+15550100/TYPE=PLMN") +
            octets(0x97) + value("+15550101/TYPE=PLMN") + octets(0x84, 0xa3, 2) +
            octets(16, 18, 3, 0x83, 0x81, 0xea, 0x8e) + value("part-0.txt") +
            "नमस्ते".encodeToByteArray() +
            octets(13, 4, 0x9e, 0x8e) + value("part-1.jpg") + octets(0xff, 0xd8, 0xff, 0xd9)
        assertContentEquals(expected, actual)
    }

    @Test
    fun parsesSupportedCarrierNotificationAndRejectsMalformedFields() {
        val upper = octets(0x8c, 0x82, 0x98) + value("test-2") + octets(0x8d, 0x92, 0x8a, 0x80)
        val url = "http://mmsc.example.invalid/fixture"
        val pdu = upper + octets(0x8e, 2, 1, 0, 0x88, 2, 0x81, 0x80, 0x83) + value(url)
        assertEquals(MmsDecode.Supported(MmsNotification("test-2", url, 256)), decodeMmsNotification(pdu))
        assertEquals(MmsDecode.Malformed, decodeMmsNotification(pdu.copyOf(pdu.size - 1)))
        assertEquals(MmsDecode.Malformed, decodeMmsNotification(pdu + octets(0x83) + value(url)))
        val noSize = upper + octets(0x83) + value(url)
        assertEquals(MmsDecode.Malformed, decodeMmsNotification(noSize))
    }

    @Test
    fun decodesSyntheticRetrieveAndRejectsOversizedOrUnexpectedParts() {
        val headers = octets(0x8c, 0x84, 0x8d, 0x92, 0x89, 21, 0x80) + value("+15550100/TYPE=PLMN") +
            octets(0x97) + value("+15550101/TYPE=PLMN") + octets(0x84, 0xa3)
        val pdu = headers + octets(2, 1, 2, 0x83, 0x48, 0x69, 1, 4, 0x9e, 0xff, 0xd8, 0xff, 0xd9)
        val read = assertIs<MmsDecode.Supported<RetrievedMms>>(decodeMmsRetrieve(pdu)).value
        assertEquals("+15550100", read.from)
        assertEquals(listOf("+15550101"), read.recipients)
        assertEquals(listOf(MmsMedia.TEXT, MmsMedia.JPEG), read.parts.map(MmsPart::media))
        assertContentEquals(octets(0xff, 0xd8, 0xff, 0xd9), read.parts.last().bytes)
        assertEquals(MmsDecode.Malformed, decodeMmsRetrieve(pdu.copyOf(pdu.size - 2)))
        assertEquals(MmsDecode.Unsupported, decodeMmsRetrieve(headers + octets(1, 1, 1, 0x9f, 42)))
        assertEquals(MmsDecode.Malformed, decodeMmsRetrieve(headers + octets(1, 1, 0xff, 0xff, 0xff, 0xff, 0xff)))
        assertEquals(MmsDecode.Unsupported, decodeMmsRetrieve(ByteArray(MAX_MMS_PDU_BYTES + 1)))
    }

    @Test
    fun refusesUnsafeRecipientsDuplicateRecipientsAndOversizedImages() {
        val image = MmsPart(MmsMedia.JPEG, byteArrayOf(42))
        assertFailsWith<IllegalArgumentException> {
            encodeMmsSend("test", listOf("+15550100\u0000+15550102"), listOf(image))
        }
        assertFailsWith<IllegalArgumentException> {
            encodeMmsSend("test", listOf("+15550100", "+15550100"), listOf(image))
        }
        assertFailsWith<IllegalArgumentException> {
            encodeMmsSend("test", listOf("+15550100"),
                listOf(MmsPart(MmsMedia.JPEG, ByteArray(MAX_MMS_PDU_BYTES))))
        }
    }

    private fun octets(vararg value: Int): ByteArray = value.map(Int::toByte).toByteArray()
    private fun value(text: String): ByteArray = text.encodeToByteArray() + byteArrayOf(0)
}
