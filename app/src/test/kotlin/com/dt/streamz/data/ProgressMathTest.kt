package com.dt.streamz.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressMathTest {

    @Test
    fun ignoresTinyAndFinishedPositions() {
        assertEquals(0L, ProgressMath.resumePosition(9_999L, 300_000L))
        assertEquals(0L, ProgressMath.resumePosition(280_000L, 300_000L))
        assertTrue(ProgressMath.isFinished(280_000L, 300_000L))
    }

    @Test
    fun keepsNormalPosition() {
        assertEquals(120_000L, ProgressMath.resumePosition(120_000L, 300_000L))
        assertFalse(ProgressMath.isFinished(120_000L, 300_000L))
    }

    @Test
    fun treatsShortVideosAsFinishedAtTheEnd() {
        assertEquals(0L, ProgressMath.resumePosition(1_000L, 1_000L))
        assertTrue(ProgressMath.isFinished(1_000L, 1_000L))
    }
}
