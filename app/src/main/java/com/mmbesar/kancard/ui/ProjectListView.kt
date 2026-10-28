@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.kancard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.ColumnEntity
import com.mmbesar.kancard.data.SubtaskCount
import com.mmbesar.kancard.data.TaskEntity
import kotlin.math.roundToInt

/** Where a dragged task would land: in [columnId], in front of [beforeTaskId] (null = at the end). */
private data class ListDrop(val columnId: Long, val beforeTaskId: Long?)

/**
 * The project as a plain task list (the "task manager" way): tasks grouped under their column.
 * Tasks with subtasks show them right underneath (tick them off in place). Swipe a task to the
 * right to finish it; press and hold a task, then drag it up or down to move it - also into
 * another column.
 */
@Composable
fun ProjectListView(
    viewModel: BoardViewModel,
    columns: List<ColumnEntity>,
    tasks: List<TaskEntity>,
    pendingIds: Set<Long>,
    subtaskCounts: Map<Long, SubtaskCount>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (TaskEntity) -> Unit,
    dragEnabled: Boolean = true,
) {
    val byColumn = remember(tasks) { tasks.groupBy { it.columnId } }
    val tasksById = remember(tasks) { tasks.associateBy { it.id } }
    // Tasks whose subtasks the user closed (or, for tasks without any, opened to add one).
    val toggled = remember { mutableStateMapOf<Long, Boolean>() }

    val listState = rememberLazyListState()
    val haptic = LocalHapticFeedback.current

    // The drag loop below lives as long as the list; it must always see the newest data.
    val columnsNow by rememberUpdatedState(columns)
    val byColumnNow by rememberUpdatedState(byColumn)
    val tasksByIdNow by rememberUpdatedState(tasksById)
    val dragEnabledNow by rememberUpdatedState(dragEnabled)

    var dragged by remember { mutableStateOf<TaskEntity?>(null) }
    var pointerY by remember { mutableFloatStateOf(0f) }
    var rowHeight by remember { mutableFloatStateOf(0f) }
    var drop by remember { mutableStateOf<ListDrop?>(null) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { offset ->
                            if (!dragEnabledNow) return@detectDragGesturesAfterLongPress
                            val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                                offset.y >= it.offset && offset.y <= it.offset + it.size
                            }
                            val task = (hit?.key as? Long)?.let { tasksByIdNow[it] }
                            if (task != null && hit != null) {
                                dragged = task
                                rowHeight = hit.size.toFloat()
                                pointerY = offset.y
                                drop = computeListDrop(offset.y, task.id, listState, columnsNow, byColumnNow, null)
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                        },
                        onDrag = { change, _ ->
                            val task = dragged
                            if (task != null) {
                                change.consume()
                                pointerY = change.position.y
                                // Near the top or bottom edge the list scrolls by itself.
                                val edge = 96f
                                val height = viewportHeight
                                if (pointerY < edge) listState.dispatchRawDelta(-24f)
                                else if (height > 0f && pointerY > height - edge) listState.dispatchRawDelta(24f)
                                drop = computeListDrop(pointerY, task.id, listState, columnsNow, byColumnNow, drop)
                            }
                        },
                        onDragEnd = {
                            val task = dragged
                            val target = drop
                            dragged = null
                            drop = null
                            if (task != null && target != null) {
                                viewModel.moveTask(task, target.columnId, target.beforeTaskId)
                            }
                        },
                        onDragCancel = {
                            dragged = null
                            drop = null
                        },
                    )
                }
                .onSizeChanged { viewportHeight = it.height.toFloat() },
        ) {
            // New tasks go into the first column (the "to do" stage).
            item(key = "quick-add") {
                QuickAddField(
                    hint = stringResource(R.string.quick_add_hint),
                    onAdd = { title -> columns.firstOrNull()?.let { viewModel.quickAdd(it.id, title) } },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            columns.forEach { column ->
                val inColumn = byColumn[column.id].orEmpty()
                item(key = "column-${column.id}") {
                    Column {
                        SectionHeader("${column.title} (${inColumn.size})")
                        // A column without tasks can still receive one: the line shows it.
                        if (inColumn.isEmpty() && drop?.columnId == column.id) DropLine()
                    }
                }
                items(inColumn, key = { it.id }) { task ->
                    val count = subtaskCounts[task.id]
                    val open = toggled[task.id] ?: (count != null)
                    Column(modifier = Modifier.alpha(if (dragged?.id == task.id) 0.35f else 1f)) {
                        if (drop?.beforeTaskId == task.id && dragged?.id != task.id) DropLine()
                        SwipeToFinish(onDone = { viewModel.closeTask(task) }) {
                            TaskRow(
                                task = task,
                                pending = task.id in pendingIds,
                                subtasks = count,
                                extra = null,
                                onDone = { viewModel.closeTask(task) },
                                onClick = { onOpen(task) },
                                trailing = {
                                    IconButton(onClick = {
                                        val nowOpen = !open
                                        toggled[task.id] = nowOpen
                                        if (nowOpen) viewModel.refreshDetails(task)
                                    }) {
                                        Icon(
                                            Icons.Filled.ArrowDropDown,
                                            contentDescription = stringResource(R.string.details_subtasks),
                                            modifier = Modifier.rotate(if (open) 180f else 0f),
                                        )
                                    }
                                },
                            )
                        }
                        if (open) {
                            Column(modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 8.dp)) {
                                SubtasksSection(viewModel, task, showTitle = false)
                            }
                        }
                        // Dropped at the very end of this column: the line goes under its last task.
                        if (drop?.columnId == column.id && drop?.beforeTaskId == null &&
                            inColumn.lastOrNull()?.id == task.id
                        ) {
                            DropLine()
                        }
                    }
                }
            }
        }

        // The task that follows the finger.
        val moving = dragged
        if (moving != null) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .offset { IntOffset(0, (pointerY - rowHeight / 2f).roundToInt()) },
            ) {
                Text(
                    moving.title,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
                )
            }
        }
    }
}

/** The line that shows where a dragged task would land. */
@Composable
private fun DropLine() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .height(3.dp)
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
    )
}

/**
 * Where would a task dropped at [pointerY] land? [pointerY] is measured in the list's own
 * coordinates, like the item offsets. [dragged] itself is ignored. Over the quick-add field
 * nothing changes ([previous] is kept).
 */
private fun computeListDrop(
    pointerY: Float,
    dragged: Long,
    listState: LazyListState,
    columns: List<ColumnEntity>,
    byColumn: Map<Long, List<TaskEntity>>,
    previous: ListDrop?,
): ListDrop? {
    val visible = listState.layoutInfo.visibleItemsInfo
    if (visible.isEmpty()) return previous
    val hit = visible.firstOrNull { pointerY >= it.offset && pointerY <= it.offset + it.size }
        ?: return if (pointerY < visible.first().offset) previous else lastColumnEnd(columns)
    val key = hit.key
    return when {
        // On a column title: the task goes to the top of that column.
        key is String && key.startsWith("column-") -> {
            val columnId = key.removePrefix("column-").toLongOrNull() ?: return previous
            ListDrop(columnId, byColumn[columnId].orEmpty().firstOrNull { it.id != dragged }?.id)
        }
        // On another task: before it (upper half) or after it (lower half).
        key is Long && key != dragged -> {
            val task = byColumn.values.flatten().firstOrNull { it.id == key } ?: return previous
            val inColumn = byColumn[task.columnId].orEmpty().filter { it.id != dragged }
            val upperHalf = pointerY < hit.offset + hit.size / 2f
            if (upperHalf) {
                ListDrop(task.columnId, task.id)
            } else {
                val index = inColumn.indexOfFirst { it.id == task.id }
                ListDrop(task.columnId, inColumn.getOrNull(index + 1)?.id)
            }
        }
        else -> previous
    }
}

/** Below everything: the end of the last column. */
private fun lastColumnEnd(columns: List<ColumnEntity>): ListDrop? {
    val last = columns.lastOrNull() ?: return null
    return ListDrop(last.id, null)
}
