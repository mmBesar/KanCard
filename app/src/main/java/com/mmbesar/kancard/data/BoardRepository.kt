package com.mmbesar.kancard.data

import com.mmbesar.kancard.network.ApiException
import com.mmbesar.kancard.network.BoardApi
import com.mmbesar.kancard.network.HttpClients
import com.mmbesar.kancard.security.Connection
import com.mmbesar.kancard.security.CredentialStore
import com.mmbesar.kancard.security.Mode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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

    private val _projectRemapped = MutableSharedFlow<Pair<Long, Long>>(extraBufferCapacity = 8)

    /** A project made offline got its real id (old id, new id): the screen switches to the new one. */
    val projectRemapped: SharedFlow<Pair<Long, Long>> = _projectRemapped.asSharedFlow()

    /** When the subtasks and comments of each project were last downloaded (they are many small calls). */
    private val detailsFetchedAt = ConcurrentHashMap<Long, Long>()

    /** Raised by every write that could make a running download stale (a queued change, a sent change). */
    @Volatile private var changeStamp = 0L

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
            detailsFetchedAt.clear()
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
            detailsFetchedAt.clear()
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
            detailsFetchedAt.clear()
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

    // ---- Subtasks and comments: saved on the phone at once, queued for the server ----------------------

    fun observeSubtasks(taskId: Long): Flow<List<SubtaskEntity>> = dao.observeSubtasks(taskId)

    fun observeComments(taskId: Long): Flow<List<CommentEntity>> = dao.observeComments(taskId)

    fun observeSubtaskCounts(): Flow<List<SubtaskCount>> = dao.observeSubtaskCounts()

    /** Downloads the subtasks and comments of one task. Without a connection the cached copy stays. */
    suspend fun refreshDetails(task: TaskEntity) {
        if (mode != Mode.KANBOARD || task.id <= 0L) return
        val startedIn = generation
        val stamp = changeStamp
        val api = api()
        val subtasks = api.getSubtasks(task.id).map { it.toEntity(task.id) }
        val comments = api.getComments(task.id).map { it.toEntity(task.id) }
        dbLock.withLock {
            // Dropped when the login changed while downloading, when anything was queued or sent
            // meanwhile (the download may be older than that change), or when this task still has
            // changes waiting (they are sent first, then the copy is refreshed).
            if (generation != startedIn || stamp != changeStamp ||
                dao.taskCount(task.id) == 0 || dao.countOpsOfTask(task.id) > 0
            ) return
            dao.replaceSubtasks(task.id, subtasks)
            dao.replaceComments(task.id, comments)
        }
    }

    suspend fun addSubtask(task: TaskEntity, title: String) {
        dbLock.withLock {
            val local = mode == Mode.LOCAL
            val id = if (local) dao.nextSubtaskId() else store.nextTempId()
            dao.upsertSubtasks(listOf(SubtaskEntity(id, task.id, title.trim(), 0, dao.nextSubtaskPosition(task.id))))
            if (!local) enqueue(OpType.SUBTASK_ADD, task.id, task.projectId, targetId = id)
        }
    }

    suspend fun setSubtaskStatus(subtask: SubtaskEntity, status: Int) {
        dbLock.withLock {
            dao.setSubtaskStatus(subtask.id, status)
            if (mode != Mode.LOCAL) {
                enqueue(OpType.SUBTASK_STATUS, subtask.taskId, dao.projectOfTask(subtask.taskId) ?: 0L, subtask.id)
            }
        }
    }

    suspend fun deleteSubtask(subtask: SubtaskEntity) {
        dbLock.withLock {
            dao.deleteSubtask(subtask.id)
            if (mode != Mode.LOCAL) {
                enqueue(OpType.SUBTASK_DELETE, subtask.taskId, dao.projectOfTask(subtask.taskId) ?: 0L, subtask.id)
            }
        }
    }

    /** [localAuthor] is the name shown for your own comments until the server tells us your name. */
    suspend fun addComment(task: TaskEntity, text: String, localAuthor: String) {
        dbLock.withLock {
            val local = mode == Mode.LOCAL
            val id = if (local) dao.nextCommentId() else store.nextTempId()
            val author = if (local) localAuthor else store.loadConnection()?.username ?: localAuthor
            dao.upsertComments(
                listOf(CommentEntity(id, task.id, author, text.trim(), System.currentTimeMillis() / 1000)),
            )
            if (!local) enqueue(OpType.COMMENT_ADD, task.id, task.projectId, targetId = id)
        }
    }

    suspend fun deleteComment(comment: CommentEntity) {
        dbLock.withLock {
            dao.deleteComment(comment.id)
            if (mode != Mode.LOCAL) {
                enqueue(OpType.COMMENT_DELETE, comment.taskId, dao.projectOfTask(comment.taskId) ?: 0L, comment.id)
            }
        }
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
            val id = if (local) dao.nextTaskId() else store.nextTempId()
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
    private suspend fun enqueue(type: OpType, taskId: Long, projectId: Long, targetId: Long = 0L) {
        changeStamp++
        when (type) {
            OpType.DELETE -> {
                // Whatever was queued for this task is pointless now.
                dao.deleteOpsOfTask(taskId)
                // A task the server never saw (negative id) needs no delete request.
                if (taskId > 0) dao.insertOp(PendingOp(type = type.name, taskId = taskId, projectId = projectId))
            }

            OpType.CREATE -> dao.insertOp(
                PendingOp(type = type.name, taskId = taskId, projectId = projectId, ref = "kancard-" + UUID.randomUUID()),
            )

            OpType.SUBTASK_ADD, OpType.COMMENT_ADD -> dao.insertOp(
                PendingOp(type = type.name, taskId = taskId, projectId = projectId, targetId = targetId),
            )

            OpType.SUBTASK_STATUS -> {
                // Values are read when sending, so one entry per subtask is enough.
                if (dao.countOpsFor(type.name, targetId, inFlightOpId) == 0) {
                    dao.insertOp(PendingOp(type = type.name, taskId = taskId, projectId = projectId, targetId = targetId))
                }
            }

            OpType.SUBTASK_DELETE, OpType.COMMENT_DELETE -> {
                val kinds = if (type == OpType.SUBTASK_DELETE) SUBTASK_OP_TYPES else COMMENT_OP_TYPES
                // Pointless changes to a thing that is being removed.
                dao.deleteOpsOfTarget(targetId, kinds)
                // Never reached the server (negative id): nothing to delete there.
                if (targetId > 0) {
                    dao.insertOp(PendingOp(type = type.name, taskId = taskId, projectId = projectId, targetId = targetId))
                }
            }

            OpType.PROJECT_CREATE, OpType.PROJECT_ARCHIVE -> dao.insertOp(
                PendingOp(type = type.name, taskId = 0L, projectId = projectId),
            )

            else -> if (dao.countOps(type.name, taskId, inFlightOpId) == 0) {
                dao.insertOp(PendingOp(type = type.name, taskId = taskId, projectId = projectId))
            }
        }
    }

    // ---- Projects (these need the server, so they need a connection) -------------------------------

    /**
     * Returns the id of the new project. On a server the project is made on the phone first
     * (with Kanboard's usual four columns) and created there when a connection is available.
     */
    suspend fun createProject(name: String): Long {
        if (mode == Mode.LOCAL) {
            return dbLock.withLock {
                createLocalProject(name).also { store.selectedProjectId = it }
            }
        }
        return dbLock.withLock {
            val projectId = store.nextTempId()
            val columns = LOCAL_COLUMNS.mapIndexed { index, title ->
                ColumnEntity(store.nextTempId(), projectId, title, index + 1)
            }
            dao.insertProjectWithContent(ProjectEntity(projectId, name.trim(), isActive = true), columns, emptyList())
            enqueue(OpType.PROJECT_CREATE, 0L, projectId)
            // Chosen here, under the lock: a sync that gives the project its real id keeps this choice in step.
            store.selectedProjectId = projectId
            projectId
        }
    }

    /** Hides a project. On a server this disables it when a connection is available; nothing is ever deleted. */
    suspend fun archiveProject(projectId: Long) {
        dbLock.withLock {
            when {
                mode == Mode.LOCAL -> dao.setProjectActive(projectId, active = false)
                // The server never heard of it: just forget it.
                projectId < 0 -> dao.deleteProjectCascade(projectId)
                else -> {
                    dao.setProjectActive(projectId, active = false)
                    enqueue(OpType.PROJECT_ARCHIVE, 0L, projectId)
                }
            }
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
    suspend fun sync(projectIds: Collection<Long>?, force: Boolean = false): SyncResult {
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
                // Projects made offline have no server id yet (their id is negative): nothing to download.
                wanted.removeAll { it <= 0L }
                for (id in wanted) {
                    if (dao.project(id)?.isActive == true) pullProject(api, id, generationAtStart)
                }
                // Subtasks and comments last, so the boards appear first. A problem here must not
                // fail the whole sync: the lists are already fresh.
                for (id in wanted) {
                    if (dao.project(id)?.isActive == true) pullDetails(api, id, generationAtStart, force || id in drained.projects)
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
                val type = OpType.entries.firstOrNull { it.name == op.type }
                if (type != null) executeOp(api, op, type)
                // (An entry of an unknown kind, written by some other version, is simply removed.)
                dbLock.withLock {
                    dao.deleteOp(op.id)
                    changeStamp++
                }
            } catch (e: ApiException) {
                // Problems that go away by themselves: no connection, a certificate or login to fix,
                // a server or proxy that is down or answers with something that is not Kanboard.
                val temporary = e.kind == ApiException.Kind.NETWORK ||
                    e.kind == ApiException.Kind.CERTIFICATE ||
                    e.kind == ApiException.Kind.UNAUTHORIZED ||
                    e.kind == ApiException.Kind.NOT_CONFIGURED ||
                    e.kind == ApiException.Kind.BAD_RESPONSE
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
                        dropOp(op)
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
            } catch (e: android.database.SQLException) {
                // A local database conflict while renumbering: this entry cannot be completed. Drop it
                // (the next download shows the truth) instead of blocking the whole queue forever.
                dbLock.withLock { dropOp(op) }
                dropped++
                touchedProjects += op.projectId
            } finally {
                inFlightOpId = 0L
            }
        }
        return DrainResult(dropped, touchedProjects, null)
    }

    /** Removes a queue entry that can never succeed, and everything that hangs on it. Inside [dbLock]. */
    private suspend fun dropOp(op: PendingOp) {
        changeStamp++
        dao.deleteOp(op.id)
        when (op.type) {
            OpType.CREATE.name -> dao.deleteOpsOfTask(op.taskId)
            OpType.SUBTASK_ADD.name -> dao.deleteOpsOfTarget(op.targetId, SUBTASK_OP_TYPES)
            OpType.COMMENT_ADD.name -> dao.deleteOpsOfTarget(op.targetId, COMMENT_OP_TYPES)
            OpType.PROJECT_CREATE.name -> dao.deleteProjectCascade(op.projectId)
        }
    }

    /**
     * Runs [block] to the end even if the sync is cancelled meanwhile: the server has already done
     * its part, and the phone must record that together with removing the queue entry.
     */
    private suspend fun <T> recordAnswer(block: suspend () -> T): T =
        withContext(NonCancellable) { dbLock.withLock { block() } }

    /** Sends one queued change. The values come from the task's CURRENT local state. */
    private suspend fun executeOp(api: BoardApi, op: PendingOp, type: OpType) {
        val startedIn = generation
        when (type) {
            OpType.CREATE -> {
                val task = dao.task(op.taskId) ?: return
                // A retry after a dropped connection must not create the task twice.
                val newId = api.findTaskIdByReference(task.projectId, op.ref)
                    ?: api.createTask(
                        task.projectId, task.columnId, task.swimlaneId, task.title,
                        task.description, task.dueDate.orEmpty(), task.colorId, task.priority, op.ref,
                    )
                recordAnswer {
                    if (dao.task(task.id) != null) {
                        // Swap the temporary id for the real one, also in the queued changes.
                        dao.remapCreatedTask(task.id, newId)
                    } else if (generation == startedIn) {
                        // The task was deleted while it was being created: remove it on the server too.
                        dao.insertOp(PendingOp(type = OpType.DELETE.name, taskId = newId, projectId = task.projectId))
                    }
                    dao.deleteOp(op.id)
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

            OpType.SUBTASK_ADD -> {
                val row = dao.subtask(op.targetId) ?: return
                if (op.taskId <= 0L) return
                val known = dao.subtaskIdsOf(op.taskId).toSet()
                // A retry after a dropped connection must not add it twice.
                val existing = api.getSubtasks(op.taskId).firstOrNull { it.title == row.title && it.id !in known }
                val newId = existing?.id ?: api.createSubtask(op.taskId, row.title)
                recordAnswer {
                    if (dao.subtask(row.id) != null) {
                        dao.remapCreatedSubtask(row.id, newId)
                    } else if (generation == startedIn) {
                        // Deleted while it was being sent: remove it on the server too.
                        dao.insertOp(
                            PendingOp(type = OpType.SUBTASK_DELETE.name, taskId = op.taskId, projectId = op.projectId, targetId = newId),
                        )
                    }
                    dao.deleteOp(op.id)
                }
            }

            OpType.SUBTASK_STATUS -> {
                val row = dao.subtask(op.targetId) ?: return
                if (row.id <= 0L || row.taskId <= 0L) return
                api.updateSubtaskStatus(row.id, row.taskId, row.status)
            }

            OpType.SUBTASK_DELETE -> {
                if (op.targetId <= 0L) return
                try {
                    api.removeSubtask(op.targetId)
                } catch (e: ApiException) {
                    if (e.kind != ApiException.Kind.REJECTED) throw e
                }
            }

            OpType.COMMENT_ADD -> {
                val row = dao.comment(op.targetId) ?: return
                if (op.taskId <= 0L) return
                val known = dao.commentIdsOf(op.taskId).toSet()
                val existing = api.getComments(op.taskId).firstOrNull { it.comment == row.content && it.id !in known }
                val newId = existing?.id ?: api.createComment(op.taskId, api.myUserId(), row.content)
                recordAnswer {
                    if (dao.comment(row.id) != null) {
                        dao.remapCreatedComment(row.id, newId)
                    } else if (generation == startedIn) {
                        dao.insertOp(
                            PendingOp(type = OpType.COMMENT_DELETE.name, taskId = op.taskId, projectId = op.projectId, targetId = newId),
                        )
                    }
                    dao.deleteOp(op.id)
                }
            }

            OpType.COMMENT_DELETE -> {
                if (op.targetId <= 0L) return
                try {
                    api.removeComment(op.targetId)
                } catch (e: ApiException) {
                    if (e.kind != ApiException.Kind.REJECTED) throw e
                }
            }

            OpType.PROJECT_CREATE -> {
                val project = dao.project(op.projectId) ?: return
                val known = dao.allProjects().map { it.id }.toSet()
                // Kanboard project names are unique. A project that already exists under this name and
                // that the phone does not know (a retry after a dropped connection) is adopted;
                // if the name is taken by a project we know, the new one gets "Name (2)", "(3)" ...
                val serverProjects = api.getMyProjects()
                var name = project.name
                val existing = serverProjects.firstOrNull { it.name == name && it.id !in known }
                var created: Long? = existing?.id
                var attempt = 1
                while (created == null) {
                    try {
                        created = api.createProject(name)
                    } catch (e: ApiException) {
                        if (e.kind != ApiException.Kind.REJECTED || attempt >= 5) throw e
                        attempt++
                        name = project.name + " ($attempt)"
                    }
                }
                val newId: Long = created
                val columns = api.getColumns(newId).map { it.toEntity(newId) }
                // Without its columns the project cannot be completed: try again later (it is adopted then).
                if (columns.isEmpty()) throw ApiException(ApiException.Kind.BAD_RESPONSE, "The new project has no columns")
                val finalName = name
                recordAnswer {
                    if (dao.project(op.projectId) != null) {
                        // Chosen before the swap, so the screen never sees a moment without its project.
                        if (store.selectedProjectId == op.projectId) store.selectedProjectId = newId
                        dao.remapCreatedProject(op.projectId, newId, columns)
                        if (finalName != project.name) dao.renameProject(newId, finalName)
                    } else if (generation == startedIn) {
                        // Archived on the phone while it was being created: hide it on the server too.
                        dao.insertOp(PendingOp(type = OpType.PROJECT_ARCHIVE.name, taskId = 0L, projectId = newId))
                    }
                    dao.deleteOp(op.id)
                }
                if (dao.project(newId) != null) _projectRemapped.tryEmit(op.projectId to newId)
            }

            OpType.PROJECT_ARCHIVE -> {
                if (op.projectId <= 0L) return
                try {
                    api.disableProject(op.projectId)
                } catch (e: ApiException) {
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

    /**
     * Keeps the subtasks and comments of the open tasks on the phone, so they can be read (and the
     * subtask counts shown) without a connection. Throttled: it is many small requests.
     */
    private suspend fun pullDetails(api: BoardApi, projectId: Long, generationAtStart: Int, force: Boolean) {
        val now = System.currentTimeMillis()
        val last = detailsFetchedAt[projectId] ?: 0L
        if (!force && now - last < DETAILS_MAX_AGE_MS) return
        val ids = dao.syncedOpenTaskIds(projectId).take(DETAILS_MAX_TASKS)
        var allWritten = true
        for (chunk in ids.chunked(DETAILS_CHUNK)) {
            val details = try {
                api.getDetails(chunk)
            } catch (e: ApiException) {
                // A server that does not like batches must not break the sync; lost connections still stop it.
                val connection = e.kind == ApiException.Kind.NETWORK || e.kind == ApiException.Kind.CERTIFICATE ||
                    e.kind == ApiException.Kind.UNAUTHORIZED
                if (connection) throw e
                detailsFetchedAt[projectId] = now
                return
            }
            val subtasks = details.flatMap { (taskId, pair) -> pair.first.map { it.toEntity(taskId) } }
            val comments = details.flatMap { (taskId, pair) -> pair.second.map { it.toEntity(taskId) } }
            dbLock.withLock {
                // Skipped if the workspace changed, or if there are changes to send first.
                if (generationAtStart == generation && dao.pendingCount() == 0) {
                    dao.replaceDetails(details.keys.toList(), subtasks, comments)
                } else {
                    allWritten = false
                }
            }
        }
        if (allWritten) detailsFetchedAt[projectId] = now
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
                    subtasks = dao.subtasksOfProject(project.id).groupBy { it.taskId },
                    comments = dao.commentsOfProject(project.id).groupBy { it.taskId },
                )
            }
        return BackupCodec.encode(BackupFile(projects = projects))
    }

    /**
     * Kanboard mode: tries to sync first, then writes the snapshot from the phone's copy.
     * Without a connection the snapshot is simply what is on the phone right now.
     */
    suspend fun exportServerSnapshot(): String {
        sync(null, force = true)
        return exportLocalBackup(onlyActive = true)
    }

    /** Local-only mode: replaces the whole workspace with the backup's content. */
    suspend fun importLocalBackup(text: String) {
        check(mode == Mode.LOCAL) { "Backups can only be restored in local-only mode" }
        val file = BackupCodec.decode(text)

        val projects = mutableListOf<ProjectEntity>()
        val columns = mutableListOf<ColumnEntity>()
        val tasks = mutableListOf<TaskEntity>()
        val subtasks = mutableListOf<SubtaskEntity>()
        val comments = mutableListOf<CommentEntity>()
        var nextSubtask = 1L
        var nextComment = 1L
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
                    val taskId = nextTask++
                    tasks += TaskEntity(
                        id = taskId,
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
                    task.subtasks.filter { it.title.isNotBlank() }.forEachIndexed { index, sub ->
                        subtasks += SubtaskEntity(nextSubtask++, taskId, sub.title.trim(), sub.status.coerceIn(0, 2), index + 1)
                    }
                    task.comments.forEach { c ->
                        comments += CommentEntity(nextComment++, taskId, c.author, c.content, c.created)
                    }
                }
            }
        }
        dbLock.withLock {
            dao.replaceEverything(projects, columns, tasks, subtasks, comments)
            store.selectedProjectId = 0L
        }
    }

    private fun toBackupProject(
        name: String,
        archived: Boolean,
        columns: List<ColumnEntity>,
        tasks: List<TaskEntity>,
        subtasks: Map<Long, List<SubtaskEntity>>,
        comments: Map<Long, List<CommentEntity>>,
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
                                subtasks = subtasks[it.id].orEmpty().sortedBy { sub -> sub.position }
                                    .map { sub -> BackupSubtask(sub.title, sub.status) },
                                comments = comments[it.id].orEmpty().sortedBy { c -> c.created }
                                    .map { c -> BackupComment(c.author, c.content, c.created) },
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
        /** Subtasks and comments are downloaded for at most this many open tasks per project, in groups. */
        const val DETAILS_MAX_TASKS = 400
        const val DETAILS_CHUNK = 40
        const val DETAILS_MAX_AGE_MS = 2 * 60 * 1000L

        /** Every new local project gets these four columns. */
        val LOCAL_COLUMNS = listOf("Backlog", "Ready", "Work in progress", "Done")
    }
}
