// SPDX-License-Identifier: GPL-3.0-or-later
package be.sequr.voicenotes

/**
 * The models the app knows how to download. Shipped inside the app on purpose: a catalog fetched
 * from a server would be an unauthenticated trust anchor. Every file is pinned to a Hugging Face
 * commit and verified against the SHA-256 below before it is used, so a newer model arrives with
 * an app update. (A signed catalog is the way to avoid that.)
 */
data class ModelSpec(
    /** Used in the file name `ggml-<id>.bin`. */
    val id: String,
    val title: String,
    val description: String,
    val sizeBytes: Long,
    val sha256: String,
    val url: String,
) {
    val fileName get() = "ggml-$id.bin"
}

object ModelCatalog {
    private const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    private fun url(file: String) = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$REVISION/$file"

    /** Best first: this order decides which installed model is used when none is chosen. */
    val models = listOf(
        ModelSpec(
            id = "large-v3-turbo-q8_0",
            title = "Large v3 Turbo",
            description = "Most accurate, especially for Dutch and French. Transcribes at roughly real time on a Pixel 10.",
            sizeBytes = 874_188_075,
            sha256 = "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1",
            url = url("ggml-large-v3-turbo-q8_0.bin"),
        ),
        ModelSpec(
            id = "small",
            title = "Small",
            description = "Smaller and faster, but makes more mistakes, particularly in noisy recordings.",
            sizeBytes = 487_601_967,
            sha256 = "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b",
            url = url("ggml-small.bin"),
        ),
    )

    fun byId(id: String) = models.firstOrNull { it.id == id }
}
