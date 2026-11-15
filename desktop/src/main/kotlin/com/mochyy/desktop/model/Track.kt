package com.mochyy.desktop.model

/**
 * A song on the PC build.
 *
 * Deliberately not shared with `:app`'s `Song`: that one is a Room entity with an id, a YouTube
 * videoId, download state and a dozen layout-driven flags. This is what the PC player needs to
 * draw a row and start playback, and nothing else.
 */
data class Track(
    val id: String,
    val title: String,
    val artist: String,
    val album: String? = null,
    val durationMs: Long = 0L,
    /** where the audio comes from: a local file path, or a stream URL resolved by a provider */
    val uri: String,
    /** seconds, if the source can give us one cheaply; the UI falls back to the decoded length */
    val thumbnailUrl: String? = null,
)

/** how the player moves between tracks */
enum class RepeatMode { OFF, ALL, ONE }

/** what the queue is doing right now */
data class PlayerState(
    val track: Track? = null,
    val playing: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val volume: Float = 0.8f,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val speed: Float = 1f,
    val error: String? = null,
) {
    val progress: Float
        get() = if (durationMs <= 0L) 0f else (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
}
