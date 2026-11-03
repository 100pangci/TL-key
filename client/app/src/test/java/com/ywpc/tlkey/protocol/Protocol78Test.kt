package com.ywpc.tlkey.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Protocol78Test {

    // 合成测试密钥，与任何真实车型无关；真实密钥由构建时注入。
    private val key = "00112233445566778899AABBCCDDEEFF"
    private val protocol = Protocol78(key)

    @Test
    fun tokenRequestGolden() {
        assertEquals(
            "82F9B5794443E0B28E53DD9751D701E0",
            HexCodec.encode(protocol.buildTokenRequest()),
        )
    }

    @Test
    fun unlockPlaintext() {
        assertEquals(
            "7803C2020011111111111111AABBCCDD",
            PacketCodec.decryptHex(
                key,
                protocol.buildCommand(ControlAction.UNLOCK, "AABBCCDD"),
            ),
        )
    }

    @Test
    fun unlockGolden() {
        assertEquals(
            "1299DC7940917C2655D5528844A2489B",
            HexCodec.encode(protocol.buildCommand(ControlAction.UNLOCK, "AABBCCDD")),
        )
    }

    @Test
    fun noLoginForLegacy() {
        assertNull(protocol.buildLogin("AABBCCDD", BleCredential("123", "1")))
    }

    @Test
    fun parseToken() {
        val frame = PacketCodec.encryptHex(key, "78000000112233445566778899AABBCC")
        assertEquals(ProtocolEvent.Token("11223344"), protocol.parseIncoming(frame))
    }

    @Test
    fun parseLockSuccess() {
        val frame = PacketCodec.encryptHex(key, "010B0100000000000000000000000000")
        val event = protocol.parseIncoming(frame) as ProtocolEvent.Ack
        assertEquals(ControlAction.LOCK, event.action)
        assertTrue(event.success)
    }

    @Test
    fun parsePowerOffSuccessBeforeFailurePrefix() {
        val frame = PacketCodec.encryptHex(key, "07FF0B02000000000000000000000000")
        val event = protocol.parseIncoming(frame) as ProtocolEvent.Ack
        assertEquals(ControlAction.POWER_OFF, event.action)
        assertTrue(event.success)
    }

    @Test
    fun parseLockFailure() {
        val frame = PacketCodec.encryptHex(key, "01FF0000000000000000000000000000")
        val event = protocol.parseIncoming(frame) as ProtocolEvent.Ack
        assertEquals(ControlAction.LOCK, event.action)
        assertTrue(!event.success)
    }

    @Test
    fun everyControlActionHasDistinctCommandAndAck() {
        val token = "AABBCCDD"
        val commands = linkedMapOf(
            ControlAction.LOCK to "7803C2010011111111111111",
            ControlAction.UNLOCK to "7803C2020011111111111111",
            ControlAction.POWER_ON to "7803C2060011111111111111",
            ControlAction.POWER_OFF to "7803C2070011111111111111",
            ControlAction.SEAT to "7803C2050011111111111111",
            ControlAction.FIND to "7803C2080011111111111111",
        )
        assertEquals(ControlAction.entries.toSet(), commands.keys)
        val ciphertexts = commands.mapValues { (action, plain) ->
            val encrypted = protocol.buildCommand(action, token)
            assertEquals(plain + token, PacketCodec.decryptHex(key, encrypted))
            HexCodec.encode(encrypted)
        }
        assertEquals(ControlAction.entries.size, ciphertexts.values.toSet().size)

        val replies = linkedMapOf(
            ControlAction.LOCK to "010B01",
            ControlAction.UNLOCK to "020B02",
            ControlAction.POWER_ON to "060B02",
            ControlAction.POWER_OFF to "07FF0B02",
            ControlAction.SEAT to "0501",
            ControlAction.FIND to "0801",
        )
        assertEquals(ControlAction.entries.toSet(), replies.keys)
        replies.forEach { (action, prefix) ->
            val event = protocol.parseIncoming(
                PacketCodec.encryptHex(key, prefix.padEnd(32, '0')),
            ) as ProtocolEvent.Ack
            assertEquals(action, event.action)
            assertTrue(event.success)
        }
    }
}
