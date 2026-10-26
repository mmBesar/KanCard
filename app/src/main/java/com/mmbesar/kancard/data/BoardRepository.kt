package com.mmbesar.kancard.data

import com.mmbesar.kancard.network.ApiException
import com.mmbesar.kancard.network.BoardApi
import com.mmbesar.kancard.network.HttpClients
import com.mmbesar.kancard.security.Connection
import com.mmbesar.kancard.security.CredentialStore
import com.mmbesar.kancard.security.Mode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/** One line in the home-screen widget: a column title or a task title. */
data class WidgetRow(val text: String, val isHeader: Boolean)

/** What the widget shows for one project. */
data class WidgetData(val projectName: String, val rows: List<WidgetRow>)

/**
 * The single place that knows about both worlds. The big idea is OFFLINE-FIRST:
 *
 *  - Every change is written to the on-phone database IMMEDIATELY. The screens
 *    only ever read from that database, so the app works the same with or
 *    without a connection.
 *  - In Kanboard mode each change is also put in a queue ("pending_ops").
 *    [sync] sends the queue to the server in order. Only when the queue is empty
 *    does it download fresh data, so a download can never overwrite something you
 *    did offline.
 *  - If the server refuses a change for good (for example the task was deleted
 *    there meanwhile), the change is dropped and the project is downloaded again,
 *    so the screen shows what the server really has.
 *  - Local-only mode simply has no queue and no server.
 */
class BoardRepository(
    private val dao: BoardDao,
    private val store: CredentialStore,
) {
    val mode: Mode get() = store.mode

    // ---- Locks and bookkeeping ------------------------------------------------------------

    /** Guards every write that touches the queue or replaces cached data (so they never interleave). */
    private val dbLock = Mutex()

    /** Only one sync (sending + downloading) runs at a time. */
    private val syncLock = Mutex()

    /** Raised whenever the workspace is replaced (connect / switch mode). A download that started before is discarded. */
    @Volatile private var generation = 0

    /** The queue entry being sent right now. New changes to the same task get their own entry. */
    @Volatile private var inFlightOpId = 0L

    private val _syncState = MutableStateFlow(SyncState.IDLE)
    val syncState: StateFlow<SyncState> = _syncState.asStateFlow()

    // ---- What the screens watch -------------------------------------------------------------

    fun observeProjects(): Flow<List<ProjectEntity>> = dao.observeProjects()

    fun observeAllOpenTasks(): Flow<List<TaskEntity>> = dao.observeAllOpenTasks()
    fun observeColumns(projectId: Long): Flow<List<ColumnEntity>> = dao.observeColumns(projectId)
    fun observeTasks(projectId: Long, open: Boolean): Flow<List<TaskEntity>> =
        dao.observeTasks(projectId, open)

    /** How many changes are still waiting for the server. */
    fun observePendingCount(): Flow<Int> = dao.observePendingCount()

    /** Which tasks have changes waiting (they get a small "waiting" label). */
    fun observePendingTaskIds(): Flow<Set<Long>> = dao.observePendingTaskIds().map { it.toSet() }

    suspend fun pendingCount(): Int = dao.pendingCount()

    // ---- Server client (rebuilt only when the saved login changes) --------------------------

    private var cachedApi: Pair<Connection, BoardApi>? = null

    private fun buildApi(c: Connection) = BoardApi(
        client = HttpClients.create(pinnedSha256 = c.pinnedSha256, allowCleartext = c.isCleartext),
        endpoint = c.endpoint,
        username = c.username,
        token = c.token,
    )

    @Synchronized
    private fun api(): BoardApi {
        val connection = store.loadConnection()
            ?: throw ApiException(ApiException.Kind.NOT_CONFIGURED, "No server has been set up")
        cachedApi?.let { if (it.first == connection) return it.second }
        return buildApi(connection).also { cachedApi = connection to it }
    }

    @Synchronized
    private fun resetApi() {
        cachedApi = null
    }

    // ---- Setting up and switching modes ---------------------------------------------------------

    /** Checks the login without saving anything. Throws [ApiException] on failure. */
    suspend fun testConnection(connection: Connection) {
        buildApi(connection).getMe()
    }

    /** Saves a tested login, wipes any previous workspace and stores the project list. */
    suspend fun connect(connection: Connection) {
        // Download first: if this fails, nothing on the phone has been changed yet.
        val projects = buildApi(connection).getMyProjects().map { it.toEntity() }
        dbLock.withLock {
            generation++
            dao.clearAll()
            store.saveConnection(connection)
            store.mode = Mode.KANBOARD
            store.selectedProjectId = 0L
            resetApi()
            dao.upsertProjects(projects)
        }
        _syncState.value = SyncState.IDLE
    }

    /** Starts local-only mode with one starter project. */
    suspend fun startLocal(starterProjectName: String) {
        dbLock.withLock {
            generation++
            dao.clearAll()
            store.clearConnection()
            store.mode = Mode.LOCAL
            store.selectedProjectId = 0L
            resetApi()
            createLocalProject(starterProjectName)
        }
        _syncState.value = SyncState.IDLE
    }

    /** Forgets everything on this phone and returns to the first-start screen. Server data is untouched. */
    suspend fun reset() {
        dbLock.withLock {
            generation++
            dao.clearAll()
            store.clearConnection()
            store.mode = Mode.UNSET
            store.selectedProjectId = 0L
            resetApi()
        }
        _syncState.value = SyncState.IDLE
    }

    /** The saved login, for showing connection details (the token is never displayed). */
    fun currentConnection(): Connection? = store.loadConnection()

    /** The server certificate was renewed and the user approved the new one. */
    fun updatePin(sha256: String) {
        val c = store.loadConnection() ?: return
        store.saveConnection(c.copy(pinnedSha256 = sha256))
        resetApi()
        _syncState.value = SyncState.IDLE
    }

    /** New token typed by the user. It is tested before it replaces the old one. */
    suspend fun updateToken(token: String) {
        val c = store.loadConnection()
            ?: throw ApiException(ApiException.Kind.NOT_CONFIGURED, "No server has been set up")
        val updated = c.copy(token = token)
        buildApi(updated).getMe()
        store.saveConnection(updated)
        resetApi()
        _syncState.value = SyncState.IDLE
    }

    // ---- Subtasks and comments -------------------------------------------------------------------
    // These need the server (they are not queued): the cached copy can always be READ offline.

    fun observeSubtasks(taskId: Long): Flow<List<SubtaskEntity>> = dao.observeSubtasks(taskId)

    fun observeComments(taskId: Long): Flow<List<CommentEntity>> = dao.observeComments(taskId)

    fun observeSubtaskCounts(): Flow<List<SubtaskCount>> = dao.observeSubtaskCounts()

    /** Downloads the subtasks and comments of one task. Without a connection the cached copy stays. */
    suspend fun refreshDetails(task: TaskEntity) {
        if (mode != Mode.KANBOARD || task.id <= 0L) return
        val startedIn = generation
        val api = api()
        val subtasks = api.getSubtasks(task.id).map { it.toEntity(task.id) }
        val comments = api.getComments(task.id).map { it.toEntity(task.id) }
        dbLock.withLock {
            // The login was changed while downloading: these rows belong to an old workspace.
            if (generation != startedIn || dao.taskCount(task.id) == 0) return
            dao.replaceSubtasks(task.id, subtasks)
            dao.replaceComments(task.id, comments)
        }
    }

    /** After a successful change: a failing refresh must not look like a failed change (a retry would duplicate it). */
    private suspend fun refreshQuietly(task: TaskEntity) {
        try {
            refreshDetails(task)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            // The change itself worked; the list refreshes the next time the screen opens.
        }
    }

    suspend fun addSubtask(task: TaskEntity, title: String) {
        if (mode == Mode.LOCAL) {
            dbLock.withLock {
                val id = dao.nextSubtaskId()
                dao.upsertSubtasks(listOf(SubtaskEntity(id, task.id, title, 0, id.toInt())))
            }
        } else {
            requireOnServer(task)
            api().createSubtask(task.id, title)
            refreshQuietly(task)
        }
    }

    suspend fun setSubtaskStatus(subtask: SubtaskEntity, status: Int) {
        if (mode != Mode.LOCAL) api().updateSubtaskStatus(subtask.id, subtask.taskId, status)
        dbLock.withLock { dao.setSubtaskStatus(subtask.id, status) }
    }

    suspend fun deleteSubtask(subtask: SubtaskEntity) {
        if (mode != Mode.LOCAL) api().removeSubtask(subtask.id)
        dbLock.withLock { dao.deleteSubtask(subtask.id) }
    }

    /** [localAuthor] is only used in local-only mode (the server knows who you are). */
    suspend fun addComment(task: TaskEntity, text: String, localAuthor: String) {
        if (mode == Mode.LOCAL) {
            dbLock.withLock {
                val id = dao.nextCommentId()
                dao.upsertComments(
                    listOf(CommentEntity(id, task.id, localAuthor, text, System.currentTimeMillis() / 1000)),
                )
            }
        } else {
            requireOnServer(task)
            val api = api()
            api.createComment(task.id, api.myUserId(), text)
            refreshQuietly(task)
        }
    }

    suspend fun deleteComment(comment: CommentEntity) {
        if (mode != Mode.LOCAL) api().removeComment(comment.id)
        dbLock.withLock { dao.deleteComment(comment.id) }
    }

    /** Tasks created offline have no server id yet, so nothing can be attached to them. */
    private fun requireOnServer(task: TaskEntity) {
        if (task.id <= 0L) throw ApiException(ApiException.Kind.REJECTED, "The task has not reached the server yet")
    }

    // ---- Tasks: written locally at once, queued for the server -------------------------------------

    suspend fun createTask(
        projectId: Long,
        columnId: Long,
        title: String,
        description: String,
        dueDate: String?,
        colorId: String,
        priority: Int,
    ) {
        dbLock.withLock {
            val local = mode == Mode.LOCAL
            val swimlane = dao.swimlaneInColumn(columnId) ?: dao.anySwimlane(projectId) ?: 0L
            // Offline tasks get a temporary NEGATIVE id until the server gives the real one.
            val id = if (local) dao.nextTaskId() else minOf(dao.minTaskId() ?: 0L, 0L) - 1L
            val task = TaskEntity(
                id = id,
                projectId = projectId,
                columnId = columnId,
                swimlaneId = swimlane,
                title = title.trim(),
                description = description,
                position = dao.nextPosition(columnId, swimlane),
                dueDate = dueDate,
                isActive = true,
                colorId = colorId,
                priority = priority,
            )
            dao.upsertTasks(listOf(task))
            if (!local) enqueue(OpType.CREATE, id, projectId)
        }
    }

    suspend fun updateTask(
        task: TaskEntity,
        title: String,
        description: String,
        dueDate: String?,
        colorId: String,
        priority: Int,
    ) {
        dbLock.withLock {
            dao.updateTaskText(task.id, title.trim(), description, dueDate, colorId, priority)
            if (mode != Mode.LOCAL) enqueue(OpType.UPDATE, task.id, task.projectId)
        }
    }

    /**
     * Moves a task to [columnId], right before [beforeTaskId]
     * (null = at the bottom of its swimlane in that column).
     */
    suspend fun moveTask(task: TaskEntity, columnId: Long, beforeTaskId: Long?) {
        dbLock.withLock {
            val current = dao.task(task.id) ?: return@withLock
            val others = dao.openTasksIn(columnId, current.swimlaneId).map { it.id }
            dao.placeTasks(columnId, insertBefore(others, current.id, beforeTaskId))
            if (mode != Mode.LOCAL) enqueue(OpType.MOVE, current.id, current.projectId)
        }
    }

    suspend fun setTaskClosed(task: TaskEntity, closed: Boolean) {
        dbLock.withLock {
            dao.setTaskOpen(task.id, open = !closed)
            if (mode != Mode.LOCAL) enqueue(OpType.STATUS, task.id, task.projectId)
        }
    }

    suspend fun deleteTask(task: TaskEntity) {
        dbLock.withLock {
            dao.deleteTask(task.id)
            dao.deleteSubtasksOf(task.id)
            dao.deleteCommentsOf(task.id)
            if (mode != Mode.LOCAL) enqueue(OpType.DELETE, task.id, task.projectId)
        }
    }

    /**
     * Adds a change to the queue. Must be called inside [dbLock].
     * Two edits of the same kind to the same task share ONE entry, because the
     * values are read from the task when the entry is finally sent.
     */
    private suspend fun enqueue(type: OpType, taskId: Long, projectId: Long) {
        if (type == OpType.DELETE) {
            // Whatever was queued for this task is pointless now.
            dao.deleteOpsOfTask(taskId)
            // A task the server never saw (negative id) needs no delete request.
            if (taskId > 0) dao.insertOp(PendingOp(type = type.name, taskId = taskId, projectId = projectId))
            return
        }
        if (type == OpType.CREATE) {
            dao.insertOp(
                PendingOp(type = type.name, taskId = taskId, projectId = projectId, ref = "kancard-" + UUID.randomUUID()),
            )
        } else if (dao.countOps(type.name, taskId, inFlightOpId) == 0) {
            dao.insertOp(PendingOp(type = type.name, taskId = taskId, projectId = projectId))
        }
    }

    // ---- Projects (these need the server, so they need a connection) -------------------------------

    /** Returns the id of the new project. */
    suspend fun createProject(name: String): Long {
        if (mode == Mode.LOCAL) {
            return dbLock.withLock { createLocalProject(name) }
        }
        val id = api().createProject(name.trim())
        sync(listOf(id))
        return id
    }

    /** Hides a project. On a server this disables it; nothing is ever deleted. */
    suspend fun archiveProject(projectId: Long) {
        if (mode == Mode.LOCAL) {
            dbLock.withLock { dao.setProjectActive(projectId, active = false) }
        } else {
            api().disableProject(projectId)
            sync(emptyList())
        }
    }

    /** Must be called inside [dbLock]. */
    private suspend fun createLocalProject(name: String): Long {
        val projectId = dao.nextProjectId()
        val firstColumnId = dao.nextColumnId()
        val columns = LOCAL_COLUMNS.mapIndexed { index, title ->
            ColumnEntity(firstColumnId + index, projectId, title, index + 1)
        }
        dao.insertProjectWithContent(
            ProjectEntity(projectId, name.trim(), isActive = true),
            columns,
            emptyList(),
        )
        return projectId
    }

    // ---- Sync: send the queue, then download ----------------------------------------------------------

    /**
     * Sends everything that is waiting, then downloads fresh data.
     * [projectIds] = which projects to download; null = all active projects
     * (the selected one first). Never throws for connection problems: they are
     * reported in the result and in [syncState].
     */
    suspend fun sync(projectIds: Collection<Long>?): SyncResult {
        if (mode != Mode.KANBOARD) return SyncResult()
        return syncLock.withLock {
            val generationAtStart = generation
            _syncState.value = SyncState.SYNCING
            var dropped = 0
            try {
                val api = api()

                val drained = drain(api)
                dropped = drained.dropped
                if (drained.error != null) {
                    // drain() already set the state that explains why it stopped.
                    return@withLock SyncResult(dropped, drained.error)
                }

                pullProjectList(api, generationAtStart)

                val wanted = LinkedHashSet<Long>()
                if (projectIds == null) {
                    val selected = store.selectedProjectId
                    val active = dao.allProjects().filter { it.isActive }.map { it.id }
                    if (selected in active) wanted += selected
                    wanted += active
                } else {
                    wanted += projectIds
                }
                wanted += drained.projects
                for (id in wanted) {
                    if (dao.project(id)?.isActive == true) pullProject(api, id, generationAtStart)
                }

                _syncState.value = SyncState.IDLE
                SyncResult(dropped)
            } catch (e: CancellationException) {
                _syncState.value = SyncState.IDLE
                throw e
            } catch (e: ApiException) {
                _syncState.value = stateFor(e)
                SyncResult(dropped, e)
            } catch (e: Exception) {
                _syncState.value = SyncState.ERROR
                SyncResult(dropped, ApiException(ApiException.Kind.SERVER, e.message.orEmpty(), e))
            }
        }
    }

    private fun stateFor(e: ApiException): SyncState = when (e.kind) {
        ApiException.Kind.NETWORK -> SyncState.OFFLINE
        ApiException.Kind.CERTIFICATE -> SyncState.CERTIFICATE
        ApiException.Kind.UNAUTHORIZED -> SyncState.LOGIN
        else -> SyncState.ERROR
    }

    /** Sends the queue, oldest first. Stops at the first problem that retrying later may fix. */
    private suspend fun drain(api: BoardApi): DrainResult {
        var dropped = 0
        val touchedProjects = mutableSetOf<Long>()
        while (true) {
            val op = dbLock.withLock {
                dao.firstOp()?.also { inFlightOpId = it.id }
            } ?: break
            try {
                executeOp(api, op)
                dbLock.withLock { dao.deleteOp(op.id) }
            } catch (e: ApiException) {
                val temporary = e.kind == ApiException.Kind.NETWORK ||
                    e.kind == ApiException.Kind.CERTIFICATE ||
                    e.kind == ApiException.Kind.UNAUTHORIZED ||
                    e.kind == ApiException.Kind.NOT_CONFIGURED
                if (temporary) {
                    // Keep the change; it is sent when the problem is gone.
                    _syncState.value = stateFor(e)
                    return DrainResult(dropped, touchedProjects, e)
                }
                // The server answered, but said no. Refusals are final; odd server errors get 3 tries.
                val giveUp = e.kind == ApiException.Kind.REJECTED ||
                    e.kind == ApiException.Kind.FORBIDDEN ||
                    op.attempts >= 2
                dbLock.withLock {
                    if (giveUp) {
                        dao.deleteOp(op.id)
                        // A task that could not be created leaves nothing else to send.
                        if (op.type == OpType.CREATE.name) dao.deleteOpsOfTask(op.taskId)
                    } else {
                        dao.bumpAttempts(op.id)
                    }
                }
                if (!giveUp) {
                    _syncState.value = SyncState.ERROR
                    return DrainResult(dropped, touchedProjects, e)
                }
                dropped++
                touchedProjects += op.projectId
            } finally {
                inFlightOpId = 0L
            }
        }
        return DrainResult(dropped, touchedProjects, null)
    }

    /** Sends one queued change. The values come from the task's CURRENT local state. */
    private suspend fun executeOp(api: BoardApi, op: PendingOp) {
        when (OpType.valueOf(op.type)) {
            OpType.CREATE -> {
                val task = dao.task(op.taskId) ?: return
                // A retry after a dropped connection must not create the task twice.
                val newId = api.findTaskIdByReference(task.projectId, op.ref)
                    ?: api.createTask(
                        task.projectId, task.columnId, task.swimlaneId, task.title,
                        task.description, task.dueDate.orEmpty(), task.colorId, task.priority, op.ref,
                    )
                dbLock.withLock {
                    if (dao.task(task.id) != null) {
                        // Swap the temporary id for the real one, also in the queued changes.
                        dao.remapCreatedTask(task.id, newId)
                    } else {
                        // The task was deleted while it was being created: remove it on the server too.
                        dao.insertOp(PendingOp(type = OpType.DELETE.name, taskId = newId, projectId = task.projectId))
                    }
                }
            }

            OpType.UPDATE -> {
                if (op.taskId < 0) return
                val task = dao.task(op.taskId) ?: return
                api.updateTask(task.id, task.title, task.description, task.dueDate.orEmpty(), task.colorId, task.priority)
            }

            OpType.MOVE -> {
                if (op.taskId < 0) return
                val task = dao.task(op.taskId) ?: return
                if (!task.isActive) return
                val position = dao.openTasksIn(task.columnId, task.swimlaneId)
                    .indexOfFirst { it.id == task.id }
                if (position < 0) return
                api.moveTaskPosition(task.projectId, task.id, task.columnId, position + 1, task.swimlaneId)
            }

            OpType.STATUS -> {
                if (op.taskId < 0) return
                val task = dao.task(op.taskId) ?: return
                if (task.isActive) api.openTask(task.id) else api.closeTask(task.id)
            }

            OpType.DELETE -> {
                try {
                    api.removeTask(op.taskId)
                } catch (e: ApiException) {
                    // "Refused" usually means it is already gone, which is what we wanted.
                    if (e.kind != ApiException.Kind.REJECTED) throw e
                }
            }
        }
    }

    private suspend fun pullProjectList(api: BoardApi, generationAtStart: Int) {
        val projects = api.getMyProjects().map { it.toEntity() }
        dbLock.withLock {
            // Skipped when the workspace was replaced meanwhile, or when you made new changes
            // while downloading (they are sent first on the next sync).
            if (generationAtStart == generation && dao.pendingCount() == 0) {
                dao.replaceProjectList(projects)
            }
        }
    }

    private suspend fun pullProject(api: BoardApi, projectId: Long, generationAtStart: Int) {
        val columns = api.getColumns(projectId).map { it.toEntity(projectId) }
        val open = api.getAllTasks(projectId, open = true)
        val closed = api.getAllTasks(projectId, open = false)
        val tasks = (open + closed).map { it.toEntity().copy(projectId = projectId) }
        dbLock.withLock {
            if (generationAtStart == generation && dao.pendingCount() == 0) {
                dao.replaceProjectContent(projectId, columns, tasks)
            }
        }
    }

    // ---- Backups ------------------------------------------------------------------------------------------

    /** Everything stored on the phone as a backup file. */
    suspend fun exportLocalBackup(onlyActive: Boolean = false): String {
        val projects = dao.allProjects()
            .filter { !onlyActive || it.isActive }
            .sortedBy { it.id }
            .map { project ->
                toBackupProject(
                    name = project.name,
                    archived = !project.isActive,
                    columns = dao.columnsOf(project.id),
                    tasks = dao.tasksOf(project.id),
                )
            }
        return BackupCodec.encode(BackupFile(projects = projects))
    }

    /**
     * Kanboard mode: syncs completely first, then writes the snapshot from the phone's copy.
     * If the sync cannot finish, nothing is written, so you never get a half-empty file.
     */
    suspend fun exportServerSnapshot(): String {
        val result = sync(null)
        result.error?.let { throw it }
        return exportLocalBackup(onlyActive = true)
    }

    /** Local-only mode: replaces the whole workspace with the backup's content. */
    suspend fun importLocalBackup(text: String) {
        check(mode == Mode.LOCAL) { "Backups can only be restored in local-only mode" }
        val file = BackupCodec.decode(text)

        val projects = mutableListOf<ProjectEntity>()
        val columns = mutableListOf<ColumnEntity>()
        val tasks = mutableListOf<TaskEntity>()
        var nextProject = 1L
        var nextColumn = 1L
        var nextTask = 1L
        for (project in file.projects) {
            val projectId = nextProject++
            projects += ProjectEntity(projectId, project.name.trim(), isActive = !project.archived)
            project.columns.forEachIndexed { columnIndex, column ->
                val columnId = nextColumn++
                columns += ColumnEntity(columnId, projectId, column.title.trim(), columnIndex + 1)
                column.tasks.forEachIndexed { taskIndex, task ->
                    tasks += TaskEntity(
                        id = nextTask++,
                        projectId = projectId,
                        columnId = columnId,
                        swimlaneId = 0L,
                        title = task.title.trim(),
                        description = task.description,
                        position = taskIndex + 1,
                        dueDate = task.dueDate,
                        isActive = !task.closed,
                        colorId = task.color,
                        priority = task.priority.coerceIn(0, 3),
                    )
                }
            }
        }
        dbLock.withLock {
            dao.replaceEverything(projects, columns, tasks)
            store.selectedProjectId = 0L
        }
    }

    private fun toBackupProject(
        name: String,
        archived: Boolean,
        columns: List<ColumnEntity>,
        tasks: List<TaskEntity>,
    ): BackupProject {
        val byColumn = tasks.groupBy { it.columnId }
        return BackupProject(
            name = name,
            archived = archived,
            columns = columns.sortedBy { it.position }.map { column ->
                BackupColumn(
                    title = column.title,
                    tasks = (byColumn[column.id] ?: emptyList())
                        .sortedWith(compareBy({ it.swimlaneId }, { it.position }, { it.id }))
                        .map {
                            BackupTask(
                                title = it.title,
                                description = it.description,
                                dueDate = it.dueDate,
                                closed = !it.isActive,
                                color = it.colorId,
                                priority = it.priority,
                            )
                        },
                )
            },
        )
    }

    // ---- Widget -----------------------------------------------------------------------------------------

    /** Reads the phone's copy for the home-screen widget. Returns null if the project is gone or archived. */
    suspend fun widgetSnapshot(projectId: Long): WidgetData? {
        val project = dao.project(projectId)?.takeIf { it.isActive } ?: return null
        val openTasks = dao.tasksOf(projectId).filter { it.isActive }.groupBy { it.columnId }
        val rows = mutableListOf<WidgetRow>()
        for (column in dao.columnsOf(projectId)) {
            val inColumn = openTasks[column.id].orEmpty()
            rows += WidgetRow("${column.title} (${inColumn.size})", isHeader = true)
            inColumn.forEach { rows += WidgetRow(it.title, isHeader = false) }
        }
        return WidgetData(project.name, rows)
    }

    private companion object {
        /** Every new local project gets these four columns. */
        val LOCAL_COLUMNS = listOf("Backlog", "Ready", "Work in progress", "Done")
    }
}
