// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** The single Quick Settings tile. It always auto-detects the language. */
class NoteTileService : TileService() {
    override fun onStartListening() = refresh()

    override fun onClick() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // Cannot record yet: open the app so it can ask for the permission.
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE),
            )
            return
        }
        RecordingService.toggle(this, Transcriber.AUTO)
        // The service flips state asynchronously; show the expected state right away.
        qsTile?.apply {
            state = if (RecordingService.isRecording) Tile.STATE_INACTIVE else Tile.STATE_ACTIVE
            updateTile()
        }
    }

    private fun refresh() {
        qsTile?.apply {
            state = if (RecordingService.isRecording) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
