package com.mmbesar.kancard.data

/**
 * Pure helper (no Android, easy to test): the new order of a column after a move.
 *
 * [others] are the ids already in the target column (and swimlane).
 * The moved task is placed right BEFORE [beforeId]; null (or an id that is not
 * in the list) means "at the very bottom". If the moved task was already in the
 * list it is taken out first, so it never appears twice.
 */
fun insertBefore(others: List<Long>, movedId: Long, beforeId: Long?): List<Long> {
    val result = others.filter { it != movedId }.toMutableList()
    val index = if (beforeId == null) result.size else result.indexOf(beforeId).let { if (it < 0) result.size else it }
    result.add(index, movedId)
    return result
}
