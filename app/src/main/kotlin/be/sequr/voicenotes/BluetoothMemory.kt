// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

/**
 * What the phone has learned about Bluetooth microphones whose link did not hold: a car head unit that takes the audio back right
 * after the hands-free link opens, so that a note has to carry on with the phone's own microphone. After [LIMIT] such drops in a
 * row the device is no longer tried and the phone's microphone is used from the start; a note that runs on over the Bluetooth
 * microphone, or "Try again" on the Triggers screen, clears the count. Nothing here is shipped with the app or leaves the phone:
 * every phone learns about the devices it meets.
 */
object BluetoothMemory {
    /** Drops in a row after which a device is no longer tried. */
    const val LIMIT = 2

    /** [id] is the device's address, [name] only for the screen. */
    data class Entry(val id: String, val name: String, val drops: Int)

    fun skipped(memory: Map<String, Entry>, id: String): Boolean = (memory[id]?.drops ?: 0) >= LIMIT

    /** [id] took the audio back right after the start of a note. */
    fun dropped(memory: Map<String, Entry>, id: String, name: String): Map<String, Entry> =
        memory + (id to Entry(id, clean(name), (memory[id]?.drops ?: 0) + 1))

    /** A note went on over [id] past the first seconds, or the user asked to try it again. */
    fun forget(memory: Map<String, Entry>, id: String): Map<String, Entry> = memory - id

    /** The first of [devices] that has not been given up on. */
    fun <T> firstUsable(devices: List<T>, memory: Map<String, Entry>, idOf: (T) -> String): T? =
        devices.firstOrNull { !skipped(memory, idOf(it)) }

    /** The devices to show as given up on. */
    fun skippedEntries(memory: Map<String, Entry>): List<Entry> = memory.values.filter { it.drops >= LIMIT }.sortedBy { it.name }

    // One line per device: address, count, name (separated by tabs). Anything that does not parse is ignored.
    fun encode(memory: Collection<Entry>): String = memory.joinToString("\n") { "${it.id}\t${it.drops}\t${it.name}" }

    fun decode(text: String?): Map<String, Entry> = text.orEmpty().lineSequence().mapNotNull { line ->
        val parts = line.split('\t', limit = 3)
        val drops = parts.getOrNull(1)?.toIntOrNull()
        if (parts.size == 3 && parts[0].isNotEmpty() && drops != null && drops > 0) Entry(parts[0], parts[2], drops) else null
    }.associateBy { it.id }

    private fun clean(name: String): String = name.map { if (it.isISOControl()) ' ' else it }.joinToString("").trim().ifEmpty { "Bluetooth device" }
}
