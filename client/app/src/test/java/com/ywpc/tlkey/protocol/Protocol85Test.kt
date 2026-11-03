package com.ywpc.tlkey.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Protocol85Test {

    // 合成测试密钥，与任何真实车型无关；真实密钥由构建时注入。
    private val key = "00112233445566778899AABBCCDDEEFF"
    private val protocol = Protocol85(key)
    private val credential = BleCredential(password = "123456789", uid = "12345")

    @Test
    fun tokenRequestGolden() {
        assertEquals(
            "555C8BE1FF98A171D2BF19178F9832E0",
            HexCodec.encode(protocol.buildTokenRequest()),
        )
    }

    @Test
    fun loginPlaintext() {
        val encrypted = protocol.buildLogin("AABBCCDD", credential)
        assertEquals(
            "850A4A11075BCD1500003039AABBCCDD",
            PacketCodec.decryptHex(key, encrypted),
        )
    }

    @Test
    fun loginGolden() {
        assertEquals(
            "D3297B0D05E7B6E9309B11AAFE541FA6",
            HexCodec.encode(protocol.buildLogin("AABBCCDD", credential)),
        )
    }

    @Test
    fun sharedPasswordUsesChild() {
        val shared = credential.copy(isShared = true, childPassword = "12345")
        assertEquals(
            "850A4A110000303900003039AABBCCDD",
            PacketCodec.decryptHex(key, protocol.buildLogin("AABBCCDD", shared)),
        )
    }

    @Test
    fun unlockGolden() {
        assertEquals(
            "8ADE72D615F4EC1DF7C250D468B75FED",
            HexCodec.encode(protocol.buildCommand(ControlAction.UNLOCK, "AABBCCDD")),
        )
    }

    @Test
    fun parseToken() {
        val frame = PacketCodec.encryptHex(key, "85000000112233445566778899AABBCC")
        assertEquals(ProtocolEvent.Token("11223344"), protocol.parseIncoming(frame))
    }

    @Test
    fun parseAuthSuccess() {
        val frame = PacketCodec.encryptHex(key, "8503B511010000000000000000000000")
        assertEquals(ProtocolEvent.AuthSuccess, protocol.parseIncoming(frame))
    }

    @Test
    fun parseAuthFailure() {
        val frame = PacketCodec.encryptHex(key, "8503B511000000000000000000000000")
        assertEquals(ProtocolEvent.AuthFailed, protocol.parseIncoming(frame))
    }

    @Test
    fun parseUnlockAck() {
        val frame = PacketCodec.encryptHex(key, "8503B521010000000000000000000000")
        val event = protocol.parseIncoming(frame)
        assertTrue(event is ProtocolEvent.Ack)
        event as ProtocolEvent.Ack
        assertEquals(ControlAction.UNLOCK, event.action)
        assertTrue(event.success)
    }

    @Test
    fun parseUnlockRejected() {
        val frame = PacketCodec.encryptHex(key, "8503B521000000000000000000000000")
        val event = protocol.parseIncoming(frame) as ProtocolEvent.Ack
        assertEquals(ControlAction.UNLOCK, event.action)
        assertTrue(!event.success)
    }

    @Test
    fun everyControlActionHasDistinctCommandAndAck() {
        val token = "AABBCCDD"
        val commands = linkedMapOf(
            ControlAction.LOCK to "85034A2000123456789ABCDE",
            ControlAction.UNLOCK to "85034A2100123456789ABCDE",
            ControlAction.POWER_ON to "85034A2200123456789ABCDE",
            ControlAction.POWER_OFF to "85034A2300123456789ABCDE",
            ControlAction.SEAT to "85034A2400123456789ABCDE",
            ControlAction.FIND to "85034A2500123456789ABCDE",
        )
        assertEquals(ControlAction.entries.toSet(), commands.keys)
        val ciphertexts = commands.mapValues { (action, plain) ->
            val encrypted = protocol.buildCommand(action, token)
            assertEquals(plain + token, PacketCodec.decryptHex(key, encrypted))
            HexCodec.encode(encrypted)
        }
        assertEquals(ControlAction.entries.size, ciphertexts.values.toSet().size)

        val replies = linkedMapOf(
            ControlAction.LOCK to "8503B520",
            ControlAction.UNLOCK to "8503B521",
            ControlAction.POWER_ON to "8503B522",
            ControlAction.POWER_OFF to "8503B523",
            ControlAction.SEAT to "8503B524",
            ControlAction.FIND to "8503B525",
        )
        assertEquals(ControlAction.entries.toSet(), replies.keys)
        replies.forEach { (action, prefix) ->
            val plain = prefix + "01" + "0".repeat(32 - prefix.length - 2)
            val event = protocol.parseIncoming(PacketCodec.encryptHex(key, plain))
                as ProtocolEvent.Ack
            assertEquals(action, event.action)
            assertTrue(event.success)
        }
    }
}
