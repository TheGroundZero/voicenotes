// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

/** Decisions of the download service that need no Android, so that they can be tested. */
object DownloadPolicy {
    private const val PROGRESS_INTERVAL_MS = 500L

    /**
     * Progress is shown at most every 500 ms, but the last step ([done] reaching [total]) is never dropped: it is what switches the card to
     * "Verifying checksum…", which matters for a model whose checksum takes seconds to compute.
     */
    fun reportProgress(done: Long, total: Long, nowMs: Long, lastReportMs: Long): Boolean =
        done >= total || nowMs - lastReportMs > PROGRESS_INTERVAL_MS

    /**
     * A download was asked for while the service is busy with others. The screen was told "downloading" before the service could say no,
     * so the id to take back is returned (null when the request is for something that is running anyway).
     */
    fun staleStart(requestedId: String?, active: Set<String>): String? = requestedId?.takeIf { it !in active }
}
