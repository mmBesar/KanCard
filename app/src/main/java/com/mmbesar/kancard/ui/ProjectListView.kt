@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.kancard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.ColumnEntity
import com.mmbesar.kancard.data.SubtaskCount
import com.mmbesar.kancard.data.TaskEntity

/**
 * The project as a plain task list (the "task manager" way): tasks grouped under their column,
 * each one can be opened to show its subtasks right underneath. Reordering is done on the board.
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
) {
    val byColumn = remember(tasks) { tasks.groupBy { it.columnId } }
    // Which tasks have their subtasks open (kept only while the screen lives).
    val expanded = remember { mutableStateMapOf<Long, Boolean>() }

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
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
                    SectionHeader("${column.title} (${inColumn.size})")
                }
                items(inColumn, key = { it.id }) { task ->
                    val open = expanded[task.id] == true
                    Column {
                        TaskRow(
                            task = task,
                            pending = task.id in pendingIds,
                            subtasks = subtaskCounts[task.id],
                            extra = null,
                            onDone = { viewModel.closeTask(task) },
                            onClick = { onOpen(task) },
                            trailing = {
                                IconButton(onClick = {
                                    val nowOpen = !open
                                    expanded[task.id] = nowOpen
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
                        if (open) {
                            Column(modifier = Modifier.padding(start = 52.dp, end = 16.dp, bottom = 8.dp)) {
                                SubtasksSection(viewModel, task, showTitle = false)
                            }
                        }
                    }
                }
            }
        }
    }
}
