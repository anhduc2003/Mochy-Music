package com.mochyy.desktop.audio

import com.mochyy.desktop.model.RepeatMode
import com.mochyy.desktop.model.Track
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioInputStream
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Playback on the PC, on the JDK's own sound output.
 *
 * The Android build hands this to ExoPlayer, which brings decoding for every format, a network
 * stack, gapless playback and a sample-accurate clock. The JDK has `javax.sound.sampled`, which
 * decodes only what a `ServiceProvider` is installed for — in practice WAV, AIFF and AU — and
 * nothing else. So the format gap is real and is not papered over here: an MP3 or FLAC either
 * gets a provider registered, or the file plays as a clear error rather than as silence.
 *
 * What matters for the rest of the app is the contract: a position that advances in real time,
 * pause/resume that keeps its place, seek that lands where you asked, and a track-ended callback
 * that fires once. Everything above this file is written against that contract, so swapping in a
 * different engine later (VLCJ, JavaCV, an external mpv) does not reach the UI.
 */
class DesktopPlayer {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow(com.mochyy.desktop.model.PlayerState())
    val state: StateFlow<com.mochyy.desktop.model.PlayerState> = _state.asStateFlow()

    private var line: SourceDataLine? = null
    private var stream: AudioInputStream? = null
    private var playbackJob: Job? = null

    /** frames per second of the open stream, and bytes per frame, for position maths */
    private var framesPerSecond = 0f
    private var bytesPerFrame = 0
    private var dataStartFrame = 0L

    /** what to play when the current track ends */
    private var queue: List<Track> = emptyList()
    private var queueIndex = -1
    private var onTrackEnded: (() -> Unit)? = null

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        queue = tracks
        queueIndex = startIndex.coerceIn(0, (tracks.size - 1).coerceAtLeast(0))
    }

    fun onEnded(callback: () -> Unit) {
        onTrackEnded = callback
    }

    /**
     * opens and starts [track]. A failure is reported in [state] rather than thrown, because the
     * UI's job is to show it, not to crash the window.
     */
    fun play(track: Track, startAtMs: Long = 0L) {
        stop()
        _state.update { it.copy(track = track, error = null, positionMs = startAtMs, playing = true) }
        playbackJob = scope.launch { run(track, startAtMs) }
    }

    private suspend fun run(track: Track, startAtMs: Long) {
        try {
            val opened = withContext(Dispatchers.IO) { open(track.uri) }
            if (opened == null) {
                _state.update { it.copy(playing = false, error = "Unsupported audio format: ${track.uri}") }
                return
            }
            val (input, format) = opened
            stream = input
            framesPerSecond = format.frameRate
            bytesPerFrame = format.frameSize
            val totalFrames = withContext(Dispatchers.IO) { input.frameLength }
            val durationMs = if (framesPerSecond > 0f) (totalFrames / framesPerSecond * 1000f).toLong() else 0L

            val out = withContext(Dispatchers.IO) { AudioSystem.getSourceDataLine(format).also { it.open(format) } }
            line = out

            // seek before the line starts, so the first thing heard is the seek target
            val skipBytes = (startAtMs / 1000f * framesPerSecond * bytesPerFrame).toLong()
            if (skipBytes > 0) withContext(Dispatchers.IO) { input.skip(skipBytes) }
            dataStartFrame = (startAtMs / 1000f * framesPerSecond).toLong()

            _state.update { it.copy(durationMs = durationMs, positionMs = startAtMs) }
            withContext(Dispatchers.IO) { out.start() }

            val buffer = ByteArray(bufferSizeFor(format))
            var written = 0L
            while (scope.isActive && _state.value.playing) {
                val read = withContext(Dispatchers.IO) { input.read(buffer, 0, buffer.size) }
                if (read <= 0) break
                withContext(Dispatchers.IO) { out.write(buffer, 0, read) }
                written += read
                val frames = dataStartFrame + written / bytesPerFrame
                val posMs = (frames / framesPerSecond * 1000f).toLong()
                _state.update { it.copy(positionMs = posMs) }
            }

            // drained: let the last buffer reach the speakers before calling it over
            withContext(Dispatchers.IO) {
                runCatching {
                    out.drain()
                }
            }
            val endedNaturally = _state.value.playing && written > 0
            closeLine()
            _state.update { it.copy(playing = false) }
            if (endedNaturally) onTrackEnded?.invoke()
        } catch (t: Throwable) {
            _state.update { it.copy(playing = false, error = t.message ?: t::class.simpleName) }
            closeLine()
        }
    }

    private fun open(uri: String): Pair<AudioInputStream, AudioFormat>? {
        val source: AudioInputStream = when {
            uri.startsWith("http://") || uri.startsWith("https://") -> {
                val url = java.net.URI(uri).toURL()
                val connection = url.openConnection().apply {
                    connectTimeout = 15_000
                    readTimeout = 30_000
                }
                AudioSystem.getAudioInputStream(BufferedInputStream(connection.getInputStream()))
            }
            else -> {
                val file = File(uri)
                if (!file.isFile) return null
                AudioSystem.getAudioInputStream(BufferedInputStream(file.inputStream()))
            }
        }
        // a conversion to PCM here is what lets a provider hand over a float or 24-bit stream
        val pcm = toPcm(source) ?: return null
        return pcm to pcm.format
    }

    /** anything the mixer can take as signed PCM at a sane rate; null when it can't be converted */
    private fun toPcm(input: AudioInputStream): AudioInputStream? {
        val format = input.format
        val target = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            format.sampleRate.takeIf { it in 8_000f..192_000f } ?: 44_100f,
            16,
            format.channels.coerceIn(1, 2),
            (format.channels.coerceIn(1, 2) * 2),
            format.sampleRate,
            false,
        )
        if (AudioSystem.isConversionSupported(target, format)) return AudioSystem.getAudioInputStream(target, input)
        // already PCM and only the layout differs: hand it over untouched and let the mixer decide
        return if (format.encoding == AudioFormat.Encoding.PCM_SIGNED) input else null
    }

    private fun bufferSizeFor(format: AudioFormat): Int {
        // about 50 ms of audio, so seek and pause land without a noticeable lag
        val frames = (format.frameRate * 0.05f).roundToInt().coerceAtLeast(1_024)
        return frames * format.frameSize
    }

    fun pause() {
        if (!_state.value.playing) return
        _state.update { it.copy(playing = false) }
        runCatching { line?.stop() }
    }

    /** resumes from where [pause] left off: the loop reopens at the paused position */
    fun resume() {
        val track = _state.value.track ?: return
        val at = _state.value.positionMs
        play(track, at)
    }

    fun toggle() {
        if (_state.value.playing) pause() else resume()
    }

    fun seekTo(ms: Long) {
        val track = _state.value.track ?: return
        val target = ms.coerceIn(0L, _state.value.durationMs.takeIf { it > 0 } ?: ms)
        val wasPlaying = _state.value.playing
        play(track, target)
        if (!wasPlaying) pause()
    }

    fun stop() {
        playbackJob?.cancel()
        playbackJob = null
        closeLine()
        _state.update { it.copy(playing = false) }
    }

    private fun closeLine() {
        runCatching { line?.stop() }
        runCatching { line?.close() }
        runCatching { stream?.close() }
        line = null
        stream = null
    }

    fun setVolume(v: Float) {
        val volume = v.coerceIn(0f, 1f)
        _state.update { it.copy(volume = volume) }
        runCatching {
            val target = line ?: return@runCatching
            if (!target.isControlSupported(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)) return@runCatching
            val db = if (volume <= 0.0001f) -80f else (20.0 * kotlin.math.log10(volume.toDouble())).toFloat()
            val control = target.getControl(javax.sound.sampled.FloatControl.Type.MASTER_GAIN)
                as javax.sound.sampled.FloatControl
            control.value = db.coerceIn(control.minimum, control.maximum)
        }
    }

    fun setShuffle(on: Boolean) = _state.update { it.copy(shuffle = on) }

    fun setRepeat(mode: RepeatMode) = _state.update { it.copy(repeat = mode) }

    /** the track after the current one, honouring repeat and shuffle */
    fun next(): Track? {
        if (queue.isEmpty()) return null
        val s = _state.value
        return when {
            s.repeat == RepeatMode.ONE -> s.track
            s.shuffle -> queue.random()
            queueIndex + 1 < queue.size -> queue[queueIndex + 1]
            s.repeat == RepeatMode.ALL -> queue.first()
            else -> null
        }
    }

    fun previous(): Track? {
        if (queue.isEmpty()) return null
        // a restart of this one is friendlier than a jump back when it has only just started
        if (_state.value.positionMs > 3_000) return _state.value.track
        return when {
            s0().repeat == RepeatMode.ONE -> _state.value.track
            s0().shuffle -> queue.random()
            queueIndex > 0 -> queue[queueIndex - 1]
            else -> queue.firstOrNull()
        }
    }

    private fun s0() = _state.value

    fun playNext() {
        val next = next() ?: return
        val index = queue.indexOfFirst { it.id == next.id }
        if (index >= 0) queueIndex = index
        play(next)
    }

    fun playPrevious() {
        val previous = previous() ?: return
        val index = queue.indexOfFirst { it.id == previous.id }
        if (index >= 0) queueIndex = index
        play(previous)
    }

    fun dispose() {
        stop()
        scope.cancel()
    }
}
