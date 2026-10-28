package com.mmbesar.kancard.ui

import android.app.Application
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import androidx.annotation.StringRes
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mmbesar.kancard.KanCardApplication
import com.mmbesar.kancard.R
import com.mmbesar.kancard.data.ColumnEntity
import com.mmbesar.kancard.data.CommentEntity
import com.mmbesar.kancard.data.ProjectEntity
import com.mmbesar.kancard.data.SyncResult
import com.mmbesar.kancard.data.SubtaskCount
import com.mmbesar.kancard.data.SubtaskEntity
import com.mmbesar.kancard.data.SyncState
import com.mmbesar.kancard.data.TaskEntity
import com.mmbesar.kancard.network.ApiException
import com.mmbesar.kancard.network.CertFetcher
import com.mmbesar.kancard.network.CertInfo
import com.mmbesar.kancard.network.ServerAddress
import com.mmbesar.kancard.security.Connection
import com.mmbesar.kancard.security.Mode
import com.mmbesar.kancard.widget.BoardWidget
import com.mmbesar.kancard.widget.RefreshWorker
import com.mmbesar.kancard.widget.WidgetPrefs
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Progress of "Test and connect" on the first-start screen. */
sealed interface ConnectState {
    data object Idle : ConnectState
    data object Working : ConnectState
    data class Error(val message: String) : ConnectState

    /** The server uses a certificate Android does not know: ask the user whether to trust it. */
    data class ConfirmCertificate(val info: CertInfo, val draft: Connection) : ConnectState
}

/** How safe the current server connection is, for the "Connection" dialog. */
enum class Security { HTTPS, HTTPS_PINNED, HTTP }

/** Server details that are safe to show (never the token). */
data class ConnectionSummary(
    val address: String,
    val username: String,
    val security: Security,
    val pinnedSha256: String?,
)

/**
 * Holds what the screens show and performs their actions. Screens talk only to
 * this class, never to the network or database directly.
 *
 * Offline-first: actions change the phone's database at once and return; the
 * sync (see [requestSync]) sends them to the server in the background.
 */
class BoardViewModel(app: Application) : AndroidViewModel(app) {

    private val container = app as KanCardApplication
    private val repo = container.repository
    private val store = container.credentialStore

    // ---- State the screens watch -------------------------------------------------------

    private val _mode = MutableStateFlow(repo.mode)
    val mode: StateFlow<Mode> = _mode.asStateFlow()

    private val _selectedProjectId = MutableStateFlow(store.selectedProjectId)

    private val _projectsLoaded = MutableStateFlow(false)

    /** false until the first answer from the database (avoids a flash of "no projects"). */
    val projectsLoaded: StateFlow<Boolean> = _projectsLoaded.asStateFlow()

    val projects: StateFlow<List<ProjectEntity>> = repo.observeProjects()
        .onEach { _projectsLoaded.value = true }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** The project on screen: the chosen one, or the first one if the choice is gone. */
    val currentProject: StateFlow<ProjectEntity?> =
        combine(projects, _selectedProjectId) { list, id ->
            // The saved choice is the second try: a project made offline changes its id when the
            // server creates it, and the saved choice is already the new one at that moment.
            list.firstOrNull { it.id == id }
                ?: list.firstOrNull { it.id == store.selectedProjectId }
                ?: list.firstOrNull()
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val columns: StateFlow<List<ColumnEntity>> = currentProject.map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeColumns(id) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val openTasks: StateFlow<List<TaskEntity>> = currentProject.map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeTasks(id, open = true) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    @OptIn(ExperimentalCoroutinesApi::class)
    val closedTasks: StateFlow<List<TaskEntity>> = currentProject.map { it?.id }
        .distinctUntilChanged()
        .flatMapLatest { id -> if (id == null) flowOf(emptyList()) else repo.observeTasks(id, open = false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Open tasks of ALL projects (the Today screen). */
    val allOpenTasks: StateFlow<List<TaskEntity>> = repo.observeAllOpenTasks()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** How many changes still wait for the server, and which tasks they belong to. */
    val pendingCount: StateFlow<Int> = repo.observePendingCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val pendingTaskIds: StateFlow<Set<Long>> = repo.observePendingTaskIds()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** "2 of 5 subtasks done" per task, for the cards. */
    val subtaskCounts: StateFlow<Map<Long, SubtaskCount>> = repo.observeSubtaskCounts()
        .map { list -> list.associateBy { it.taskId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** What the sync indicator shows (offline, syncing, login problem ...). */
    val syncState: StateFlow<SyncState> = repo.syncState

    private val _busy = MutableStateFlow(false)

    /** true while an action that needs the server is running (the UI shows a thin progress bar). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _refreshing = MutableStateFlow(false)

    /** true while a pull-to-refresh sync runs. */
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _connectState = MutableStateFlow<ConnectState>(ConnectState.Idle)
    val connectState: StateFlow<ConnectState> = _connectState.asStateFlow()

    /** True while the setup screen is shown to EDIT the saved server address / user. */
    private val _editingConnection = MutableStateFlow(false)
    val editingConnection: StateFlow<Boolean> = _editingConnection.asStateFlow()

    fun startEditConnection() {
        _connectState.value = ConnectState.Idle
        _editingConnection.value = true
    }

    fun cancelEditConnection() {
        _connectState.value = ConnectState.Idle
        _editingConnection.value = false
    }

    private val _certReview = MutableStateFlow<ConnectState.ConfirmCertificate?>(null)

    /** Set while the user is asked whether to trust a renewed server certificate. */
    val certReview: StateFlow<ConnectState.ConfirmCertificate?> = _certReview.asStateFlow()

    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)

    /** One-off messages to show in a snackbar. */
    val events: SharedFlow<String> = _events.asSharedFlow()

    // ---- Waking up the sync -------------------------------------------------------------------------

    private val connectivity = app.getSystemService(ConnectivityManager::class.java)

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        // A network (re)appeared, also right after registering: sync everything.
        override fun onAvailable(network: Network) {
            viewModelScope.launch { requestSync(full = true) }
        }
    }

    init {
        try {
            connectivity?.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                networkCallback,
            )
        } catch (e: Exception) {
            // No connectivity service: the sync still runs on every change and on pull-to-refresh.
        }
        // A project made offline got its real id from the server: keep showing it.
        viewModelScope.launch {
            repo.projectRemapped.collect { (oldId, newId) ->
                if (_selectedProjectId.value == oldId) _selectedProjectId.value = newId
                // Widgets and the remembered view of that project follow it to its new id.
                WidgetPrefs.remapProject(getApplication<Application>(), oldId, newId)
                container.viewPrefs.moveDefault(oldId, newId)
                updateWidgets()
            }
        }
        // Whenever another project comes on screen, bring its content up to date.
        viewModelScope.launch {
            currentProject.map { it?.id }.distinctUntilChanged().collect { id ->
                if (id != null) requestSync()
            }
        }
    }

    override fun onCleared() {
        try {
            connectivity?.unregisterNetworkCallback(networkCallback)
        } catch (e: Exception) {
            // Already gone.
        }
        super.onCleared()
    }

    // ---- First start: connect or go local -----------------------------------------------------

    /** Tests the login; asks about the certificate if Android does not trust it. */
    fun connect(rawAddress: String, username: String, token: String) {
        attemptConnect(rawAddress, username.trim(), token.trim(), pinnedSha256 = null)
    }

    /** The user looked at the fingerprint and said "trust it". */
    fun confirmCertificate(state: ConnectState.ConfirmCertificate) {
        val draft = state.draft
        attemptConnect(draft.endpoint, draft.username, draft.token, pinnedSha256 = state.info.sha256)
    }

    fun dismissConnectMessage() {
        _connectState.value = ConnectState.Idle
    }

    private fun attemptConnect(rawAddress: String, username: String, token: String, pinnedSha256: String?) {
        viewModelScope.launch {
            _connectState.value = ConnectState.Working

            val parsed = ServerAddress.parse(rawAddress)
            if (parsed == null) {
                _connectState.value = ConnectState.Error(str(R.string.error_bad_address))
                return@launch
            }
            if (username.isEmpty() || token.isEmpty()) {
                _connectState.value = ConnectState.Error(str(R.string.error_missing_login))
                return@launch
            }

            val draft = Connection(parsed.endpoint, username, token, pinnedSha256)
            try {
                repo.testConnection(draft)
                repo.connect(draft)
                _selectedProjectId.value = 0L
                _mode.value = Mode.KANBOARD
                _connectState.value = ConnectState.Idle
                _editingConnection.value = false
                requestSync(full = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                val untrusted = e.kind == ApiException.Kind.CERTIFICATE
                if (untrusted && pinnedSha256 == null && !parsed.isCleartext) {
                    // Offer to trust the certificate instead of just failing.
                    _connectState.value = try {
                        ConnectState.ConfirmCertificate(CertFetcher.fetch(parsed.host, parsed.port), draft)
                    } catch (fetchError: Exception) {
                        ConnectState.Error(describe(e))
                    }
                } else if (untrusted && pinnedSha256 != null) {
                    _connectState.value = ConnectState.Error(str(R.string.error_certificate_changed))
                } else {
                    _connectState.value = ConnectState.Error(describe(e))
                }
            } catch (e: Exception) {
                _connectState.value = ConnectState.Error(describe(e))
            }
        }
    }

    fun startLocal() = onlineOp {
        repo.startLocal(str(R.string.starter_project_name))
        _selectedProjectId.value = 0L
        _mode.value = Mode.LOCAL
    }

    /** Forget this workspace on the phone and go back to the first-start screen. */
    fun resetMode() = onlineOp {
        repo.reset()
        _selectedProjectId.value = 0L
        _connectState.value = ConnectState.Idle
        _editingConnection.value = false
        _mode.value = Mode.UNSET
    }

    // ---- Recovering from a changed certificate or a rejected token --------------------------------------

    /** Fetches the server's current certificate so the user can approve it. */
    fun reviewCertificate() {
        val connection = repo.currentConnection() ?: return
        val parsed = ServerAddress.parse(connection.endpoint) ?: return
        if (parsed.isCleartext) return
        viewModelScope.launch {
            try {
                val info = CertFetcher.fetch(parsed.host, parsed.port)
                _certReview.value = ConnectState.ConfirmCertificate(info, connection)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(str(R.string.error_network))
            }
        }
    }

    fun trustReviewedCertificate(state: ConnectState.ConfirmCertificate) {
        repo.updatePin(state.info.sha256)
        _certReview.value = null
        requestSync(full = true, loud = true)
    }

    fun dismissCertReview() {
        _certReview.value = null
    }

    fun updateToken(token: String) = onlineOp {
        repo.updateToken(token)
        requestSync(full = true, loud = true)
    }

    // ---- Board actions (saved at once, sent to the server in the background) --------------------------------

    fun selectProject(projectId: Long) {
        _selectedProjectId.value = projectId
        store.selectedProjectId = projectId
    }

    /** Pull-to-refresh: send what is waiting, then download the current project. */
    fun refreshCurrent() = requestSync(loud = true)

    /** "Sync now" in the menu: everything. */
    fun syncNow() = requestSync(full = true, loud = true)

    fun createTask(
        columnId: Long,
        title: String,
        description: String,
        dueDate: String?,
        colorId: String,
        priority: Int,
    ) = localOp {
        val project = currentProject.value ?: return@localOp
        repo.createTask(project.id, columnId, title, description, dueDate, colorId, priority)
    }

    fun updateTask(
        task: TaskEntity,
        title: String,
        description: String,
        dueDate: String?,
        colorId: String,
        priority: Int,
    ) = localOp { repo.updateTask(task, title, description, dueDate, colorId, priority) }

    /** Moves [task] to [columnId], right before [beforeTaskId] (null = at the bottom). */
    fun moveTask(task: TaskEntity, columnId: Long, beforeTaskId: Long?) =
        localOp { repo.moveTask(task, columnId, beforeTaskId) }

    // ---- Subtasks and comments (saved on the phone, sent to the server in the background) ----

    /** The columns of any project (the details sheet offers them for moving a task). */
    fun columnsFor(projectId: Long): Flow<List<ColumnEntity>> = repo.observeColumns(projectId)

    fun subtasks(taskId: Long): Flow<List<SubtaskEntity>> = repo.observeSubtasks(taskId)

    fun comments(taskId: Long): Flow<List<CommentEntity>> = repo.observeComments(taskId)

    /** Called when the details screen opens: updates the cached copy if the server can be reached. */
    fun refreshDetails(task: TaskEntity) {
        viewModelScope.launch {
            try {
                repo.refreshDetails(task)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Offline or refused: the cached copy stays, nothing to report.
            }
        }
    }

    fun addSubtask(task: TaskEntity, title: String) = detailOp(task) { repo.addSubtask(task, title) }

    /** Tapping a subtask: to do / in progress -> done, done -> to do. */
    fun toggleSubtask(task: TaskEntity, subtask: SubtaskEntity) =
        detailOp(task) { repo.setSubtaskStatus(subtask, if (subtask.status == 2) 0 else 2) }

    fun deleteSubtask(task: TaskEntity, subtask: SubtaskEntity) =
        detailOp(task) { repo.deleteSubtask(subtask) }

    fun addComment(task: TaskEntity, text: String) =
        detailOp(task) { repo.addComment(task, text, str(R.string.comment_author_me)) }

    fun deleteComment(task: TaskEntity, comment: CommentEntity) =
        detailOp(task) { repo.deleteComment(comment) }

    /** Subtasks and comments are saved on the phone at once and sent later, like everything else. */
    private fun detailOp(@Suppress("UNUSED_PARAMETER") task: TaskEntity, block: suspend () -> Unit) = localOp(block)

    /** One-line "add a task" under a column: just a title, everything else default. */
    fun quickAdd(columnId: Long, title: String) = createTask(columnId, title, "", null, "", 0)

    fun closeTask(task: TaskEntity) = localOp { repo.setTaskClosed(task, closed = true) }

    fun reopenTask(task: TaskEntity) = localOp { repo.setTaskClosed(task, closed = false) }

    fun deleteTask(task: TaskEntity) = localOp { repo.deleteTask(task) }

    // Projects too: made and archived on the phone first, the server hears about it when it can.

    fun createProject(name: String) = localOp {
        repo.createProject(name)
        // The repository chose the new project (and keeps that choice right if the id changes later).
        _selectedProjectId.value = store.selectedProjectId
    }

    fun archiveCurrentProject() = localOp {
        val project = currentProject.value ?: return@localOp
        repo.archiveProject(project.id)
        selectProject(0L)
    }

    // ---- Backups ------------------------------------------------------------------------------------------

    /** Writes the backup (local mode) or server snapshot (Kanboard mode) to a file the user picked. */
    fun exportTo(uri: Uri) = onlineOp {
        val text = if (repo.mode == Mode.LOCAL) repo.exportLocalBackup() else repo.exportServerSnapshot()
        withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openOutputStream(uri, "wt")
                ?: throw IllegalArgumentException(str(R.string.error_file_write))
            stream.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        }
        _events.tryEmit(str(R.string.message_exported))
    }

    /** Restores a backup file (local mode only). Replaces everything after validation. */
    fun importFrom(uri: Uri) = onlineOp {
        val text = withContext(Dispatchers.IO) {
            val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IllegalArgumentException(str(R.string.error_file_read))
            stream.use { input ->
                val bytes = input.readBytes()
                if (bytes.size > MAX_BACKUP_BYTES) {
                    throw IllegalArgumentException(str(R.string.error_file_too_big))
                }
                String(bytes, Charsets.UTF_8)
            }
        }
        repo.importLocalBackup(text)
        _selectedProjectId.value = 0L
        _events.tryEmit(str(R.string.message_imported))
    }

    // ---- Connection details for the dialog --------------------------------------------------------------------

    fun connectionSummary(): ConnectionSummary? {
        val c = repo.currentConnection() ?: return null
        val security = when {
            c.isCleartext -> Security.HTTP
            c.pinnedSha256 != null -> Security.HTTPS_PINNED
            else -> Security.HTTPS
        }
        return ConnectionSummary(c.endpoint, c.username, security, c.pinnedSha256)
    }

    // ---- The sync scheduler ---------------------------------------------------------------------------------------

    // Everything below runs on the main thread, so these plain variables need no locking.
    private var syncRunning = false
    private var syncAgain = false
    private var wantFull = false
    private var wantLoud = false

    /**
     * Asks for a sync. If one is already running, another run follows it (several
     * quick changes cause one extra run, not one each).
     * [full] = download all projects; [loud] = the user asked, so problems are shown.
     */
    private fun requestSync(full: Boolean = false, loud: Boolean = false) {
        if (repo.mode != Mode.KANBOARD) return
        wantFull = wantFull || full
        wantLoud = wantLoud || loud
        if (syncRunning) {
            syncAgain = true
            return
        }
        syncRunning = true
        viewModelScope.launch {
            try {
                do {
                    syncAgain = false
                    val downloadAll = wantFull
                    val showProblems = wantLoud
                    wantFull = false
                    wantLoud = false

                    if (showProblems) _refreshing.value = true
                    val ids = if (downloadAll) null else listOfNotNull(currentProject.value?.id)
                    val result = repo.sync(ids, force = showProblems)
                    _refreshing.value = false

                    report(result, showProblems)
                    updateWidgets()
                } while (syncAgain)
            } finally {
                syncRunning = false
                _refreshing.value = false
            }
        }
    }

    private fun report(result: SyncResult, loud: Boolean) {
        if (result.dropped > 0) _events.tryEmit(str(R.string.sync_dropped, result.dropped))
        val error = result.error ?: return
        // A wrong login or a changed certificate has its own banner with a button.
        val hasBanner = error.kind == ApiException.Kind.UNAUTHORIZED || error.kind == ApiException.Kind.CERTIFICATE
        if (loud && !hasBanner) _events.tryEmit(describe(error))
    }

    // ---- Helpers --------------------------------------------------------------------------------------------------

    /** A change saved on the phone at once; the sync sends it later. Shows no progress bar. */
    private fun localOp(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                updateWidgets()
                if (repo.mode == Mode.KANBOARD) {
                    // Also hand it to Android's job scheduler: it sends the change when the
                    // connection returns, even if the app is closed by then.
                    RefreshWorker.syncSoon(getApplication<Application>())
                    requestSync()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(describe(e))
            }
        }
    }

    /** An action that needs the server (or replaces everything): shows a progress bar, reports failures. */
    private fun onlineOp(block: suspend () -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            try {
                block()
                updateWidgets()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(describe(e))
            } finally {
                _busy.value = false
            }
        }
    }

    private suspend fun updateWidgets() {
        try {
            BoardWidget().updateAll(getApplication<Application>())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A widget that cannot redraw must never break the app.
        }
    }

    private fun str(@StringRes id: Int, vararg args: Any): String =
        getApplication<Application>().getString(id, *args)

    private fun describe(e: Throwable): String = when (e) {
        is ApiException -> when (e.kind) {
            ApiException.Kind.NETWORK -> str(R.string.error_network)
            ApiException.Kind.CERTIFICATE -> str(R.string.error_certificate)
            ApiException.Kind.UNAUTHORIZED -> str(R.string.error_unauthorized)
            ApiException.Kind.FORBIDDEN -> str(R.string.error_forbidden)
            ApiException.Kind.REJECTED -> str(R.string.error_rejected)
            ApiException.Kind.SERVER -> str(R.string.error_server, e.message.orEmpty())
            ApiException.Kind.BAD_RESPONSE -> str(R.string.error_bad_response)
            ApiException.Kind.NOT_CONFIGURED -> str(R.string.error_not_configured)
        }
        // Our own validation messages (bad backup file, ...) are already readable.
        is IllegalArgumentException -> e.message ?: str(R.string.error_unexpected)
        else -> str(R.string.error_unexpected)
    }

    private companion object {
        /** Backups larger than this are refused. */
        const val MAX_BACKUP_BYTES = 8 * 1024 * 1024
    }
}
