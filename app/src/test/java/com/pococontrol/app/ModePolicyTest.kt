package com.pococontrol.app

import org.junit.Assert.*
import org.junit.Test

class ModePolicyTest {
    @Test fun wifiKeepsWirelessAndDeveloperOn() {
        assertEquals(listOf("1", "0", "1"), ModePolicy.target("wifi", listOf("0", "0", "0")))
        assertEquals(listOf(1), ModePolicy.order("wifi"))
        assertTrue(ModePolicy.wifiPreflight("wifi", listOf("1", "1", "1")))
        assertFalse(ModePolicy.wifiPreflight("wifi", listOf("0", "1", "1")))
        assertFalse(ModePolicy.wifiPreflight("wifi", listOf("1", "1", "0")))
        assertFalse(ModePolicy.wifiPreflight("wifi", emptyList()))
    }
    @Test fun fullModeRequestsAllOff() {
        assertEquals(listOf("0", "0", "0"), ModePolicy.target("poco", listOf("1", "1", "1")))
        assertEquals(listOf(1, 2, 0), ModePolicy.order("poco"))
    }
    @Test fun normalUsesTheSavedValues() {
        val saved = listOf("1", "null", "0")
        assertEquals(saved, ModePolicy.target("normal", saved))
        assertEquals(setOf(0, 1, 2), ModePolicy.order("normal").toSet())
    }
    @Test fun pairingCodeRequiresSixAsciiDigits() {
        assertTrue(isPairingCode("123456")); assertTrue(isPairingCode("000000"))
        assertFalse(isPairingCode("12345")); assertFalse(isPairingCode("1234567"))
        assertFalse(isPairingCode("12 456")); assertFalse(isPairingCode("１２３４５６"))
    }
}
