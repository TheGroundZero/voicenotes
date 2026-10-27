# Privacy

Voice Notes is designed so that your voice and your words stay on your phone.

## What is stored, and where

- **Recordings** (16 kHz mono WAV) and **transcripts** (every version, including your edits), plus which microphone
  recorded each note, are stored in the app's private storage. Other apps cannot read it.
- **Labels** you give your notes, and **settings** (languages, chosen model, options), are stored the same way.
- Android's cloud backup and device-to-device transfer are **switched off** for this app (`allowBackup="false"`,
  and extraction rules that exclude everything).
- Deleting a note removes its recording and all versions of its text. Uninstalling the app removes everything.
- Recordings are kept until you remove them. If you want, the app deletes a recording a chosen number of days after its note
  was transcribed (*Storage*, off by default, with a confirmation before the first removal); the text stays. You can also
  remove the recording of a single note.

## What leaves the phone

- **Only what you send:** *Share* (text or audio), *Export*, *Add to file* (a Markdown file you picked; the Markdown lists
  a note's labels as `#tags`), or a *Backup* you save. The app never sends a note anywhere by itself.
- **Backups:** *Backup & restore* writes one file to a location you pick with Android's file picker. The app that
  provides the location (for example Nextcloud) does any uploading, not Voice Notes. A backup holds every note, all
  versions of its text, the labels and, if you leave it on, every recording. **Switch on the passphrase** (it is on by default):
  the file is then encrypted (AES-256-GCM, key from your passphrase with PBKDF2-HMAC-SHA256, 600,000 rounds), and nobody,
  including the developer, can open it without the passphrase, which cannot be recovered. Without a passphrase, anyone
  who can read the file can hear and read everything in it. Restoring reads the file you pick and changes nothing
  until you confirm. See [docs/BACKUP.md](docs/BACKUP.md) for the format.
- **Model downloads:** when you tap *Download* on the Models screen, the phone connects to **huggingface.co** (and
  its content-delivery hosts) and downloads one speech-model file, followed by the small speech-check model (885 KB, Silero VAD) that skips
  notes without speech, unless that is on the phone already. Hugging Face can see the requests, including your
  IP address, as for any download; the app sends no account, device identifier or other data and checks each file against a
  checksum built into the app. You can instead copy the model files onto the phone and import them, and then the app does
  not need network access at all.
- No analytics, crash reporting, advertising, tracking or any other third-party service.

## Permissions

| Permission | Why | When |
|---|---|---|
| Microphone | to record your notes | asked on first use |
| Notifications | to show that a note is being recorded or transcribed, with a Stop button | asked on first use, optional |
| Nearby devices (Bluetooth connect) | only to find out which Bluetooth headset or car is connected, so a note can be recorded through its microphone; the app does not scan, pair or send data | asked only if you switch that option on |
| Network | only to download a speech model (and the small speech-check model) on request | declared; on Android versions and systems that allow it (for example GrapheneOS) you can switch it off in the app settings and everything except downloads keeps working |
| Foreground service, vibration, network state | to keep recording and transcribing alive, to give a touch confirmation, and to check for Wi-Fi before a large download | normal permissions |

## Android Auto, Bluetooth and the car

When used with Android Auto or a car's Bluetooth, the car or head unit can see that a media app called "Voice Notes"
exists and whether it is recording. Audio from a car microphone, if you choose to use one, goes through the phone's
normal Bluetooth hands-free link and is handled like any other recording.

## Changes

If this changes, the change is listed in [CHANGELOG.md](CHANGELOG.md) and the in-app *Privacy & permissions* screen
is updated with it.
