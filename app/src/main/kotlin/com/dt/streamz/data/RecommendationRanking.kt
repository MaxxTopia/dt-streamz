package com.dt.streamz.data

/** Small deterministic ranking primitive shared by For You and its tests. */
object RecommendationRanking {

    fun <T> rank(
        items: Iterable<T>,
        key: (T) -> String,
        score: (T) -> Int,
        limit: Int,
    ): List<T> {
        if (limit <= 0) return emptyList()
        return items
            .distinctBy(key)
            .sortedWith(compareByDescending<T> { score(it) }.thenBy { key(it) })
            .take(limit)
    }
}
