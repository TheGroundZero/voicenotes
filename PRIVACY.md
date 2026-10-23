# Privacy

Voice Notes is designed so that your voice and your words stay on your phone.

## What is stored, and where

- **Recordings** (16 kHz mono WAV) and **transcripts** (every version, including your edits), plus which microphone
  recorded each note, are stored in the app's private storage. Other apps cannot read it.
- **Settings** (languages, chosen model, options) are stored the same way.
- Android's cloud backup and device-to-device transfer are **switched off** for this app (`allowBackup="false"`,
  and extraction rules that exclude everything).
- Deleting a note removes its recording and all versions of its text. Uninstalling the app removes everything.
- Recordings are kept until you remove them. If you want, the app deletes a recording a chosen number of days after its note
  was transcribed (*Storage*, off by default, with a confirmation before the first removal); the text stays. You can also
  remove the recording of a single note.

## What leaves the phone

- **Only what you send:** *Share* (text or audio), *Export*, or *Add to file* (a Markdown file you picked). The app
  never sends a note anywhere by itself.
- **Model downloads:** when you tap *Download* on the Models screen, the phone connects to **huggingface.co** (and
  its content-delivery hosts) and downloads one speech-model file. Hugging Face can see the request, including your
  IP address, as for any download; the app sends no account, device identifier or other data and checks the file against a
  checksum built into the app. You can instead copy a model file onto the phone and import it, and then the app does
  not need network access at all.
- No analytics, crash reporting, advertising, tracking or any other third-party service.

## Permissions

| Permission | Why | When |
|---|---|---|
| Microphone | to record your notes | asked on first use |
| Notifications | to show that a note is being recorded or transcribed, with a Stop button | asked on first use, optional |
| Nearby devices (Bluetooth connect) | only to find out which Bluetooth headset or car is connected, so a note can be recorded through its microphone; the app does not scan, pair or send data | asked only if you switch that option on |
| Network | only to download a speech model on request | declared; on Android versions and systems that allow it (for example GrapheneOS) you can switch it off in the app settings and everything except downloads keeps working |
| Foreground service, vibration, network state | to keep recording and transcribing alive, to give a touch confirmation, and to check for Wi-Fi before a large download | normal permissions |

## Android Auto, Bluetooth and the car

When used with Android Auto or a car's Bluetooth, the car or head unit can see that a media app called "Voice Notes"
exists and whether it is recording. Audio from a car microphone, if you choose to use one, goes through the phone's
normal Bluetooth hands-free link and is handled like any other recording.

## Changes

If this changes, the change is listed in [CHANGELOG.md](CHANGELOG.md) and the in-app *Privacy & permissions* screen
is updated with it.
