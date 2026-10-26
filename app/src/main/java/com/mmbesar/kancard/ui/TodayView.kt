@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.kancard.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.SubtaskCount
import com.mmbesar.kancard.data.TaskEntity

/**
 * The first screen: what needs attention across ALL projects. Overdue, due today, coming up
 * this week, and high priority tasks without a date. Tap a task for details; tap its circle to finish it.
 */
@Composable
fun TodayView(
    groups: TodayGroups,
    projectNames: Map<Long, String>,
    pendingIds: Set<Long>,
    subtaskCounts: Map<Long, SubtaskCount>,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onOpen: (TaskEntity) -> Unit,
    onDone: (TaskEntity) -> Unit,
) {
    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        if (groups.isEmpty) {
            Box(modifier = Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(R.string.today_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            return@PullToRefreshBox
        }
        // Colours are read here: the list builder below is not a composable.
        val red = MaterialTheme.colorScheme.error
        val accent = MaterialTheme.colorScheme.primary
        val soft = MaterialTheme.colorScheme.tertiary
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            section(R.string.today_overdue, red, groups.overdue, projectNames, pendingIds, subtaskCounts, onOpen, onDone)
            section(R.string.today_today, accent, groups.today, projectNames, pendingIds, subtaskCounts, onOpen, onDone)
            section(R.string.today_soon, accent, groups.soon, projectNames, pendingIds, subtaskCounts, onOpen, onDone)
            section(R.string.today_important, soft, groups.important, projectNames, pendingIds, subtaskCounts, onOpen, onDone)
        }
    }
}

/** Adds one section (heading + rows) to the list; nothing if the section is empty. */
private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: Int,
    colour: androidx.compose.ui.graphics.Color,
    tasks: List<TaskEntity>,
    projectNames: Map<Long, String>,
    pendingIds: Set<Long>,
    subtaskCounts: Map<Long, SubtaskCount>,
    onOpen: (TaskEntity) -> Unit,
    onDone: (TaskEntity) -> Unit,
) {
    if (tasks.isEmpty()) return
    item(key = "header-$title") {
        SectionHeader(stringResource(title), colour)
    }
    items(tasks, key = { it.id }) { task ->
        TaskRow(
            task = task,
            pending = task.id in pendingIds,
            subtasks = subtaskCounts[task.id],
            extra = projectNames[task.projectId],
            onDone = { onDone(task) },
            onClick = { onOpen(task) },
        )
    }
}
