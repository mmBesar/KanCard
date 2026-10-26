@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.kancard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.DueDates
import com.mmbesar.kancard.data.TaskEntity

/**
 * One task in full, as a sheet that slides up over the board: the description (Markdown shown
 * properly), subtasks to tick off, and comments. Loaded copies stay readable offline.
 */
@Composable
fun TaskDetailsSheet(
    viewModel: BoardViewModel,
    task: TaskEntity,
    snackbar: SnackbarHostState,
    onEdit: () -> Unit,
    onDismiss: () -> Unit,
) {
    // Fresh copy from the server every time the sheet opens (silent when offline).
    LaunchedEffect(task.id) { viewModel.refreshDetails(task) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.headlineSmall.copy(textDirection = TextDirection.Content),
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = onEdit) { Text(stringResource(R.string.task_edit)) }
            }
            val due = task.dueDate
            if (due != null) {
                Text(
                    stringResource(R.string.due_on, due),
                    color = if (DueDates.isOverdue(due)) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (task.priority > 0) {
                val label = when (task.priority) {
                    1 -> R.string.priority_low
                    2 -> R.string.priority_medium
                    else -> R.string.priority_high
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PriorityDot(task.priority)
                    Text(
                        stringResource(R.string.field_priority) + ": " + stringResource(label),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (task.description.isNotBlank()) {
                MarkdownText(task.description, modifier = Modifier.fillMaxWidth())
            }
            HorizontalDivider()
            SubtasksSection(viewModel, task)
            HorizontalDivider()
            CommentsSection(viewModel, task)
            // Errors ("needs a connection" ...) must be visible above the sheet's own scrim.
            SnackbarHost(snackbar)
        }
    }
}
