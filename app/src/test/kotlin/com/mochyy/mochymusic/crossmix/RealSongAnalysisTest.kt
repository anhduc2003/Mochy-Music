package com.mochyy.mochymusic.crossmix

import org.junit.Test
import java.io.File

/**
 * Not an assertion-driven test: this runs the app's own analyser over two real songs and prints what
 * it found, so a mix between them can be judged against the numbers the planner would actually see.
 * Kept out of the normal run unless the song files are present, so CI stays green.
 */
class RealSongAnalysisTest {

    private val songs = File(System.getenv("SONG_DIR") ?: "")

    private fun analyse(name: String): TrackAnalysis? {
        val file = File(songs, name)
        if (!file.exists()) return null
        val bytes = file.readBytes()
        val samples = FloatArray(bytes.size / 4)
        java.nio.ByteBuffer.wrap(bytes)
            .order(java.nio.ByteOrder.LITTLE_ENDIAN)
            .asFloatBuffer()
            .get(samples)
        val analyzer = StreamingTrackAnalyzer()
        var offset = 0
        val chunk = FloatArray(4_096)
        while (offset < samples.size) {
            val count = minOf(chunk.size, samples.size - offset)
            System.arraycopy(samples, offset, chunk, 0, count)
            analyzer.feed(chunk, count)
            offset += count
        }
        return analyzer.finish(complete = true)
    }

    @Test
    fun `analyse the two provided songs`() {
        if (!songs.isDirectory) {
            println("RealSongAnalysisTest: no SONG_DIR, skipped")
            return
        }
        for (name in listOf("vet-thuong.pcm", "gio-cuon.pcm")) {
            val a = analyse(name)
            if (a == null) {
                println("RealSongAnalysisTest: $name missing, skipped")
                continue
            }
            println(
                "REAL $name -> bpm=${a.bpm} conf=${a.bpmConfidence} beats=${a.beatsMs.size} " +
                    "key=${a.key} keyConf=${a.keyConfidence} bars=${a.barBeats.size} " +
                    "analyzedMs=${a.analyzedMs} reliable=${a.isBeatReliable} " +
                    "tailSilence=${a.trailingSilenceStartMs} leadSilence=${a.leadingSilenceEndMs}"
            )
        }

        val out = analyse("vet-thuong.pcm")
        val inc = analyse("gio-cuon.pcm")
        if (out != null && inc != null) {
            val plan = CrossmixPlanner.plan(
                CrossmixTrackInput("out", 257_680L, out, null),
                CrossmixTrackInput("inc", 290_730L, inc, null),
            )
            println(
                "REAL PLAN style=${plan.style} length=${plan.lengthBeats} beats " +
                    "(${plan.lengthBeats * plan.outBeatMs / 1_000.0}s) beatMatched=${plan.beatMatched} " +
                    "inSpeed=${plan.inSpeed} outSpeed=${plan.outSpeed} " +
                    "outSemitones=${plan.outSemitones} outStartMs=${plan.outStartMs}"
            )
            val dist = Harmony.camelotDistance(out.key, inc.key)
            println("REAL keyDistance=$dist shift=${Harmony.shiftSemitones(out.key, inc.key, 2)}")
        }
    }
}
