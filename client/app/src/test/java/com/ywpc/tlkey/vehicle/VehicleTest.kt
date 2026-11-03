package com.ywpc.tlkey.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VehicleTest {

    @Test
    fun formatMac_acceptsTwelveHexCharsOnly() {
        assertEquals("AA:BB:CC:DD:EE:FF", Vehicle.formatMac("aabbccddeeff"))
        assertEquals("AA:BB:CC:DD:EE:FF", Vehicle.formatMac("AA:BB:CC:DD:EE:FF"))
        assertNull(Vehicle.formatMac("AABBCCDDEE"))
        assertNull(Vehicle.formatMac(""))
    }

    @Test
    fun bleAddress_legacyUsesBtMacFirst() {
        val legacy = vehicle(modelType = 1, mac = "AABBCCDDEEFF", btMac = "112233445566")
        assertEquals("11:22:33:44:55:66", legacy.bleAddress)
    }

    @Test
    fun bleAddress_newProtocolUsesMacFirst() {
        val modern = vehicle(modelType = 3, mac = "AABBCCDDEEFF", btMac = "112233445566")
        assertEquals("AA:BB:CC:DD:EE:FF", modern.bleAddress)
    }

    @Test
    fun hasBleCredentials_requiresKnownModelType() {
        val unknown = vehicle(modelType = 0, mac = "AABBCCDDEEFF", passwordMain = "123456")
        assertFalse(unknown.hasBleCredentials)
    }

    @Test
    fun hasBleCredentials_modernNeedsPassword() {
        assertFalse(vehicle(modelType = 3, mac = "AABBCCDDEEFF").hasBleCredentials)
        assertTrue(
            vehicle(modelType = 3, mac = "AABBCCDDEEFF", passwordMain = "123456")
                .hasBleCredentials,
        )
    }

    @Test
    fun hasBleCredentials_legacyNeedsOnlyAddress() {
        assertTrue(vehicle(modelType = 1, btMac = "112233445566").hasBleCredentials)
        assertFalse(vehicle(modelType = 1).hasBleCredentials)
    }

    @Test
    fun mergedWith_keepsCachedBleCredentialsWhenListOmitsThem() {
        val cached = vehicle(
            modelType = 3,
            mac = "AABBCCDDEEFF",
            btName = "TAILG_BEUOZB",
            passwordMain = "123456",
            passwordChildren = listOf("654321"),
        )
        val fresh = vehicle(modelType = 3, name = "新车名", mac = "")

        val merged = cached.mergedWith(fresh)

        assertEquals("新车名", merged.name)
        assertEquals("AABBCCDDEEFF", merged.mac)
        assertEquals("123456", merged.passwordMain)
        assertEquals(listOf("654321"), merged.passwordChildren)
        assertEquals("TAILG_BEUOZB", merged.btName)
    }

    @Test
    fun mergedWith_updatesModelTypeWhenListProvidesIt() {
        val cached = vehicle(modelType = 0)
        val fresh = vehicle(modelType = 10)

        assertEquals(10, cached.mergedWith(fresh).modelType)
    }

    private fun vehicle(
        modelType: Int,
        name: String = "车",
        mac: String = "",
        btName: String? = null,
        btMac: String? = null,
        passwordMain: String = "",
        passwordChildren: List<String> = emptyList(),
    ): Vehicle = Vehicle(
        id = "car-1",
        name = name,
        carType = "T",
        modelType = modelType,
        mac = mac,
        btName = btName,
        btMac = btMac,
        btPin = null,
        passwordMain = passwordMain,
        passwordChildren = passwordChildren,
        shareCarFlag = false,
        frame = "F",
        imei = "I",
        userId = "U",
    )
}
