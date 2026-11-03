package com.ywpc.tlkey.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BindingNamesTest {

    @Test
    fun mapsOfficialBroadcastPrefixesToModelTypes() {
        assertEquals(3, BindingNames.modelTypeFor("TAILG_BEUOZB-1234"))
        assertEquals(1601, BindingNames.modelTypeFor("TAILG_AAIFDX"))
        assertEquals(1501, BindingNames.modelTypeFor("TAILG_JIFSAD"))
        assertEquals(1701, BindingNames.modelTypeFor("TAILG_HGSAIJ"))
        assertEquals(10, BindingNames.modelTypeFor("TAILG_JW-001"))
        assertEquals(10, BindingNames.modelTypeFor("TAILG_JLFBXW"))
        assertEquals(401, BindingNames.modelTypeFor("TAILG_BGPSBEOZB"))
        assertEquals(928, BindingNames.modelTypeFor("TAILG_BGPSJLBXW"))
        assertEquals(2201, BindingNames.modelTypeFor("TAILG_BGPSYSACY"))
        assertEquals(2103, BindingNames.modelTypeFor("TAILG_BGPSXIAIL"))
    }

    @Test
    fun mapsQgjPrefixes() {
        assertEquals(8, BindingNames.modelTypeFor("Hi-TAILING"))
        assertEquals(8, BindingNames.modelTypeFor("Q_BASH_01"))
        assertEquals(283, BindingNames.modelTypeFor("QDemo_2012"))
        assertEquals(283, BindingNames.modelTypeFor("TL_2022"))
    }

    @Test
    fun rejectsUnrelatedDevices() {
        assertNull(BindingNames.modelTypeFor("MI Band 8"))
        assertNull(BindingNames.modelTypeFor(null))
        assertFalse(BindingNames.isBindingDevice("TAILG_UNKNOWN"))
        assertTrue(BindingNames.isBindingDevice("TAILG_BGPSJLBXW"))
    }
}
