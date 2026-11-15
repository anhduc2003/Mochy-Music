package com.mochyy.mochymusic.crossmix

import com.mochyy.mochymusic.constants.CrossmixLength
import com.mochyy.mochymusic.constants.CrossmixTempoMatch

/** the listener's choices for how crossmix mixes, read by the planner; the defaults are the planner left to itself */
data class CrossmixSettings(
    val length: CrossmixLength = CrossmixLength.MEDIUM,
    val tempoMatch: CrossmixTempoMatch = CrossmixTempoMatch.FULL,
    /** one style for every mix, or null for the one that fits the pair */
    val style: CrossmixStyle? = null,
    /** whether backspins, tape stops, risers and the like may be picked when the energy jumps */
    val stunts: Boolean = true,
    /**
     * every hand-over a real blend of the two songs: no drop right at the end, no plain fade, even without
     * a beat to lock to. Off by default: forcing a mix between two songs that share neither tempo nor key is
     * what made hand-overs sound wrong, so the planner is left free to fall back to a clean fade when a real
     * blend wouldn't work. On is there for anyone who would rather always hear both songs meet.
     */
    val alwaysMix: Boolean = false,
) {
    companion object {
        val Default = CrossmixSettings()
    }
}
