@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.kancard.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.TaskEntity

/** Closed tasks of the current project: bring them back or delete them for good. */
@Composable
fun ClosedTasksScreen(
    viewModel: BoardViewModel,
    snackbar: SnackbarHostState,
    onBack: () -> Unit,
) {
    val tasks by viewModel.closedTasks.collectAsStateWithLifecycle()
    var toDelete by remember { mutableStateOf<TaskEntity?>(null) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.menu_closed_tasks)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        if (tasks.isEmpty()) {
            Text(
                stringResource(R.string.closed_empty),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(padding).padding(24.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(tasks, key = { it.id }) { task ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        ),
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                task.title,
                                style = MaterialTheme.typography.titleMedium.copy(
                                    textDirection = androidx.compose.ui.text.style.TextDirection.Content,
                                ),
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { viewModel.reopenTask(task) }) {
                                    Text(stringResource(R.string.closed_reopen))
                                }
                                TextButton(onClick = { toDelete = task }) {
                                    Text(
                                        stringResource(R.string.closed_delete),
                                        color = MaterialTheme.colorScheme.error,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    toDelete?.let { task ->
        ConfirmDialog(
            title = stringResource(R.string.closed_delete_title),
            message = stringResource(R.string.closed_delete_message, task.title),
            confirmLabel = stringResource(R.string.closed_delete),
            onDismiss = { toDelete = null },
            onConfirm = {
                viewModel.deleteTask(task)
                toDelete = null
            },
        )
    }
}
