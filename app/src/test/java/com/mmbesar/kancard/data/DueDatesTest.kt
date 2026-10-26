package com.mmbesar.kancard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DueDatesTest {

    // 2026-10-25 00:00:00 in UTC+3 (e.g. Cairo) is 2026-10-24 21:00:00 UTC.
    private val midnightInPlusThree = 1_792_875_600L
    // 2026-10-25 00:00:00 in UTC-5 is 2026-10-25 05:00:00 UTC.
    private val midnightInMinusFive = 1_792_904_400L

    @Test
    fun serverEastOfUtcStillShowsTheChosenDay() {
        assertEquals("2026-10-25", DueDates.fromEpochSeconds(midnightInPlusThree))
    }

    @Test
    fun serverWestOfUtcStillShowsTheChosenDay() {
        assertEquals("2026-10-25", DueDates.fromEpochSeconds(midnightInMinusFive))
    }

    @Test
    fun zeroMeansNoDate() {
        assertNull(DueDates.fromEpochSeconds(0))
    }

    @Test
    fun pickerRoundTrip() {
        val millis = DueDates.toPickerMillis("2026-10-25")!!
        assertEquals("2026-10-25", DueDates.fromPickerMillis(millis))
    }

    @Test
    fun invalidDatesAreRejected() {
        assertNull(DueDates.toPickerMillis("not a date"))
        assertNull(DueDates.toPickerMillis("2026-13-45"))
        assertNull(DueDates.toPickerMillis(""))
        assertNull(DueDates.toPickerMillis(null))
    }

    @Test
    fun overdueComparison() {
        assertTrue(DueDates.isOverdue("2026-10-23", today = "2026-10-24"))
        assertFalse(DueDates.isOverdue("2026-10-24", today = "2026-10-24"))
        assertFalse(DueDates.isOverdue("2026-10-25", today = "2026-10-24"))
        assertFalse(DueDates.isOverdue(null, today = "2026-10-24"))
    }
}
