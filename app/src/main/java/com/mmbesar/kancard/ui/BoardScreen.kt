@file:OptIn(ExperimentalMaterial3Api::class)

package com.mmbesar.kancard.ui

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.DueDates
import com.mmbesar.kancard.data.SyncState
import com.mmbesar.kancard.data.TaskEntity
import com.mmbesar.kancard.security.Mode
import kotlinx.coroutines.launch

/** Which pop-up is open on the main screen (at most one at a time). */
private sealed interface BoardDialog {
    data class NewTask(val columnId: Long) : BoardDialog
    data class EditTask(val task: TaskEntity) : BoardDialog
    data object NewProject : BoardDialog
    data object ConfirmArchive : BoardDialog
    data object UpdateToken : BoardDialog
    data class ConfirmImport(val uri: Uri) : BoardDialog
}

/** The two main places of the app. */
private const val TAB_TODAY = 0
private const val TAB_PROJECTS = 1

/**
 * The main screen. Two places, switched by the bar at the bottom:
 *  - Today: what needs attention across all projects;
 *  - Projects: one project at a time, shown as a Kanban board or as a task list
 *    (each project remembers which one it opens with).
 * Task details slide up as a sheet over whatever is on screen.
 */
@Composable
fun BoardScreen(viewModel: BoardViewModel, themePrefs: ThemePrefs, viewPrefs: ViewPrefs) {
    val mode by viewModel.mode.collectAsStateWithLifecycle()
    val loaded by viewModel.projectsLoaded.collectAsStateWithLifecycle()
    val projects by viewModel.projects.collectAsStateWithLifecycle()
    val project by viewModel.currentProject.collectAsStateWithLifecycle()
    val columns by viewModel.columns.collectAsStateWithLifecycle()
    val projectTasks by viewModel.openTasks.collectAsStateWithLifecycle()
    val everyTask by viewModel.allOpenTasks.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val refreshing by viewModel.refreshing.collectAsStateWithLifecycle()
    val pendingCount by viewModel.pendingCount.collectAsStateWithLifecycle()
    val pendingIds by viewModel.pendingTaskIds.collectAsStateWithLifecycle()
    val syncState by viewModel.syncState.collectAsStateWithLifecycle()
    val certReview by viewModel.certReview.collectAsStateWithLifecycle()
    val look by themePrefs.settings.collectAsStateWithLifecycle()
    val subtaskCounts by viewModel.subtaskCounts.collectAsStateWithLifecycle()
    val viewDefaults by viewPrefs.defaults.collectAsStateWithLifecycle()
    val rememberLast by viewPrefs.rememberLast.collectAsStateWithLifecycle()
    val todayByProject by viewPrefs.todayByProject.collectAsStateWithLifecycle()

    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(viewModel) { viewModel.events.collect { snackbar.showSnackbar(it) } }

    var tab by rememberSaveable { mutableIntStateOfToday() }
    var showClosed by rememberSaveable { mutableStateOf(false) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var detailsId by rememberSaveable { mutableStateOf<Long?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var dialog by remember { mutableStateOf<BoardDialog?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var projectMenuOpen by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState(pageCount = { columns.size })

    // How THIS project is shown: what was chosen during this visit, else its saved default, else the board.
    val projectId = project?.id ?: 0L
    var chosenView by rememberSaveable(projectId) { mutableStateOf<String?>(null) }
    val view: ProjectView = chosenView?.let { name -> ProjectView.values().firstOrNull { it.name == name } }
        ?: viewDefaults[projectId]
        ?: ProjectView.BOARD
    val viewLabel = stringResource(if (view == ProjectView.BOARD) R.string.view_board else R.string.view_list)
    val savedMessage = stringResource(R.string.view_default_saved, viewLabel)
    fun chooseView(newView: ProjectView) {
        chosenView = newView.name
        // "Remember the last view": the choice also becomes this project's default.
        if (rememberLast && projectId != 0L) viewPrefs.setDefault(projectId, newView)
    }

    // Android's file pickers: the app never needs storage permission.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri -> if (uri != null) viewModel.exportTo(uri) }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> if (uri != null) dialog = BoardDialog.ConfirmImport(uri) }

    // Details work for any open task of any project (they are opened from Today as well).
    val detailsTask = detailsId?.let { id -> everyTask.firstOrNull { it.id == id } }
    // A task that disappeared (closed, deleted, got its server id) must not leave a dead "open" flag.
    LaunchedEffect(detailsTask == null) { if (detailsTask == null) detailsId = null }

    // Back closes whatever is on top: settings, closed tasks, search, then Projects -> Today.
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showClosed && !showSettings) { showClosed = false }
    BackHandler(enabled = searching && !showClosed && !showSettings) {
        searching = false
        query = ""
    }
    BackHandler(enabled = tab == TAB_PROJECTS && !searching && !showClosed && !showSettings) { tab = TAB_TODAY }

    if (showSettings) {
        SettingsScreen(
            themePrefs = themePrefs,
            viewPrefs = viewPrefs,
            viewModel = viewModel,
            snackbar = snackbar,
            onBack = { showSettings = false },
        )
        return
    }
    if (showClosed) {
        ClosedTasksScreen(viewModel = viewModel, snackbar = snackbar, onBack = { showClosed = false })
        return
    }

    // The projects that are on screen right now (archived ones are not in the list).
    val projectNames = remember(projects) { projects.associate { it.id to it.name } }
    val todayTasks = remember(everyTask, projectNames) { everyTask.filter { it.projectId in projectNames } }
    val today = DueDates.today()
    val groups = remember(todayTasks, today) { groupToday(todayTasks, today, DueDates.plusDays(today, 7)) }

    // Search narrows the open tasks of the project by title or description.
    val needle = query.trim()
    val tasks = remember(projectTasks, needle) {
        if (needle.isEmpty()) projectTasks
        else projectTasks.filter {
            it.title.contains(needle, ignoreCase = true) || it.description.contains(needle, ignoreCase = true)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    if (tab == TAB_TODAY) {
                        Text(stringResource(R.string.tab_today))
                    } else if (searching) {
                        TextField(
                            value = query,
                            onValueChange = { query = it },
                            singleLine = true,
                            textStyle = androidx.compose.material3.LocalTextStyle.current.copy(
                                textDirection = androidx.compose.ui.text.style.TextDirection.Content,
                            ),
                            placeholder = { Text(stringResource(R.string.search_hint)) },
                            colors = TextFieldDefaults.colors(
                                focusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                                unfocusedContainerColor = androidx.compose.ui.graphics.Color.Transparent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Box {
                            Row(
                                modifier = Modifier.clickable { projectMenuOpen = true },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = project?.name ?: stringResource(R.string.app_name),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                Icon(Icons.Filled.ArrowDropDown, contentDescription = stringResource(R.string.menu_projects))
                            }
                            DropdownMenu(expanded = projectMenuOpen, onDismissRequest = { projectMenuOpen = false }) {
                                projects.forEach { p ->
                                    DropdownMenuItem(
                                        text = { Text(p.name) },
                                        onClick = {
                                            projectMenuOpen = false
                                            viewModel.selectProject(p.id)
                                        },
                                    )
                                }
                                if (projects.isNotEmpty()) HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.project_new)) },
                                    onClick = {
                                        projectMenuOpen = false
                                        dialog = BoardDialog.NewProject
                                    },
                                )
                            }
                        }
                    }
                },
                actions = {
                    if (searching && tab == TAB_PROJECTS) {
                        IconButton(onClick = { searching = false; query = "" }) {
                            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close))
                        }
                    } else {
                        if (tab == TAB_PROJECTS && project != null) {
                            IconButton(onClick = { searching = true }) {
                                Icon(Icons.Filled.Search, contentDescription = stringResource(R.string.search_hint))
                            }
                        }
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.menu_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                if (mode != Mode.LOCAL) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_sync_now)) },
                                        onClick = { menuOpen = false; viewModel.syncNow() },
                                    )
                                }
                                if (tab == TAB_PROJECTS && project != null) {
                                    if (!rememberLast) {
                                        DropdownMenuItem(
                                            text = { Text(stringResource(R.string.view_make_default, viewLabel)) },
                                            onClick = {
                                                menuOpen = false
                                                viewPrefs.setDefault(projectId, view)
                                                scope.launch { snackbar.showSnackbar(savedMessage) }
                                            },
                                        )
                                    }
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_closed_tasks)) },
                                        onClick = { menuOpen = false; showClosed = true },
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_archive_project)) },
                                        onClick = { menuOpen = false; dialog = BoardDialog.ConfirmArchive },
                                    )
                                }
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            stringResource(
                                                if (mode == Mode.LOCAL) R.string.menu_export_backup
                                                else R.string.menu_export_snapshot,
                                            ),
                                        )
                                    },
                                    onClick = {
                                        menuOpen = false
                                        exportLauncher.launch("kancard-backup.json")
                                    },
                                )
                                if (mode == Mode.LOCAL) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(R.string.menu_import_backup)) },
                                        onClick = {
                                            menuOpen = false
                                            importLauncher.launch(arrayOf("*/*"))
                                        },
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.menu_settings)) },
                                    onClick = { menuOpen = false; showSettings = true },
                                )
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == TAB_TODAY,
                    onClick = { tab = TAB_TODAY },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_today)) },
                )
                NavigationBarItem(
                    selected = tab == TAB_PROJECTS,
                    onClick = { tab = TAB_PROJECTS },
                    icon = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_projects)) },
                )
            }
        },
        floatingActionButton = {
            if (tab == TAB_PROJECTS && project != null && columns.isNotEmpty()) {
                FloatingActionButton(onClick = {
                    val column = columns.getOrNull(pagerState.currentPage) ?: columns.first()
                    dialog = BoardDialog.NewTask(column.id)
                }) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.task_new))
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (busy || syncState == SyncState.SYNCING) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            if (mode != Mode.LOCAL) {
                SyncBanner(
                    state = syncState,
                    pending = pendingCount,
                    onUpdateToken = { dialog = BoardDialog.UpdateToken },
                    onReview = { viewModel.reviewCertificate() },
                )
            }
            if (tab == TAB_TODAY) {
                TodayView(
                    viewModel = viewModel,
                    byProject = todayByProject,
                    onByProjectChange = { viewPrefs.setTodayByProject(it) },
                    groups = groups,
                    projectNames = projectNames,
                    pendingIds = pendingIds,
                    subtaskCounts = subtaskCounts,
                    refreshing = refreshing,
                    onRefresh = { viewModel.syncNow() },
                    onOpen = { detailsId = it.id },
                    onDone = { viewModel.closeTask(it) },
                )
            } else {
                when {
                    !loaded -> Unit
                    project == null -> EmptyProjects(onCreate = { dialog = BoardDialog.NewProject })
                    columns.isEmpty() -> Text(
                        stringResource(R.string.board_no_columns),
                        modifier = Modifier.padding(24.dp),
                    )
                    else -> Column(modifier = Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            FilterChip(
                                selected = view == ProjectView.BOARD,
                                onClick = { chooseView(ProjectView.BOARD) },
                                label = { Text(stringResource(R.string.view_board)) },
                            )
                            FilterChip(
                                selected = view == ProjectView.LIST,
                                onClick = { chooseView(ProjectView.LIST) },
                                label = { Text(stringResource(R.string.view_list)) },
                            )
                        }
                        if (view == ProjectView.BOARD) {
                            BoardPager(
                                columns = columns,
                                tasks = tasks,
                                pendingIds = pendingIds,
                                // Dragging a card in a filtered list would drop it at a misleading place.
                                dragEnabled = needle.isEmpty(),
                                showDescriptions = look.showDescriptions,
                                subtaskCounts = subtaskCounts,
                                pagerState = pagerState,
                                refreshing = refreshing,
                                onRefresh = { viewModel.refreshCurrent() },
                                onEdit = { dialog = BoardDialog.EditTask(it) },
                                onDetails = { detailsId = it.id },
                                onQuickAdd = { columnId, title -> viewModel.quickAdd(columnId, title) },
                                onMove = { task, columnId, before -> viewModel.moveTask(task, columnId, before) },
                                onClose = { viewModel.closeTask(it) },
                            )
                        } else {
                            ProjectListView(
                                viewModel = viewModel,
                                columns = columns,
                                tasks = tasks,
                                pendingIds = pendingIds,
                                subtaskCounts = subtaskCounts,
                                refreshing = refreshing,
                                onRefresh = { viewModel.refreshCurrent() },
                                onOpen = { detailsId = it.id },
                                dragEnabled = needle.isEmpty(),
                            )
                        }
                    }
                }
            }
        }
    }

    // Task details: a sheet that slides up over the board or the Today list.
    if (detailsTask != null) {
        TaskDetailsSheet(
            viewModel = viewModel,
            task = detailsTask,
            snackbar = snackbar,
            onEdit = {
                detailsId = null
                dialog = BoardDialog.EditTask(detailsTask)
            },
            onDismiss = { detailsId = null },
        )
    }

    // Certificate that changed since it was trusted (opened from the banner's "Review").
    certReview?.let { state ->
        CertificateDialog(
            state = state,
            onTrust = { viewModel.trustReviewedCertificate(state) },
            onDismiss = { viewModel.dismissCertReview() },
            changed = true,
        )
    }

    // ---- Pop-ups ---------------------------------------------------------------------------------
    when (val d = dialog) {
        null -> Unit

        is BoardDialog.NewTask -> TaskDialog(
            initial = null,
            onDismiss = { dialog = null },
            onSave = { title, description, due, colorId, priority ->
                viewModel.createTask(d.columnId, title, description, due, colorId, priority)
                dialog = null
            },
            onClose = null,
        )

        is BoardDialog.EditTask -> TaskDialog(
            initial = d.task,
            onDismiss = { dialog = null },
            onSave = { title, description, due, colorId, priority ->
                viewModel.updateTask(d.task, title, description, due, colorId, priority)
                dialog = null
            },
            onClose = {
                viewModel.closeTask(d.task)
                dialog = null
            },
        )

        BoardDialog.NewProject -> TextInputDialog(
            title = stringResource(R.string.project_new),
            label = stringResource(R.string.field_project_name),
            confirmLabel = stringResource(R.string.action_create),
            onDismiss = { dialog = null },
            onConfirm = { name ->
                viewModel.createProject(name)
                dialog = null
            },
        )

        BoardDialog.ConfirmArchive -> ConfirmDialog(
            title = stringResource(R.string.archive_title),
            message = stringResource(
                if (mode == Mode.LOCAL) R.string.archive_message_local else R.string.archive_message_server,
            ),
            confirmLabel = stringResource(R.string.menu_archive_project),
            onDismiss = { dialog = null },
            onConfirm = {
                viewModel.archiveCurrentProject()
                dialog = null
            },
        )

        BoardDialog.UpdateToken -> TokenDialog(
            onDismiss = { dialog = null },
            onConfirm = { token ->
                viewModel.updateToken(token)
                dialog = null
            },
        )

        is BoardDialog.ConfirmImport -> ConfirmDialog(
            title = stringResource(R.string.import_title),
            message = stringResource(R.string.import_message),
            confirmLabel = stringResource(R.string.import_confirm),
            onDismiss = { dialog = null },
            onConfirm = {
                viewModel.importFrom(d.uri)
                dialog = null
            },
        )
    }
}

/** Remembered tab number; the app opens on Today. */
private fun mutableIntStateOfToday() = mutableStateOf(TAB_TODAY)

/**
 * Thin strip under the title bar that tells the truth about syncing:
 * offline, waiting changes, login or certificate problems. Hidden when all is well.
 */
@Composable
private fun SyncBanner(
    state: SyncState,
    pending: Int,
    onUpdateToken: () -> Unit,
    onReview: () -> Unit,
) {
    // Text to show (null = nothing), and an optional button.
    val text: String? = when (state) {
        SyncState.LOGIN -> stringResource(R.string.banner_login)
        SyncState.CERTIFICATE -> stringResource(R.string.banner_certificate)
        SyncState.ERROR -> stringResource(R.string.banner_error)
        SyncState.OFFLINE ->
            if (pending > 0) stringResource(R.string.banner_offline_pending, pending)
            else stringResource(R.string.banner_offline)
        SyncState.SYNCING -> if (pending > 0) stringResource(R.string.banner_pending, pending) else null
        SyncState.IDLE -> if (pending > 0) stringResource(R.string.banner_pending, pending) else null
    }
    if (text == null) return

    val problem = state == SyncState.LOGIN || state == SyncState.CERTIFICATE || state == SyncState.ERROR
    Surface(
        color = if (problem) MaterialTheme.colorScheme.errorContainer
        else MaterialTheme.colorScheme.secondaryContainer,
        contentColor = if (problem) MaterialTheme.colorScheme.onErrorContainer
        else MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            when (state) {
                SyncState.LOGIN -> TextButton(onClick = onUpdateToken) {
                    Text(stringResource(R.string.action_update_token))
                }
                SyncState.CERTIFICATE -> TextButton(onClick = onReview) {
                    Text(stringResource(R.string.action_review))
                }
                else -> Unit
            }
        }
    }
}

/** Shown when there is no project yet. */
@Composable
private fun EmptyProjects(onCreate: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.board_no_projects), modifier = Modifier.padding(bottom = 16.dp))
        Button(onClick = onCreate) { Text(stringResource(R.string.project_new)) }
    }
}
