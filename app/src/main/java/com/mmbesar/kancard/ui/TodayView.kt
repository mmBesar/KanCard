@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.kancard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.SubtaskCount
import com.mmbesar.kancard.data.TaskEntity

/**
 * The first screen: what needs attention across ALL projects. Overdue, due today, coming up
 * this week, and high priority tasks without a date. Two ways to read it:
 *  - by time (Overdue / Today / Soon / Important), with the project name under each task;
 *  - by project (a tree: project, its tasks, their subtasks).
 * Tap a task for details, tap its circle (or swipe it right) to finish it, tick subtasks in place.
 */
@Composable
fun TodayView(
    viewModel: BoardViewModel,
    groups: TodayGroups,
    projectNames: Map<Long, String>,
    pendingIds: Set<Long>,
    subtaskCounts: Map<Long, SubtaskCount>,
    byProject: Boolean,
    onByProjectChange: (Boolean) -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (TaskEntity) -> Unit,
    onDone: (TaskEntity) -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = !byProject,
                    onClick = { onByProjectChange(false) },
                    label = { Text(stringResource(R.string.today_by_time)) },
                )
                FilterChip(
                    selected = byProject,
                    onClick = { onByProjectChange(true) },
                    label = { Text(stringResource(R.string.today_by_project)) },
                )
            }
            if (groups.isEmpty) {
                Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        stringResource(R.string.today_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // Colours are read here: the list builder below is not a composable.
                val red = MaterialTheme.colorScheme.error
                val accent = MaterialTheme.colorScheme.primary
                val soft = MaterialTheme.colorScheme.tertiary
                val titleOverdue = stringResource(R.string.today_overdue)
                val titleToday = stringResource(R.string.today_today)
                val titleSoon = stringResource(R.string.today_soon)
                val titleImportant = stringResource(R.string.today_important)
                val everything = remember(groups) { groups.overdue + groups.today + groups.soon + groups.important }
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    if (byProject) {
                        val perProject = everything.groupBy { it.projectId }
                            .toList()
                            .sortedBy { (projectId, _) -> projectNames[projectId].orEmpty().lowercase() }
                        for ((projectId, tasks) in perProject) {
                            section(
                                "p$projectId",
                                "${projectNames[projectId].orEmpty()} (${tasks.size})",
                                accent,
                                tasks.sortedWith(compareBy({ it.dueDate ?: "9999-99-99" }, { -it.priority }, { it.title.lowercase() })),
                                false,
                                viewModel, projectNames, pendingIds, subtaskCounts, onOpen, onDone,
                            )
                        }
                    } else {
                        section("overdue", titleOverdue, red, groups.overdue, true, viewModel, projectNames, pendingIds, subtaskCounts, onOpen, onDone)
                        section("today", titleToday, accent, groups.today, true, viewModel, projectNames, pendingIds, subtaskCounts, onOpen, onDone)
                        section("soon", titleSoon, accent, groups.soon, true, viewModel, projectNames, pendingIds, subtaskCounts, onOpen, onDone)
                        section("important", titleImportant, soft, groups.important, true, viewModel, projectNames, pendingIds, subtaskCounts, onOpen, onDone)
                    }
                }
            }
        }
    }
}

/** Adds one group (heading + rows with their subtasks) to the list; nothing if the group is empty. */
private fun LazyListScope.section(
    key: String,
    title: String,
    colour: Color,
    tasks: List<TaskEntity>,
    showProject: Boolean,
    viewModel: BoardViewModel,
    projectNames: Map<Long, String>,
    pendingIds: Set<Long>,
    subtaskCounts: Map<Long, SubtaskCount>,
    onOpen: (TaskEntity) -> Unit,
    onDone: (TaskEntity) -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "header-$key") {
        SectionHeader(title, colour)
    }
    // The key mixes the group in: the same task can never appear in two groups, but stay safe.
    items(tasks, key = { "$key-${it.id}" }) { task ->
        Column {
            SwipeToFinish(onDone = { onDone(task) }) {
                TaskRow(
                    task = task,
                    pending = task.id in pendingIds,
                    subtasks = subtaskCounts[task.id],
                    extra = if (showProject) projectNames[task.projectId] else null,
                    onDone = { onDone(task) },
                    onClick = { onOpen(task) },
                )
            }
            SubtasksInline(viewModel, task, Modifier.padding(start = 40.dp, end = 16.dp, bottom = 4.dp))
        }
    }
}
