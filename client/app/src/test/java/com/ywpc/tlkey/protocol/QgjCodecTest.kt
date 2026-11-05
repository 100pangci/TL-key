package com.ywpc.tlkey.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QgjCodecTest {

    @Test
    fun v1Frame_usesTotalLengthAndBigEndianCommand() {
        val frame = QgjCodec.frame(QgjVariant.V1, QgjCodec.CMD_V1_LOGIN, ByteArray(0))
        assertArrayEquals(
            byteArrayOf(0xA5.toByte(), 0x00, 0x00, 0x06, 0x10, 0x02),
            frame,
        )
    }

    @Test
    fun v2Frame_lengthExcludesHeader() {
        val frame = QgjCodec.frame(
            QgjVariant.V2,
            QgjCodec.CMD_V2_LOGIN,
            byteArrayOf(0x00, 0x00, 0x30, 0x39),
        )
        assertArrayEquals(
            byteArrayOf(
                0xA7.toByte(), 0x00, 0x00, 0x06, 0x10, 0x01,
                0x00, 0x00, 0x30, 0x39,
            ),
            frame,
        )
    }

    @Test
    fun gpsFrame_usesTotalLength() {
        val frame = QgjCodec.frame(QgjVariant.GPS, QgjCodec.CMD_GPS_QUERY_IMEI, ByteArray(0))
        assertArrayEquals(
            byteArrayOf(0xA6.toByte(), 0x00, 0x00, 0x06, 0x30, 0x59),
            frame,
        )
    }

    @Test
    fun parse_roundTripsAllVariants() {
        for (variant in QgjVariant.entries) {
            val payload = byteArrayOf(0x01, 0x02, 0x03)
            val frame = QgjCodec.frame(variant, 0x1234, payload)
            val parsed = QgjCodec.parse(frame)!!
            assertEquals(variant, parsed.variant)
            assertEquals(0x1234, parsed.commandId)
            assertArrayEquals(payload, parsed.payload)
            assertNull(parsed.errorCode)
        }
    }

    @Test
    fun parse_rejectsWrongLengthAndUnknownHeader() {
        val frame = QgjCodec.frame(QgjVariant.V1, 0x1002, byteArrayOf(1, 2))
        assertNull(QgjCodec.parse(frame.copyOf().also { it[3] = 0x09 }))
        assertNull(QgjCodec.parse(byteArrayOf(0x11, 0, 0, 6, 0x10, 0x02)))
    }

    @Test
    fun parse_v2ErrorNibbleMapsToNegativeCode() {
        val parsed = QgjCodec.parse(byteArrayOf(0xA7.toByte(), 0x20, 0x00, 0x00))!!
        assertEquals(QgjVariant.V2, parsed.variant)
        assertEquals(-2, parsed.errorCode)
    }

    @Test
    fun parse_gpsLowNibbleError() {
        val parsed = QgjCodec.parse(byteArrayOf(0xA6.toByte(), 0x03, 0x00, 0x00))!!
        assertEquals(QgjVariant.GPS, parsed.variant)
        assertEquals(-3, parsed.errorCode)
    }

    @Test
    fun parse_v1ErrorCommand() {
        val parsed = QgjCodec.parse(
            byteArrayOf(0xA5.toByte(), 0x00, 0x00, 0x06, 0xF0.toByte(), 0x01),
        )!!
        assertEquals(-3, parsed.errorCode)
    }

    @Test
    fun opCodes_matchNomalCommand() {
        assertEquals(1, QgjCodec.opCode(ControlAction.UNLOCK))
        assertEquals(2, QgjCodec.opCode(ControlAction.LOCK))
        assertEquals(3, QgjCodec.opCode(ControlAction.POWER_ON))
        assertEquals(4, QgjCodec.opCode(ControlAction.POWER_OFF))
        assertEquals(7, QgjCodec.opCode(ControlAction.SEAT))
        assertEquals(8, QgjCodec.opCode(ControlAction.FIND))
        assertArrayEquals(byteArrayOf(1), QgjCodec.controlPayload(ControlAction.UNLOCK))
    }

    @Test
    fun everyControlActionHasDistinctOpCode() {
        val expected = linkedMapOf(
            ControlAction.UNLOCK to 1,
            ControlAction.LOCK to 2,
            ControlAction.POWER_ON to 3,
            ControlAction.POWER_OFF to 4,
            ControlAction.SEAT to 7,
            ControlAction.FIND to 8,
        )
        assertEquals(ControlAction.entries.toSet(), expected.keys)
        val payloads = expected.mapValues { (action, code) ->
            assertEquals(code, QgjCodec.opCode(action))
            QgjCodec.controlPayload(action).single().toInt() and 0xFF
        }
        assertEquals(ControlAction.entries.size, payloads.values.toSet().size)
    }

    @Test
    fun loginPayload_v1HasPasswordOnlyAndV2AddsUid() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x00, 0x30, 0x39),
            QgjCodec.loginPayload("12345", "67890", QgjVariant.V1),
        )
        assertArrayEquals(
            byteArrayOf(
                0x00, 0x00, 0x30, 0x39,
                0x00, 0x01, 0x09, 0x32,
            ),
            QgjCodec.loginPayload("12345", "67890", QgjVariant.V2),
        )
    }

    @Test
    fun loginPayload_unsignedPasswordOverflowsToBits() {
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()),
            QgjCodec.loginPayload("4294967295", "0", QgjVariant.V1),
        )
    }

    @Test
    fun bindPayload_v1WritesMainAndChildrenFourBytesEach() {
        val payload = QgjCodec.bindPayload(
            mainPassword = "1",
            childPasswords = listOf("2", "3"),
            variant = QgjVariant.V1,
            legacyV2 = true,
        )
        assertArrayEquals(
            byteArrayOf(
                0, 0, 0, 1,
                0, 0, 0, 2,
                0, 0, 0, 3,
            ),
            payload,
        )
    }

    @Test
    fun bindPayload_v1KeepsAtMostSixPasswords() {
        val payload = QgjCodec.bindPayload(
            mainPassword = "1",
            childPasswords = listOf("2", "3", "4", "5", "6", "7", "8"),
            variant = QgjVariant.V1,
            legacyV2 = true,
        )
        assertEquals(6 * 4, payload.size)
    }

    @Test
    fun bindPayload_v2LegacyHasNoPrefix() {
        val payload = QgjCodec.bindPayload(
            mainPassword = "1",
            childPasswords = listOf("2"),
            variant = QgjVariant.V2,
            legacyV2 = true,
        )
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2), payload)
    }

    @Test
    fun bindPayload_v2NonLegacyAppendsNamePrefix() {
        val payload = QgjCodec.bindPayload(
            mainPassword = "1",
            childPasswords = listOf("2"),
            variant = QgjVariant.V2,
            legacyV2 = false,
        )
        assertArrayEquals(
            byteArrayOf(0, 0, 0, 1, 0, 0, 0, 2, 0x02, 'T'.code.toByte(), 'L'.code.toByte()),
            payload,
        )
    }

    @Test
    fun bindPayload_v2KeepsMainAndOneChild() {
        val payload = QgjCodec.bindPayload(
            mainPassword = "1",
            childPasswords = listOf("2", "3"),
            variant = QgjVariant.V2,
            legacyV2 = true,
        )
        assertEquals(8, payload.size)
    }

    @Test
    fun identityMac_fromEightByteAdvertisement() {
        val data = byteArrayOf(
            0x80.toByte(), 0x00,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        assertEquals("AABBCCDDEEFF", QgjCodec.identityMac(data))
        assertTrue(QgjCodec.isLegacyV2(data))
    }

    @Test
    fun identityMac_fromSixByteAdvertisement() {
        val data = byteArrayOf(
            0x11, 0x22, 0x33, 0x44, 0x55, 0x66,
        )
        assertEquals("112233445566", QgjCodec.identityMac(data))
        assertTrue(QgjCodec.isLegacyV2(data))
    }

    @Test
    fun manufacturerData_missingOrUnknownHasNoParsedMac() {
        assertNull(QgjCodec.identityMac(null))
        assertNull(QgjCodec.identityMac(ByteArray(7)))
        // ScannedDevice 在广播缺失时不写 IS_LEGACY_V2，读取结果为 false。
        assertFalse(QgjCodec.isLegacyV2(null))
        assertFalse(QgjCodec.isLegacyV2(ByteArray(0)))
        assertTrue(QgjCodec.isLegacyV2(ByteArray(7)))
        assertFalse(
            QgjCodec.isLegacyV2(
                byteArrayOf(0x00, 0x00, 1, 2, 3, 4, 5, 6),
            ),
        )
    }

    @Test
    fun gpsString_roundTripWithoutLengthPrefix() {
        val encoded = QgjCodec.gpsStringPayload("860123456789012")
        assertEquals(15, encoded.size)
        assertArrayEquals("860123456789012".toByteArray(), encoded)
        assertEquals("860123456789012", QgjCodec.decodeGpsString(encoded))
    }

    @Test
    fun statusOk_onlyStatusOneSucceeds() {
        assertTrue(QgjCodec.statusOk(byteArrayOf(1)))
        assertFalse(QgjCodec.statusOk(byteArrayOf(0)))
        assertFalse(QgjCodec.statusOk(ByteArray(0)))
    }

    @Test
    fun gpsActivatePayload_matchesGpsActivateSetOn101() {
        // f0.encode: GpsOpCode.Activate→0x02, SwitchState.ON.ordinal→0x01, 101 as UINT32 BE.
        assertArrayEquals(
            byteArrayOf(0x02, 0x01, 0x00, 0x00, 0x00, 0x65),
            QgjCodec.gpsActivatePayload(),
        )
        assertArrayEquals(
            byteArrayOf(
                0xA7.toByte(), 0x00, 0x00, 0x08,
                0x30, 0x55,
                0x02, 0x01, 0x00, 0x00, 0x00, 0x65,
            ),
            QgjCodec.frame(
                QgjVariant.V2,
                QgjCodec.CMD_GPS_SET_STATUS,
                QgjCodec.gpsActivatePayload(),
            ),
        )
        assertArrayEquals(
            byteArrayOf(0xA7.toByte(), 0x00, 0x00, 0x02, 0x21, 0x22),
            QgjCodec.frame(QgjVariant.V2, QgjCodec.CMD_ECU_GPS_CONNECT_LOCAL, ByteArray(0)),
        )
    }

    @Test
    fun harmony_detectedOnlyFromFdeeServiceData() {
        val harmony = java.util.UUID.fromString("0000fdee-0000-1000-8000-00805f9b34fb")
        val other = java.util.UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb")
        assertTrue(QgjCodec.isHarmonyServiceData(listOf(other, harmony)))
        assertFalse(QgjCodec.isHarmonyServiceData(listOf(other)))
        assertFalse(QgjCodec.isHarmonyServiceData(emptyList()))
    }

    @Test
    fun scanIdentity_nonHarmonyPrefersManufacturerIdentity() {
        val data8 = byteArrayOf(
            0x00, 0x11,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        assertEquals(
            "AABBCCDDEEFF",
            QgjCodec.identityMacForScan("11:22:33:44:55:66", data8, harmony = false),
        )
        val data6 = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        assertEquals(
            "010203040506",
            QgjCodec.identityMacForScan("11:22:33:44:55:66", data6, harmony = false),
        )
    }

    @Test
    fun scanIdentity_nonHarmonyFallsBackToAdvertisedAddress() {
        assertEquals(
            "112233445566",
            QgjCodec.identityMacForScan("11:22:33:44:55:66", null, harmony = false),
        )
        assertEquals(
            "112233445566",
            QgjCodec.identityMacForScan(
                "11:22:33:44:55:66",
                byteArrayOf(1, 2),
                harmony = false,
            ),
        )
    }

    @Test
    fun scanIdentity_harmonyUsesLastByteMinusTwo() {
        assertEquals(
            "112233445564",
            QgjCodec.identityMacForScan("11:22:33:44:55:66", null, harmony = true),
        )
        // 末字节 0x01 - 2 按 byte 回绕为 0xFF。
        assertEquals(
            "1122334455FF",
            QgjCodec.identityMacForScan("11:22:33:44:55:01", null, harmony = true),
        )
    }

    @Test
    fun scanIdentity_harmonyIgnoresManufacturerData() {
        val data = byteArrayOf(
            0x00, 0x11,
            0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(),
            0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte(),
        )
        assertEquals(
            "112233445564",
            QgjCodec.identityMacForScan("11:22:33:44:55:66", data, harmony = true),
        )
    }

    @Test
    fun matchesIdentity_ignoresSeparatorsAndCase() {
        assertTrue(QgjCodec.matchesIdentity("aa:bb:cc:dd:ee:ff", "AABBCCDDEEFF"))
        assertTrue(QgjCodec.matchesIdentity("1122334455667788", "11:22:33:44:55:66:77:88"))
        assertFalse(QgjCodec.matchesIdentity(null, "AABBCCDDEEFF"))
        assertFalse(QgjCodec.matchesIdentity("AABBCCDDEEFF", ""))
        assertFalse(QgjCodec.matchesIdentity("AABBCCDDEEFE", "AABBCCDDEEFF"))
    }
}
