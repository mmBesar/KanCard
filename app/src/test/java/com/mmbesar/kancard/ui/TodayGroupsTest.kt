package com.mmbesar.kancard.ui

import com.mmbesar.kancard.data.DueDates
import com.mmbesar.kancard.data.TaskEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TodayGroupsTest {

    private fun task(id: Long, due: String?, priority: Int = 0, active: Boolean = true) = TaskEntity(
        id = id, projectId = 1, columnId = 1, swimlaneId = 0, title = "t$id", description = "",
        position = 1, dueDate = due, isActive = active, colorId = "", priority = priority,
    )

    @Test
    fun tasksLandInTheRightSection() {
        val groups = groupToday(
            listOf(
                task(1, "2026-10-20"),
                task(2, "2026-10-26"),
                task(3, "2026-10-30"),
                task(4, "2026-12-01", priority = 3),
                task(5, null, priority = 3),
                task(6, "2026-12-01"),
                task(7, "2026-10-01", active = false),
            ),
            today = "2026-10-26",
            soonEnd = "2026-11-02",
        )
        assertEquals(listOf(1L), groups.overdue.map { it.id })
        assertEquals(listOf(2L), groups.today.map { it.id })
        assertEquals(listOf(3L), groups.soon.map { it.id })
        assertEquals(listOf(4L, 5L), groups.important.map { it.id })
    }

    @Test
    fun aHighPriorityTaskDueTodayIsNotListedTwice() {
        val groups = groupToday(listOf(task(1, "2026-10-26", priority = 3)), "2026-10-26", "2026-11-02")
        assertEquals(1, groups.today.size)
        assertTrue(groups.important.isEmpty())
    }

    @Test
    fun nothingDueMeansEmpty() {
        assertTrue(groupToday(listOf(task(1, null)), "2026-10-26", "2026-11-02").isEmpty)
    }

    @Test
    fun plusDaysCrossesMonthEnds() {
        assertEquals("2026-11-02", DueDates.plusDays("2026-10-26", 7))
        assertEquals("2026-10-26", DueDates.plusDays("2026-10-26", 0))
        assertEquals("garbage", DueDates.plusDays("garbage", 3))
    }
}
