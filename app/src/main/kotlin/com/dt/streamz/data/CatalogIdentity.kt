package com.dt.streamz.data

import java.util.Locale

/**
 * Small, explicit catalog-identity rules for content that has more than one
 * upstream representation. These rules are intentionally narrow: a saved
 * title is only migrated when its title and episode number prove the known
 * Naruto: Shippuden identity. Unknown provider/title pairs stay fail-closed.
 */
internal const val CANONICAL_ANILIST_PROVIDER_ID = "anilist"
internal const val CANONICAL_NARUTO_SHIPPUDEN_ID = "1735"

private const val CANONICAL_NARUTO_SHIPPUDEN_TITLE = "Naruto: Shippuden"
private const val NARUTO_SHIPPUDEN_POSTER =
    "https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/bx1735-kGfVm0YqCPcu.png"
private const val NARUTO_FIRST_EPISODE = 1
private const val NARUTO_LAST_EPISODE = 500

/** Punctuation-insensitive title key used for identity comparisons. */
internal fun catalogTitleKey(title: String): String = title
    .lowercase(Locale.US)
    .replace(Regex("[^a-z0-9]+"), " ")
    .trim()
    .replace(Regex("\\s+"), " ")

/** AniList uses the romaji spelling "Shippuuden" while other sources do not. */
internal fun canonicalSearchTitle(title: String): String {
    return when (catalogTitleKey(title)) {
        "naruto shippuden", "naruto shippuuden" -> "naruto shippuden"
        else -> catalogTitleKey(title)
    }
}

internal fun isNarutoShippudenTitle(title: String): Boolean =
    canonicalSearchTitle(title) == "naruto shippuden"

/**
 * Convert only the known Naruto aliases to the stable AniList/VidNest route.
 * The episode number is bounded by the verified AniList episode count so a
 * malformed/stale entry cannot be turned into a guessed playback URL.
 */
internal fun WatchEntry.canonicalizedForCatalog(): WatchEntry {
    if (!isNarutoShippudenTitle(titleName)) return this
    if (kind != null && kind != MediaKind.Anime.name) return this

    val episode = episodeNumber.takeIf { it in NARUTO_FIRST_EPISODE..NARUTO_LAST_EPISODE }
        ?: Regex("(?:ep:|s\\d+e)(\\d+)$", RegexOption.IGNORE_CASE)
            .find(episodeId)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()
            ?.takeIf { it in NARUTO_FIRST_EPISODE..NARUTO_LAST_EPISODE }
        ?: return this

    return copy(
        providerId = CANONICAL_ANILIST_PROVIDER_ID,
        titleId = CANONICAL_NARUTO_SHIPPUDEN_ID,
        titleName = CANONICAL_NARUTO_SHIPPUDEN_TITLE,
        poster = NARUTO_SHIPPUDEN_POSTER,
        episodeId = "ep:$episode",
        episodeNumber = episode,
        // Do not carry a provider-specific/random episode label across the
        // migration. AniList details supplies the verified title on resume.
        episodeTitle = null,
        kind = MediaKind.Anime.name,
    )
}

/**
 * Search-level collision guard. AniList is the canonical anime catalog here;
 * duplicate anime titles from legacy/scraping providers must not replace its
 * flat episode list and explicit Dub/Sub sources.
 */
internal fun mergeCatalogSearchResults(results: List<SearchResult>): List<SearchResult> {
    val selected = LinkedHashMap<String, SearchResult>()
    for (result in results) {
        // If AniList is unavailable, do not silently substitute the known
        // Naruto title with a provider that has different seasons/servers.
        // This applies across Anime/Series classifications because the wrong
        // provider may label the same show as a generic TV series.
        if (isNarutoShippudenTitle(result.title) &&
            result.providerId != CANONICAL_ANILIST_PROVIDER_ID
        ) continue
        val key = if (result.kind == MediaKind.Anime) {
            "anime:${canonicalSearchTitle(result.title)}"
        } else {
            "${result.providerId}:${result.id}"
        }
        val existing = selected[key]
        if (existing == null || catalogProviderPriority(result) < catalogProviderPriority(existing)) {
            selected[key] = result
        }
    }
    return selected.values.toList()
}

private fun catalogProviderPriority(result: SearchResult): Int = when {
    result.kind == MediaKind.Anime && result.providerId == CANONICAL_ANILIST_PROVIDER_ID -> 0
    result.kind == MediaKind.Anime -> 100
    else -> 0
}
