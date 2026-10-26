package com.dt.streamz.data

import org.junit.Assert.assertEquals
import org.junit.Test

class RecommendationRankingTest {

    @Test
    fun sortsByFeedbackScoreAndRemovesDuplicateKeys() {
        val values = listOf(
            "tmdb:low" to 0,
            "tmdb:liked" to 10,
            "tmdb:low" to 99,
            "tmdb:hidden" to -100,
        )

        val ranked = RecommendationRanking.rank(
            items = values,
            key = { it.first },
            score = { it.second },
            limit = 3,
        )

        assertEquals(
            listOf("tmdb:liked", "tmdb:low", "tmdb:hidden"),
            ranked.map { it.first },
        )
    }

    @Test
    fun zeroLimitReturnsEmpty() {
        assertEquals(
            emptyList<String>(),
            RecommendationRanking.rank(
                items = listOf("a"),
                key = { it },
                score = { 1 },
                limit = 0,
            ),
        )
    }
}
