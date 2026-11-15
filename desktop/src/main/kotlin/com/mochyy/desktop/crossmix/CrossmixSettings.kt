package com.mochyy.desktop.crossmix

/**
 * The listener's mixing choices, mirroring the Android build's.
 *
 * Copied rather than shared because :app's copies live in a PreferenceKeys.kt that also holds a
 * hundred android DataStore keys; only what the planner reads is needed to plan a mix.
 */

/** how long a crossmix overlap may run: scales the planner's overlap caps */
enum class CrossmixLength(val scale: Float) {
    SHORT(0.6f),
    MEDIUM(1f),
    LONG(1.5f),
}

/** how far the slower song may be sped up to meet the other; off still locks two songs already at one tempo */
enum class CrossmixTempoMatch(val maxSpeedUp: Float) {
    OFF(0.006f),
    GENTLE(0.04f),
    FULL(0.10f),
}

/**
 * The listener's choices for how crossmix mixes, read by the planner.
 *
 * Kept identical in shape and defaults to the Android build's so a mix planned on the PC means the
 * same thing as one planned on the phone, and so the shared planner source needs no branch.
 */
data class CrossmixSettings(
    val length: CrossmixLength = CrossmixLength.MEDIUM,
    val tempoMatch: CrossmixTempoMatch = CrossmixTempoMatch.FULL,
    /** one style for every mix, or null for the one that fits the pair */
    val style: CrossmixStyle? = null,
    /** whether backspins, tape stops, risers and the like may be picked when the energy jumps */
    val stunts: Boolean = true,
    /**
     * every hand-over a real blend of the two songs: no drop right at the end, no plain fade, even
     * without a beat to lock to. Off by default - forcing a blend between two songs that share
     * neither tempo nor key is what made hand-overs sound wrong.
     */
    val alwaysMix: Boolean = false,
) {
    companion object {
        val Default = CrossmixSettings()
    }
}