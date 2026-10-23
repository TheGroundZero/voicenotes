// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

import android.Manifest
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.RemoteViews

/** A one-tap record/stop button for the home screen. Another trigger: it always auto-detects the language. */
class RecordWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) = update(context)

    companion object {
        /** Redraws every placed widget from the current recording state. */
        fun update(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, RecordWidget::class.java))
            if (ids.isEmpty()) return
            val recording = RecordingService.isRecording
            val views = RemoteViews(context.packageName, R.layout.widget_record).apply {
                setTextViewText(R.id.widget_glyph, if (recording) "■" else "●")
                setInt(R.id.widget_root, "setBackgroundResource", if (recording) R.drawable.widget_bg_recording else R.drawable.widget_bg_idle)
                setContentDescription(R.id.widget_root, context.getString(if (recording) R.string.widget_stop else R.string.widget_record))
                setOnClickPendingIntent(R.id.widget_root, clickIntent(context))
            }
            manager.updateAppWidget(ids, views)
        }

        private fun clickIntent(context: Context): PendingIntent {
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            // Without the microphone permission the service cannot record: open the app so it can ask.
            if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                return PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), flags)
            }
            return PendingIntent.getForegroundService(
                context, 0,
                Intent(context, RecordingService::class.java).setAction(RecordingService.ACTION_TOGGLE).putExtra(RecordingService.EXTRA_LANG, Transcriber.AUTO),
                flags,
            )
        }
    }
}
