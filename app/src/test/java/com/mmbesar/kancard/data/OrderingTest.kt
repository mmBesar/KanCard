package com.mmbesar.kancard.data

import org.junit.Assert.assertEquals
import org.junit.Test

/** Checks the pure "where does a moved task land" helper. */
class OrderingTest {

    @Test
    fun insertsAtTop() {
        assertEquals(listOf(9L, 1L, 2L, 3L), insertBefore(listOf(1, 2, 3), movedId = 9, beforeId = 1))
    }

    @Test
    fun insertsInTheMiddle() {
        assertEquals(listOf(1L, 9L, 2L, 3L), insertBefore(listOf(1, 2, 3), movedId = 9, beforeId = 2))
    }

    @Test
    fun nullMeansAtTheBottom() {
        assertEquals(listOf(1L, 2L, 9L), insertBefore(listOf(1, 2), movedId = 9, beforeId = null))
    }

    @Test
    fun unknownTargetMeansAtTheBottom() {
        assertEquals(listOf(1L, 2L, 9L), insertBefore(listOf(1, 2), movedId = 9, beforeId = 77))
    }

    @Test
    fun movingWithinTheSameColumnNeverDuplicates() {
        assertEquals(listOf(2L, 1L, 3L), insertBefore(listOf(1, 2, 3), movedId = 2, beforeId = 1))
        assertEquals(listOf(1L, 3L, 2L), insertBefore(listOf(1, 2, 3), movedId = 2, beforeId = null))
    }

    @Test
    fun emptyColumn() {
        assertEquals(listOf(5L), insertBefore(emptyList(), movedId = 5, beforeId = null))
    }
}
