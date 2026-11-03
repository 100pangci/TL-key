package com.ywpc.tlkey.protocol

import org.junit.Assert.assertEquals
import org.junit.Test

class CodecTest {

    @Test
    fun hexRoundTrip() {
        val bytes = byteArrayOf(0x00, 0x0F, 0x7F, 0x80.toByte(), 0xFF.toByte())
        assertEquals("000F7F80FF", HexCodec.encode(bytes))
        assertEquals("000F7F80FF", HexCodec.encode(HexCodec.decode("000f7f80ff")))
    }

    @Test
    fun authHex8() {
        assertEquals("075BCD15", AuthCodec.hex8("123456789"))
        assertEquals("00003039", AuthCodec.hex8("12345"))
    }

    @Test
    fun checksum() {
        assertEquals(2, Checksum.sum8(byteArrayOf(1, 2, 0xFF.toByte())))
        assertEquals("02", Checksum.sum8Hex(byteArrayOf(1, 2, 0xFF.toByte())))
    }

    @Test
    fun selector() {
        assertEquals(ProtocolFamily.LEGACY_78, ProtocolSelector.familyFor(1))
        assertEquals(ProtocolFamily.LEGACY_78, ProtocolSelector.familyFor(2))
        assertEquals(ProtocolFamily.NEW_85, ProtocolSelector.familyFor(3))
        assertEquals(ProtocolFamily.NEW_85, ProtocolSelector.familyFor(2201))
        assertEquals(ProtocolFamily.QGJ, ProtocolSelector.familyFor(8))
        assertEquals(ProtocolFamily.QGJ, ProtocolSelector.familyFor(283))
        assertEquals(ProtocolFamily.QGJ, ProtocolSelector.create(8)?.family)
        assertEquals(ProtocolFamily.QGJ, ProtocolSelector.create(283)?.family)
    }
}
