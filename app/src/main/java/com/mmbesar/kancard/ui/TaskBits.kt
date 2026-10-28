package com.mmbesar.kancard.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Surface
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.DueDates
import com.mmbesar.kancard.data.SubtaskCount
import com.mmbesar.kancard.data.TaskEntity

// Small pieces shared by the Today screen, the list view and the board.

/** A small coloured dot for the priority: quiet for low, loud for high. Nothing is drawn for "none". */
@Composable
fun PriorityDot(priority: Int, modifier: Modifier = Modifier) {
    if (priority <= 0) return
    val colour = when (priority) {
        1 -> MaterialTheme.colorScheme.outline
        2 -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.error
    }
    Box(modifier = modifier.size(8.dp).clip(CircleShape).background(colour))
}

/** The one-line facts under a task title: due date, priority dot, subtasks, waiting-to-sync, extra text. */
@Composable
fun TaskFacts(
    task: TaskEntity,
    pending: Boolean,
    subtasks: SubtaskCount?,
    extra: String? = null,
) {
    val due = task.dueDate
    if (due == null && task.priority <= 0 && !pending && subtasks == null && extra == null) return
    Row(
        modifier = Modifier.padding(top = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PriorityDot(task.priority)
        if (extra != null) {
            Text(
                extra,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
        if (due != null) {
            Text(
                stringResource(R.string.due_on, due),
                style = MaterialTheme.typography.labelMedium,
                color = if (DueDates.isOverdue(due)) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (subtasks != null) {
            Text(
                "☑ ${subtasks.done}/${subtasks.total}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (pending) {
            Text(
                stringResource(R.string.task_waiting),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/**
 * One task as a flat row (Today screen and list view): a circle to mark it done, the title,
 * the facts, and an optional piece on the right.
 */
@Composable
fun TaskRow(
    task: TaskEntity,
    pending: Boolean,
    subtasks: SubtaskCount?,
    extra: String?,
    onDone: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: @Composable (() -> Unit)? = null,
) {
    val ring = taskColor(task.colorId) ?: MaterialTheme.colorScheme.outline
    val doneLabel = stringResource(R.string.task_close)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 4.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onDone) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(2.dp, ring, CircleShape)
                    .semantics { contentDescription = doneLabel },
            )
        }
        Column(modifier = Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                task.title,
                style = MaterialTheme.typography.bodyLarge.copy(textDirection = TextDirection.Content),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(),
            )
            TaskFacts(task, pending, subtasks, extra)
        }
        if (trailing != null) trailing()
    }
}

/** A heading that starts a group of rows. */
@Composable
fun SectionHeader(text: String, colour: Color = MaterialTheme.colorScheme.primary) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = colour,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/** A single line to add a task: type a title, press Enter on the keyboard. */
@Composable
fun QuickAddField(hint: String, onAdd: (String) -> Unit, modifier: Modifier = Modifier) {
    var text by remember { mutableStateOf("") }
    fun submit() {
        val title = text.trim()
        if (title.isNotEmpty()) {
            onAdd(title)
            text = ""
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        singleLine = true,
        placeholder = { Text(hint) },
        textStyle = LocalTextStyle.current.copy(textDirection = TextDirection.Content),
        keyboardOptions = KeyboardOptions(
            capitalization = KeyboardCapitalization.Sentences,
            imeAction = ImeAction.Done,
        ),
        keyboardActions = KeyboardActions(onDone = { submit() }),
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Swipe the content to the right to finish it. The row stays where it is until the list
 * drops the finished task (offline too: the change is saved on the phone at once).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToFinish(onDone: () -> Unit, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value == SwipeToDismissBoxValue.StartToEnd) {
                onDone()
                true
            } else {
                false
            }
        },
    )
    SwipeToDismissBox(
        state = state,
        enableDismissFromEndToStart = false,
        backgroundContent = {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(start = 20.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    stringResource(R.string.action_done),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
        },
    ) {
        // Opaque, so the colour behind only shows while swiping.
        Surface(color = MaterialTheme.colorScheme.background) { content() }
    }
}
