package com.ywpc.tlkey.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EcuCodecTest {

    // b4=0xB4 b5=0x24 b6=0xA0 b7=0 b8=0x94 b9=0x0A b10=0x50 b11=0
    private val fcc1Read = "00000000B424A000948A5000"

    // 与官方 readEcu fcc1 的位映射一致。
    @Test
    fun parseFcc1ReadsAbilitiesAndStates() {
        val state = EcuCodec.parseFcc1(fcc1Read)!!
        assertEquals(EcuChannel.FCC1, state.channel)
        assertEquals("10010100", state.state1)
        assertEquals("10001010", state.state2)
        assertEquals("01010000", state.state3)

        assertTrue(state.flag(EcuCodec.TYPE_TCS)!!)
        assertTrue(state.flag(EcuCodec.TYPE_SFR)!!)
        assertFalse(state.flag(EcuCodec.TYPE_PCP)!!)
        assertTrue(state.flag(EcuCodec.TYPE_CC)!!)
        assertFalse(state.flag(EcuCodec.TYPE_REVERSE)!!)
        assertFalse(state.flag(EcuCodec.TYPE_SFS)!!)
        assertTrue(state.flag(EcuCodec.TYPE_LBCM)!!)
        assertTrue(state.flag(EcuCodec.TYPE_HILL_PARKING)!!)
        assertFalse(state.flag(EcuCodec.TYPE_SLIP_SLOPE)!!)

        assertEquals("01", state.bits(EcuCodec.TYPE_HDC))
        assertEquals("01", state.bits(EcuCodec.TYPE_ER))
        assertEquals("010", state.bits(EcuCodec.TYPE_PODG))

        assertTrue(state.abilities.tcs!!)
        assertTrue(state.abilities.sfs!!)
        assertTrue(state.abilities.sfr!!)
        assertFalse(state.abilities.pcp!!)
        assertTrue(state.abilities.cc!!)
        assertFalse(state.abilities.reverse!!)
        assertTrue(state.abilities.er!!)
        assertTrue(state.abilities.lbcm!!)
        assertTrue(state.abilities.hdc!!)
        assertTrue(state.abilities.slipSlope!!)
        assertFalse(state.abilities.hillParking!!)
        assertTrue(state.abilities.podg!!)
        assertNull(state.abilities.temp)
    }

    @Test
    fun fcc1FrameMatchesOfficialPrefixAndBitPositions() {
        val state = EcuCodec.parseFcc1(fcc1Read)!!
        val frame = EcuCodec.frame(EcuCodec.setFlag(state, EcuCodec.TYPE_TCS, false))
        assertEquals("00070002148A50", EcuCodec.hex(frame))
    }

    @Test
    fun hdcErAndPodgReplaceExactBitRanges() {
        val state = EcuCodec.parseFcc1(fcc1Read)!!
        val hdc = EcuCodec.setBits(state, EcuCodec.TYPE_HDC, "10")
        assertEquals("10010000", hdc.state3)
        assertEquals("00070002948A90", EcuCodec.hex(EcuCodec.frame(hdc)))

        val er = EcuCodec.setBits(state, EcuCodec.TYPE_ER, "10")
        assertEquals("10010010", er.state2)

        val podg = EcuCodec.setBits(state, EcuCodec.TYPE_PODG, "111")
        assertEquals("10001111", podg.state2)
    }

    @Test
    fun everySingleBitTypeMapsToExpectedPosition() {
        val state = EcuCodec.parseFcc1(fcc1Read)!!
        val expectations = mapOf(
            EcuCodec.TYPE_TCS to Triple(1, 0, "00010100"),
            EcuCodec.TYPE_SFR to Triple(1, 3, "10000100"),
            EcuCodec.TYPE_PCP to Triple(1, 4, "10011100"),
            EcuCodec.TYPE_CC to Triple(1, 5, "10010000"),
            EcuCodec.TYPE_REVERSE to Triple(1, 6, "10010110"),
            EcuCodec.TYPE_SFS to Triple(1, 2, "10110100"),
            EcuCodec.TYPE_LBCM to Triple(2, 0, "00001010"),
            EcuCodec.TYPE_NITRO to Triple(2, 1, "11001010"),
            EcuCodec.TYPE_HILL_PARKING to Triple(3, 3, "01000000"),
            EcuCodec.TYPE_SLIP_SLOPE to Triple(3, 2, "01110000"),
        )
        expectations.forEach { (type, expected) ->
            val (stateIndex, _, flipped) = expected
            val next = EcuCodec.setFlag(state, type, state.flag(type) != true)
            val actual = when (stateIndex) {
                1 -> next.state1
                2 -> next.state2
                else -> next.state3
            }
            assertEquals("type $type", flipped, actual)
        }
    }

    @Test
    fun fbb1ParseAndFrameRoundTrip() {
        val read = "D0018E04B424A0000000AA948A5000"
        val state = EcuCodec.parseFbb1(read)!!
        assertEquals(EcuChannel.FBB1, state.channel)
        assertEquals("AA", state.value1)
        assertEquals("00", state.value2)
        assertEquals("10010100", state.state1)
        assertEquals("10001010", state.state2)
        assertEquals("01010000", state.state3)
        assertTrue(state.abilities.podg!!)

        assertEquals(
            "D0018E0A00FFFFFF00AA948A5000",
            EcuCodec.hex(EcuCodec.frame(state)),
        )
    }

    @Test
    fun fbb1SupportsTemperatureButFcc1DoesNot() {
        val state = EcuCodec.parseFbb1("D0018E04B424A0000000AA948A5000")!!
        val temp = EcuCodec.setFlag(state, EcuCodec.TYPE_TEMP, true)
        assertEquals("01010010", temp.state3)
        assertTrue(EcuCodec.hex(EcuCodec.frame(temp)).contains("52"))
        assertFalse(EcuCodec.supports(EcuChannel.FCC1, EcuCodec.TYPE_TEMP))
        assertTrue(EcuCodec.supports(EcuChannel.FBB1, EcuCodec.TYPE_TEMP))
    }

    @Test
    fun malformedReadIsRejected() {
        assertNull(EcuCodec.parseFcc1("00"))
        assertNull(EcuCodec.parseFbb1("D0018E04"))
        assertNull(EcuCodec.parseFcc1("00000000ZZ24A000948A5000"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidMultiBitValueIsRejected() {
        val state = EcuCodec.parseFcc1(fcc1Read)!!
        EcuCodec.setBits(state, EcuCodec.TYPE_HDC, "2")
    }

    @Test
    fun podgSupportMatchesOfficialGpsFlagRule() {
        // b11 index4=1 且 index6=0 时官方把 PODG 能力置 0。
        val unsupported = "00000000B424A000940A5008"
        assertFalse(EcuCodec.parseFcc1(unsupported)!!.abilities.podg!!)
    }
}
