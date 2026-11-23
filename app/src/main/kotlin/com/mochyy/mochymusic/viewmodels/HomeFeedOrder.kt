// HomeFeedOrder.kt

package com.mochyy.mochymusic.viewmodels

import com.music.innertube.models.SongItem
import com.music.innertube.models.YTItem
import com.music.innertube.pages.HomePage

/**
 * The shelves in the order Home shows them when the app is running on Vietnam's feed: the ones
 * showing Vietnamese music first, and a fresh order within each group every time.
 *
 * YouTube answers the same account in the same region with the same page, so a Home built straight
 * from that answer looks frozen from one launch to the next. Shuffling here is what makes opening
 * the app feel like the real YouTube again, and the Vietnamese-first split is what keeps the
 * Vietnamese shelves from sitting under a wall of international ones.
 */
internal fun orderHomeSections(sections: List<HomePage.Section>): List<HomePage.Section> {
    if (sections.size < 2) return sections
    val (vietnamese, rest) = sections.partition { it.readsVietnamese() }
    return vietnamese.shuffled() + rest.shuffled()
}

/** a shelf counts as Vietnamese when at least half of what it shows is */
private fun HomePage.Section.readsVietnamese(): Boolean {
    if (items.isEmpty()) return false
    return items.count { it.readsVietnamese() } * 2 >= items.size
}

private fun YTItem.readsVietnamese(): Boolean =
    looksVietnamese(title) || (this as? SongItem)?.artists?.any { looksVietnamese(it.name) } == true

/**
 * The letters only Vietnamese writes: đ, ơ, ư, ă and the vowels carrying a dot below or a hook
 * above. Plain Latin titles with no diacritics at all don't count — an English song and a
 * Vietnamese one typed without accents read the same, so they're left where YouTube put them.
 */
private val VietnameseLetters =
    Regex("[đĐơƠưƯăĂạảấầẩẫậắằẳẵặẹẻẽếềểễệỉịọỏốồổỗộớờởỡợụủứừửữựỳỵỷỹ]")

internal fun looksVietnamese(text: String): Boolean = VietnameseLetters.containsMatchIn(text)
