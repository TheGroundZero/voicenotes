// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.util.Log

/** Which Bluetooth microphones (a headset, or a car's hands-free system) Android offers right now. */
object BluetoothMic {
    fun hasPermission(context: Context) =
        context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    /**
     * What to store in [Note.micSource] for a Bluetooth microphone: `car:<name>` when the device says it is a car's
     * audio or hands-free system (Bluetooth device class), otherwise `bluetooth:<name>`. Cars do not always say so
     * truthfully, so this is a best guess for the icon in the list, not something to rely on.
     */
    @SuppressLint("MissingPermission") // checked by hasPermission(); a SecurityException is also caught below
    fun sourceFor(context: Context, device: AudioDeviceInfo): String {
        val name = device.productName.toString()
        val deviceClass = if (!hasPermission(context)) null else runCatching {
            context.getSystemService(BluetoothManager::class.java).adapter.getRemoteDevice(device.address).bluetoothClass?.deviceClass
        }.getOrNull()
        val isCar = deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO || deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE
        return (if (isCar) MIC_CAR_PREFIX else MIC_BLUETOOTH_PREFIX) + name
    }

    fun available(context: Context): List<AudioDeviceInfo> {
        if (!hasPermission(context)) return emptyList()
        return context.getSystemService(AudioManager::class.java).availableCommunicationDevices
            .filter { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
    }
}

/**
 * Routes the recording to a Bluetooth microphone for the length of one note, if the user turned that on and
 * one is connected. Otherwise (or if the link does not come up) the phone's own microphone is used.
 *
 * Android only records from a Bluetooth microphone once the "communication device" has been switched to it,
 * which opens the hands-free link; that can take a moment, so [acquire] reports back asynchronously. A car
 * treats an open link like a call, so music may stop and the quality is that of a hands-free call; both are
 * to be judged in the car.
 */
class MicrophoneRoute(private val context: Context, private val handler: Handler) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var routed = false
    private var pending: Pending? = null

    private inner class Pending(val device: AudioDeviceInfo, val onReady: (AudioDeviceInfo?) -> Unit) {
        val listener = AudioManager.OnCommunicationDeviceChangedListener { changed -> if (changed?.id == device.id) finish(device) }
        val timeout = Runnable { Log.w(TAG, "Bluetooth microphone did not connect in time, using the phone's"); clear(); finish(null) }
        var done = false

        fun finish(result: AudioDeviceInfo?) {
            if (done) return
            done = true
            audioManager.removeOnCommunicationDeviceChangedListener(listener)
            handler.removeCallbacks(timeout)
            pending = null
            Log.i(TAG, if (result != null) "recording from the bluetooth microphone" else "bluetooth microphone not used, falling back to the phone's")
            onReady(result)
        }
    }

    /** Calls [onReady] on the main thread with the device to record from, or null for the phone's microphone. */
    fun acquire(onReady: (AudioDeviceInfo?) -> Unit) {
        val available = if (Settings.bluetoothMic(context)) BluetoothMic.available(context) else emptyList()
        val device = BluetoothMemory.firstUsable(available, Settings.bluetoothMemory(context)) { it.address }
        if (device == null) {
            val why = when {
                !Settings.bluetoothMic(context) -> "bluetooth option off"
                available.isEmpty() -> "bluetooth option on, no device"
                else -> "bluetooth option on, but the device took the audio back ${BluetoothMemory.LIMIT} times in a row"
            }
            Log.i(TAG, "recording with the phone's microphone ($why)")
            onReady(null)
            return
        }
        Log.i(TAG, "bluetooth microphone available: type=${device.type}")
        if (audioManager.communicationDevice?.id == device.id) {
            routed = true
            onReady(device)
            return
        }
        val request = Pending(device, onReady)
        pending = request
        audioManager.addOnCommunicationDeviceChangedListener(context.mainExecutor, request.listener)
        if (!audioManager.setCommunicationDevice(device)) {
            request.finish(null)
            return
        }
        routed = true
        handler.postDelayed(request.timeout, CONNECT_TIMEOUT_MS)
    }

    /** Gives the microphone back (also cancels a start that is still waiting for the link). */
    fun release() {
        pending?.let {
            it.done = true
            audioManager.removeOnCommunicationDeviceChangedListener(it.listener)
            handler.removeCallbacks(it.timeout)
            pending = null
        }
        clear()
    }

    private fun clear() {
        if (routed) audioManager.clearCommunicationDevice()
        routed = false
    }

    private companion object {
        const val TAG = "MicrophoneRoute"
        const val CONNECT_TIMEOUT_MS = 3_000L
    }
}
