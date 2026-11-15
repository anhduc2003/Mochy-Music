package com.mochyy.desktop

import com.mochyy.desktop.audio.DesktopPlayer
import com.mochyy.desktop.model.Track
import org.junit.Test
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Headless checks for the PC build's two load-bearing pieces: reading a file's tags, and the
 * player's contract (position advances, pause keeps its place, end-of-track fires once).
 *
 * The mixer is not asserted on where a machine has no output device — CI has none — so the
 * playback test only runs where one exists.
 */
class DesktopPlayerTest {

    private val wav = File(System.getenv("WAV_PATH") ?: "")

    @Test
    fun `splits an untagged file name into artist and title`() {
        assertEquals("Sơn Tùng M-TP" to "Chạy Ngay Đi", TrackReader.splitFileName("Sơn Tùng M-TP - Chạy Ngay Đi"))
        // a title that itself contains " - " keeps the first separator as the split
        assertEquals("A" to "B - C", TrackReader.splitFileName("A - B - C"))
        // no separator: the whole name is the title, and no artist is invented
        assertEquals("" to "NoSeparator", TrackReader.splitFileName("NoSeparator"))
        // a leading separator must not produce an empty artist
        assertEquals("" to "- Odd Name", TrackReader.splitFileName("- Odd Name"))
    }

    @Test
    fun `reads a real wav file's length and defaults`() {
        if (!wav.isFile) {
            println("DesktopPlayerTest: no WAV_PATH, skipped playback checks")
            return
        }
        val track = assertNotNull(TrackReader.read(wav), "should read ${wav.name}")
        assertEquals(wav.absolutePath, track.id)
        // the tone is 3 s; allow the encoder a frame either way
        assertTrue(track.durationMs in 2_900..3_100, "duration was ${track.durationMs}")
        assertEquals("", track.artist, "an untagged file claims no artist")
    }

    @Test
    fun `the player reports a clean error for a missing file`() {
        if (!wav.isFile) return
        val player = DesktopPlayer()
        try {
            player.play(Track("x", "Gone", "", uri = File(wav.parentFile, "does-not-exist.wav").absolutePath))
            // the failure is asynchronous; give the open a moment to report
            val deadline = System.currentTimeMillis() + 3_000
            while (player.state.value.error == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertNotNull(player.state.value.error, "a missing file should surface an error, not silence")
            assertTrue(!player.state.value.playing, "nothing should be playing")
        } finally {
            player.dispose()
        }
    }

    @Test
    fun `plays a wav, advances, pauses in place and ends once`() {
        if (!wav.isFile) return
        // no output device on CI: skip rather than fail the build
        val canOutput = runCatching {
            AudioSystem.getSourceDataLine(AudioSystem.getAudioInputStream(wav).format).close()
        }.isSuccess
        if (!canOutput) {
            println("DesktopPlayerTest: no audio output device, skipped playback")
            return
        }

        val player = DesktopPlayer()
        try {
            var ended = 0
            player.onEnded { ended++ }
            player.play(Track("t", "Tone", "", uri = wav.absolutePath))

            val deadline = System.currentTimeMillis() + 5_000
            while (player.state.value.positionMs < 600 && System.currentTimeMillis() < deadline) Thread.sleep(100)
            assertTrue(player.state.value.positionMs >= 600, "position should advance, was ${player.state.value.positionMs}")

            player.pause()
            val atPause = player.state.value.positionMs
            Thread.sleep(600)
            // paused means paused: position must not creep while the line is stopped
            assertTrue(
                player.state.value.positionMs - atPause < 300,
                "position moved while paused: $atPause -> ${player.state.value.positionMs}",
            )

            player.resume()
            val endDeadline = System.currentTimeMillis() + 8_000
            while (ended == 0 && System.currentTimeMillis() < endDeadline) Thread.sleep(100)
            assertEquals(1, ended, "end-of-track should fire exactly once")
        } finally {
            player.dispose()
        }
    }
}
