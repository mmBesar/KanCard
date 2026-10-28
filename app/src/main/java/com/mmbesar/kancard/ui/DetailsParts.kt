package com.mmbesar.kancard.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.TaskEntity
import java.text.DateFormat
import java.util.Date

// The subtask and comment blocks, used by the details sheet and (subtasks) by the list view.

/** Subtasks of one task: tap to tick off, X to delete, a field to add one. */
@Composable
fun SubtasksSection(viewModel: BoardViewModel, task: TaskEntity, showTitle: Boolean = true) {
    val subtasks by remember(task.id) { viewModel.subtasks(task.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var newSubtask by rememberSaveable(task.id) { mutableStateOf("") }
    val fieldStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content)

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (showTitle) {
            val done = subtasks.count { it.status == 2 }
            Text(
                stringResource(R.string.details_subtasks) + if (subtasks.isNotEmpty()) "  ($done/${subtasks.size})" else "",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        if (subtasks.isEmpty()) {
            Text(stringResource(R.string.details_no_subtasks), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        subtasks.forEach { sub ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleSubtask(task, sub) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = sub.status == 2, onCheckedChange = { viewModel.toggleSubtask(task, sub) })
                Text(
                    sub.title,
                    style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                    textDecoration = if (sub.status == 2) TextDecoration.LineThrough else null,
                    color = if (sub.status == 2) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { viewModel.deleteSubtask(task, sub) }) {
                    Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.details_delete))
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = newSubtask,
                onValueChange = { newSubtask = it },
                label = { Text(stringResource(R.string.subtask_hint)) },
                textStyle = fieldStyle,
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    viewModel.addSubtask(task, newSubtask.trim())
                    newSubtask = ""
                },
                enabled = newSubtask.isNotBlank() && !busy,
            ) { Text(stringResource(R.string.action_add)) }
        }
    }
}

/** Comments of one task, newest at the bottom, with a box to write one (Markdown works). */
@Composable
fun CommentsSection(viewModel: BoardViewModel, task: TaskEntity) {
    val comments by remember(task.id) { viewModel.comments(task.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    var newComment by rememberSaveable(task.id) { mutableStateOf("") }
    val fieldStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content)
    val dates = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            stringResource(R.string.details_comments) + if (comments.isNotEmpty()) "  (${comments.size})" else "",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        if (comments.isEmpty()) {
            Text(stringResource(R.string.details_no_comments), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        comments.forEach { comment ->
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        comment.author.ifBlank { "?" } + "  ·  " + dates.format(Date(comment.created * 1000L)),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.deleteComment(task, comment) }) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.details_delete))
                    }
                }
                MarkdownText(comment.content, modifier = Modifier.fillMaxWidth())
            }
        }
        OutlinedTextField(
            value = newComment,
            onValueChange = { newComment = it },
            label = { Text(stringResource(R.string.comment_hint)) },
            textStyle = fieldStyle,
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = {
                viewModel.addComment(task, newComment.trim())
                newComment = ""
            },
            enabled = newComment.isNotBlank() && !busy,
            modifier = Modifier.align(Alignment.End),
        ) { Text(stringResource(R.string.action_send)) }
    }
}

/**
 * The subtasks of one task as compact rows with a tick box, shown right under the task
 * (nothing is drawn when the task has none). Tap the box or the title to tick.
 */
@Composable
fun SubtasksInline(viewModel: BoardViewModel, task: TaskEntity, modifier: Modifier = Modifier) {
    val subtasks by remember(task.id) { viewModel.subtasks(task.id) }
        .collectAsStateWithLifecycle(initialValue = emptyList())
    if (subtasks.isEmpty()) return
    Column(modifier = modifier.fillMaxWidth()) {
        subtasks.forEach { sub ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.toggleSubtask(task, sub) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = sub.status == 2, onCheckedChange = { viewModel.toggleSubtask(task, sub) })
                Text(
                    sub.title,
                    style = MaterialTheme.typography.bodyMedium.copy(textDirection = TextDirection.Content),
                    textDecoration = if (sub.status == 2) TextDecoration.LineThrough else null,
                    color = if (sub.status == 2) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
