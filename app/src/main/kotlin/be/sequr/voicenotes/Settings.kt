// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.content.Context
import java.util.Locale

/** Tiny preferences wrapper. Languages are ISO 639-1 codes. */
object Settings {
    /** Languages offered as "preferred" (a curated subset of what whisper supports). */
    val supportedLanguages = listOf("en", "nl", "de", "fr", "es", "it", "pt", "sv", "da", "no", "fi", "pl")

    fun languageName(code: String): String =
        if (code == Transcriber.AUTO) "Auto" else Locale(code).getDisplayLanguage(Locale.ENGLISH).ifBlank { code.uppercase() }

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Auto-detection only chooses among these; the first one is the fallback. Never empty. */
    fun preferredLanguages(context: Context): List<String> =
        prefs(context).getString("preferred_languages", null)?.split(',')?.filter { it in supportedLanguages }?.ifEmpty { null }
            ?: listOf("en", "nl")

    fun setPreferredLanguages(context: Context, languages: List<String>) {
        require(languages.isNotEmpty())
        prefs(context).edit().putString("preferred_languages", languages.joinToString(",")).apply()
    }

    /** Language used by the in-app record button: [Transcriber.AUTO] or one of the preferred languages. */
    fun recordLanguage(context: Context): String =
        prefs(context).getString("record_language", null)
            ?.takeIf { it == Transcriber.AUTO || it in preferredLanguages(context) } ?: Transcriber.AUTO

    fun setRecordLanguage(context: Context, language: String) =
        prefs(context).edit().putString("record_language", language).apply()

    /** The model chosen as default, or null for "the best installed one". */
    fun defaultModel(context: Context): String? = prefs(context).getString("default_model", null)

    fun setDefaultModel(context: Context, id: String?) = prefs(context).edit().putString("default_model", id).apply()

    /** Model downloads are large; they stay on Wi-Fi unless the user allows mobile data. */
    fun allowMobileData(context: Context): Boolean = prefs(context).getBoolean("allow_mobile_data", false)

    fun setAllowMobileData(context: Context, allow: Boolean) = prefs(context).edit().putBoolean("allow_mobile_data", allow).apply()

    /** Record from a connected Bluetooth headset or car microphone instead of the phone's (see [MicrophoneRoute]). */
    fun bluetoothMic(context: Context): Boolean = prefs(context).getBoolean("bluetooth_mic", false)

    fun setBluetoothMic(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("bluetooth_mic", enabled).apply()

    /** Remove a recording this many days after its note was transcribed; 0 = never (the default). See [AudioCleanup]. */
    fun autoRemovalDays(context: Context): Int = prefs(context).getInt("auto_removal_days", 0)

    fun setAutoRemovalDays(context: Context, days: Int) = prefs(context).edit().putInt("auto_removal_days", days).apply()

    /** The first-run explanation has been completed (see [OnboardingScreen]). */
    fun onboarded(context: Context): Boolean = prefs(context).getBoolean("onboarded", false)

    fun setOnboarded(context: Context) = prefs(context).edit().putBoolean("onboarded", true).apply()

    /** [BatteryPolicy]'s last decision, kept between its two thresholds. */
    fun batteryLow(context: Context): Boolean = prefs(context).getBoolean("battery_low", false)

    fun setBatteryLow(context: Context, low: Boolean) = prefs(context).edit().putBoolean("battery_low", low).apply()

    /** The note being transcribed right now, or -1; still set at start-up means the process died during it. */
    fun inflightNote(context: Context): Long = prefs(context).getLong("inflight_note", -1)

    fun setInflightNote(context: Context, id: Long) = prefs(context).edit().putLong("inflight_note", id).commit().let { }

    /** Whether the headset / steering-wheel play-pause button starts and stops notes (see [MediaButtonService]). */
    fun headsetButton(context: Context): Boolean = prefs(context).getBoolean("headset_button", false)

    fun setHeadsetButton(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("headset_button", enabled).apply()

    /** A Markdown file the user picked; notes are appended to it. Stored as a content:// URI string. */
    fun appendTarget(context: Context): String? = prefs(context).getString("append_target", null)

    fun setAppendTarget(context: Context, uri: String?) = prefs(context).edit().putString("append_target", uri).apply()

    /** Append each new note to the target as soon as it has been transcribed. */
    fun autoAppend(context: Context): Boolean = prefs(context).getBoolean("auto_append", false)

    fun setAutoAppend(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean("auto_append", enabled).apply()

    /** Why the last automatic append failed (null if it worked), so the Export screen can say so. */
    fun appendError(context: Context): String? = prefs(context).getString("append_error", null)

    fun setAppendError(context: Context, message: String?) = prefs(context).edit().putString("append_error", message).apply()

    /** The last successful backup: when, how many notes, and whether it was encrypted and held the recordings. Null before the first. */
    class LastBackup(val time: Long, val notes: Int, val encrypted: Boolean, val withAudio: Boolean)

    fun lastBackup(context: Context): LastBackup? = prefs(context).let {
        if (!it.contains("last_backup_time")) null
        else LastBackup(it.getLong("last_backup_time", 0), it.getInt("last_backup_notes", 0), it.getBoolean("last_backup_encrypted", false), it.getBoolean("last_backup_audio", true))
    }

    fun setLastBackup(context: Context, backup: LastBackup) = prefs(context).edit()
        .putLong("last_backup_time", backup.time).putInt("last_backup_notes", backup.notes)
        .putBoolean("last_backup_encrypted", backup.encrypted).putBoolean("last_backup_audio", backup.withAudio).apply()

    /** The note list shows the oldest notes first when this is on; newest first is the default. */
    fun oldestFirst(context: Context): Boolean = prefs(context).getBoolean("notes_oldest_first", false)

    fun setOldestFirst(context: Context, oldestFirst: Boolean) = prefs(context).edit().putBoolean("notes_oldest_first", oldestFirst).apply()

    /** What the backup form starts with (never the passphrase). Encryption is on until the user turns it off. */
    fun backupEncrypt(context: Context): Boolean = prefs(context).getBoolean("backup_encrypt", true)

    fun setBackupEncrypt(context: Context, encrypt: Boolean) = prefs(context).edit().putBoolean("backup_encrypt", encrypt).apply()

    fun backupWithAudio(context: Context): Boolean = prefs(context).getBoolean("backup_with_audio", true)

    fun setBackupWithAudio(context: Context, include: Boolean) = prefs(context).edit().putBoolean("backup_with_audio", include).apply()
}
