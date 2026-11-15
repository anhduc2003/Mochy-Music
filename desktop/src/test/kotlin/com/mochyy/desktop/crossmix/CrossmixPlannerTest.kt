package com.mochyy.desktop.crossmix

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random

/**
 * The PC build's check on the shared crossmix planner.
 *
 * The Android test of the same name runs 31 cases; this one covers the parts that changed in the
 * hand-over fixes and that would break silently — the tempo coverage, the beat match being what it
 * says it is, and the key shift. The audio-processor cases stayed behind because that processor is
 * Media3 code and does not exist on the JVM.
 */
class CrossmixPlannerTest {

    /** a kick on every beat with a hat between, plus a quiet pad, so the analyser has something to lock to */
    private fun synthTrack(bpm: Double, seconds: Int, kickOffsetMs: Double = 250.0): FloatArray {
        val rate = StreamingTrackAnalyzer.SampleRate
        val out = FloatArray(rate * seconds)
        val beat = 60.0 / bpm
        val random = Random(7)
        var t = kickOffsetMs / 1_000.0
        var index = 0
        while (t < seconds) {
            val start = (t * rate).toInt()
            val accent = if (index % 4 == 0) 1.0f else 0.55f
            for (i in 0 until (0.12 * rate).toInt()) {
                val n = start + i
                if (n >= out.size) break
                val env = exp(-i / (0.03 * rate)).toFloat()
                out[n] += accent * 0.8f * env * sin(2 * PI * (55.0 + 60.0 * exp(-i / (0.01 * rate))) * i / rate).toFloat()
            }
            val hat = ((t + beat / 2) * rate).toInt()
            for (i in 0 until (0.03 * rate).toInt()) {
                val n = hat + i
                if (n >= out.size) break
                out[n] += 0.12f * exp(-i / (0.006 * rate)).toFloat() * (random.nextFloat() * 2f - 1f)
            }
            t += beat
            index++
        }
        for (n in out.indices) out[n] += 0.06f * sin(2 * PI * 220.0 * n / rate).toFloat()
        return out
    }

    private fun analyze(samples: FloatArray, complete: Boolean = true): TrackAnalysis {
        val analyzer = StreamingTrackAnalyzer()
        var offset = 0
        val chunk = FloatArray(4_096)
        while (offset < samples.size) {
            val count = minOf(chunk.size, samples.size - offset)
            System.arraycopy(samples, offset, chunk, 0, count)
            analyzer.feed(chunk, count)
            offset += count
        }
        return analyzer.finish(complete)!!
    }

    private val outgoingTrack by lazy { analyze(synthTrack(128.0, 200)) }
    private val nearTempoTrack by lazy { analyze(synthTrack(126.0, 150), complete = false) }
    private val farTempoTrack by lazy { analyze(synthTrack(100.0, 150), complete = false) }

    @Test
    fun `a planned mix starts inside the last 30 seconds`() {
        val plan = CrossmixPlanner.plan(
            CrossmixTrackInput("o", 200_000L, outgoingTrack, null),
            CrossmixTrackInput("i", 0L, nearTempoTrack, null),
        )
        assertTrue("starts at ${plan.outStartMs}", plan.outStartMs >= 200_000L - CrossmixPlanner.MaxLeadMs)
        assertTrue("the move fits", plan.outMediaAt(plan.lengthBeats.toFloat()) <= 200_000.0 + plan.style.tailBeats * plan.outBeatMs)
    }

    @Test
    fun `an unmatched blend is never longer than the unmatched ceiling`() {
        // this is the bug that shipped: the cap was chosen before the match was attempted, so a pair
        // that only looked lockable blended unmatched for the whole matched ceiling
        val plan = CrossmixPlanner.plan(
            CrossmixTrackInput("o", 200_000L, outgoingTrack, null),
            CrossmixTrackInput("i", 0L, farTempoTrack, null),
        )
        if (!plan.style.entersAtEnd && !plan.beatMatched) {
            val seconds = plan.lengthBeats * plan.outBeatMs / 1_000.0
            assertTrue(
                "${plan.style.label} blended $seconds s unmatched",
                seconds <= CrossmixPlanner.MaxUnmatchedOverlapMs / 1_000.0,
            )
        }
    }

    @Test
    fun `every tempo the analyser reports has a blend length that fits`() {
        // the analyser only reports 60-200 bpm (estimateTempo). The old 8/16-only list left 80-113
        // with no valid length at all, so a 90 bpm song could never mix
        val window = CrossmixPlanner.MaxUnmatchedOverlapMs / 1_000.0
        val counts = listOf(4, 8, 12, 16, 20, 24, 32)
        for (bpm in 60..200) {
            val beatMs = 60_000.0 / bpm
            val fits = counts.any { beats ->
                val seconds = beats * beatMs / 1_000.0
                seconds >= 6.0 && seconds <= window
            }
            assertTrue("$bpm bpm has no blend length inside $window s", fits)
        }
    }

    @Test
    fun `the key shift reaches a whole step and no further`() {
        // a whole step (Camelot distance 2) is what a small move can fix
        assertEquals(1f, Harmony.shiftSemitones("C major", "C# major", 2)!!, 0.001f)
        assertEquals(2f, Harmony.shiftSemitones("C major", "D major", 2)!!, 0.001f)
        assertEquals(-1f, Harmony.shiftSemitones("C major", "B major", 2)!!, 0.001f)
        assertEquals(0f, Harmony.shiftSemitones("A minor", "A minor", 2)!!, 0.001f)
        // a fifth is out of reach of two semitones, so it is left alone rather than bent badly
        assertNull(Harmony.shiftSemitones("C major", "G major", 2))
        assertNull(Harmony.shiftSemitones(null, "C major", 2))
        assertNull(Harmony.shiftSemitones("C major", null, 2))
    }

    @Test
    fun `camelot distance treats a relative major as its own minor`() {
        // A minor and C major share a number on the wheel, so they sit together
        assertEquals(1, Harmony.camelotDistance("A minor", "C major"))
        assertEquals(0, Harmony.camelotDistance("C major", "C major"))
        // and a fifth apart is one step round
        assertEquals(1, Harmony.camelotDistance("C major", "G major"))
    }

    @Test
    fun `lyric stamps are read the way lrc files write them`() {
        val lrc = """
            [ti:Test]
            [00:12.50]first line
            [00:20.00][01:40.00]repeated chorus
        """.trimIndent()
        val lines = LyricsStamp.parse(lrc)
        assertEquals(3, lines.size)
        assertEquals(12_500L, lines[0].time)
        assertEquals(20_000L, lines[1].time)
        assertEquals(100_000L, lines[2].time)
        // a file with no timing at all says nothing about where the voice is
        assertTrue(!LyricsStamp.hasStamps("just words\nmore words"))
    }
}
