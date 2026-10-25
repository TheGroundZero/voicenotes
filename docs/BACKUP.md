<!--
SPDX-FileCopyrightText: 2026 The Voice Notes authors (sequr.be)
SPDX-License-Identifier: GPL-3.0-or-later
-->

# Backup format

*Menu → Backup & restore.* A backup is one file the user saves with Android's file picker (Nextcloud and other providers
appear there); the app itself never touches the network for this. Format version 1.

## Layout

An **unencrypted** backup is a ZIP file (it starts with `PK`). An **encrypted** one wraps the same ZIP in an
authenticated stream (it starts with `VNBK`). Entries, in this order:

| Entry | Content |
|---|---|
| `manifest.json` | `format` (`voicenotes-backup`), `formatVersion`, `appVersion`, `appVersionCode`, `createdAt` (ms), `noteCount`, `audioCount`, `models` (ids used by the transcripts), `settings` (`preferredLanguages`, `recordLanguage`, `defaultModel`) |
| `notes.json` | `{"notes": [...]}`; each note has `createdAt`, `audioFile`, `language`, `status`, `micSource`, `audioDeleted`, `audio` (`size`, `sha256`, or `null`) and all its `transcripts` (`text`, `model`, `language`, `detected`, `source`, `createdAt`, `isCurrent`) |
| `audio/<name>.wav` | the recordings (16 kHz mono WAV), one per note that has one; `<name>` matches `[A-Za-z0-9._-]{1,100}\.wav` and does not start with a dot |

Notes are stored as data, not as a copy of the database, so a restore can merge into existing notes and does not depend on
the database version. Not included: speech models (re-download them under Models), the export-file target and other
settings that belong to this particular phone. Notes still being recorded are skipped; notes that were queued or
half transcribed come back as *waiting*.

## Encryption

`"VNBK"`, version byte (1), key-derivation id (1 = PBKDF2-HMAC-SHA256), iterations (4 bytes, big endian, 600,000 by
default; readers refuse values outside 1,000 to 10,000,000), salt (16 random bytes), nonce prefix (8 random bytes).
Then blocks of `[length (4 bytes)][AES-256-GCM ciphertext + 16-byte tag]`, each holding up to 64 KiB of the ZIP. The
nonce of a block is the nonce prefix followed by the block number (4 bytes); the authenticated data is the whole header plus
one byte that is 1 for the last block. A wrong passphrase fails the first block; a changed, reordered, swapped or cut-short
file fails a later block, because the end of the stream is itself authenticated. The last block always exists, possibly
empty. There is no recovery path for a lost passphrase, and the format has no back door.

## Restore rules

- The file is read and checked completely (structure, file names, every recording's size and SHA-256) before anything on the phone changes.
- Any entry that is not `manifest.json`, `notes.json` or a declared `audio/*.wav` makes the backup unusable (no path tricks).
- A backup with a newer `formatVersion` is refused with a message to update the app.
- **Add to my notes** (default): a note whose creation time and file name already exist is left alone; a file name that is
  taken by a different note gets a `-restoredN` suffix. **Replace everything** adds the backup's notes and then removes
  the old ones in the same database transaction; it is refused while a note is being recorded or transcribed.
- The recordings are copied into place first, then all notes go into the database in one transaction. If anything fails,
  the copied recordings are removed again and the existing notes are untouched.
- A note whose recording is not in the backup is marked as having no recording; if it was waiting to be transcribed it
  becomes *failed*, because it cannot be transcribed.
- Language settings (preferred languages, the recording-button language, and the default model if it is installed here)
  are restored only if the user leaves that option on.

## Tests

`BackupCryptoTest`, `BackupArchiveTest` and `BackupPlanTest` cover round trips at block boundaries, wrong passphrases,
every kind of damage (flipped bytes, truncation, reordered blocks, unreasonable iteration counts), path tricks, missing,
extra, oversized and corrupt recordings, newer formats and the merge rules.
