// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BluetoothMemoryTest {
    private val car = "2C:D1:C6:00:00:01"
    private val headset = "AA:BB:CC:00:00:02"

    @Test fun aDeviceIsGivenUpOnAfterTwoDropsInARow() {
        var memory = emptyMap<String, BluetoothMemory.Entry>()
        assertFalse(BluetoothMemory.skipped(memory, car))
        memory = BluetoothMemory.dropped(memory, car, "My Car")
        assertFalse(BluetoothMemory.skipped(memory, car))       // once can be chance
        memory = BluetoothMemory.dropped(memory, car, "My Car")
        assertTrue(BluetoothMemory.skipped(memory, car))
    }

    @Test fun aNoteThatHeldStartsTheCountAgain() {
        var memory = BluetoothMemory.dropped(emptyMap(), car, "My Car")
        memory = BluetoothMemory.forget(memory, car)
        memory = BluetoothMemory.dropped(memory, car, "My Car")
        assertFalse(BluetoothMemory.skipped(memory, car))       // the drops are only counted in a row
    }

    @Test fun anotherDeviceIsNotAffected() {
        val memory = BluetoothMemory.dropped(BluetoothMemory.dropped(emptyMap(), car, "My Car"), car, "My Car")
        assertTrue(BluetoothMemory.skipped(memory, car))
        assertFalse(BluetoothMemory.skipped(memory, headset))
    }

    @Test fun theFirstDeviceThatIsNotGivenUpOnIsChosen() {
        val memory = BluetoothMemory.dropped(BluetoothMemory.dropped(emptyMap(), car, "My Car"), car, "My Car")
        assertEquals(headset, BluetoothMemory.firstUsable(listOf(car, headset), memory) { it })
        assertNull(BluetoothMemory.firstUsable(listOf(car), memory) { it })
        assertEquals(car, BluetoothMemory.firstUsable(listOf(car), emptyMap()) { it })
        assertNull(BluetoothMemory.firstUsable(emptyList<String>(), emptyMap()) { it })
    }

    @Test fun tryAgainClearsTheDevice() {
        val memory = BluetoothMemory.dropped(BluetoothMemory.dropped(emptyMap(), car, "My Car"), car, "My Car")
        assertTrue(BluetoothMemory.skippedEntries(memory).isNotEmpty())
        assertTrue(BluetoothMemory.skippedEntries(BluetoothMemory.forget(memory, car)).isEmpty())
    }

    @Test fun onlyGivenUpDevicesAreListed() {
        var memory = BluetoothMemory.dropped(emptyMap(), headset, "Headset")
        memory = BluetoothMemory.dropped(BluetoothMemory.dropped(memory, car, "My Car"), car, "My Car")
        assertEquals(listOf("My Car"), BluetoothMemory.skippedEntries(memory).map { it.name })
    }

    @Test fun whatIsStoredComesBackIdentical() {
        val memory = BluetoothMemory.dropped(BluetoothMemory.dropped(emptyMap(), car, "My Car"), car, "My Car") +
            (headset to BluetoothMemory.Entry(headset, "Headset with a space", 1))
        assertEquals(memory, BluetoothMemory.decode(BluetoothMemory.encode(memory.values)))
    }

    @Test fun nothingOrDamagedTextMeansNothingLearned() {
        assertTrue(BluetoothMemory.decode(null).isEmpty())
        assertTrue(BluetoothMemory.decode("").isEmpty())
        assertTrue(BluetoothMemory.decode("garbage\nno-tabs-here\n$car\tx\tName\n$car\t0\tName\n\t3\tNoId").isEmpty())
        // a good line next to a bad one survives
        assertEquals(setOf(car), BluetoothMemory.decode("garbage\n$car\t2\tMy Car").keys)
    }

    @Test fun aNameThatWouldBreakTheFormatIsCleaned() {
        val memory = BluetoothMemory.dropped(emptyMap(), car, "Evil\tname\nwith lines")
        val restored = BluetoothMemory.decode(BluetoothMemory.encode(memory.values))
        assertEquals(memory, restored)
        assertFalse(restored.getValue(car).name.any { it.isISOControl() })
        assertEquals("Bluetooth device", BluetoothMemory.dropped(emptyMap(), car, "  \n ").getValue(car).name)
    }
}
