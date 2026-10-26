package com.mmbesar.kancard.ui

import com.mmbesar.kancard.data.TaskEntity

/** The Today screen's sections. Plain data, so it is easy to test. */
data class TodayGroups(
    val overdue: List<TaskEntity>,
    val today: List<TaskEntity>,
    val soon: List<TaskEntity>,
    val important: List<TaskEntity>,
) {
    val isEmpty: Boolean get() = overdue.isEmpty() && today.isEmpty() && soon.isEmpty() && important.isEmpty()
}

/**
 * Sorts open tasks into the Today sections.
 *  - overdue: due before [today]
 *  - today: due [today]
 *  - soon: due after today, up to and including [soonEnd]
 *  - important: high priority (3) and not already in a section above
 * Dates are "YYYY-MM-DD" text, which compares correctly as text.
 */
fun groupToday(tasks: List<TaskEntity>, today: String, soonEnd: String): TodayGroups {
    val byDueThenPriority = compareBy<TaskEntity>({ it.dueDate ?: "9999-99-99" }, { -it.priority }, { it.title.lowercase() })
    val open = tasks.filter { it.isActive }
    val overdue = open.filter { it.dueDate != null && it.dueDate < today }
    val dueToday = open.filter { it.dueDate == today }
    val soon = open.filter { it.dueDate != null && it.dueDate > today && it.dueDate <= soonEnd }
    val placed = (overdue + dueToday + soon).map { it.id }.toSet()
    val important = open.filter { it.priority >= 3 && it.id !in placed }
    return TodayGroups(
        overdue = overdue.sortedWith(byDueThenPriority),
        today = dueToday.sortedWith(byDueThenPriority),
        soon = soon.sortedWith(byDueThenPriority),
        important = important.sortedWith(byDueThenPriority),
    )
}
