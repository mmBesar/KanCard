package com.mmbesar.kancard.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/**
 * All database questions in one place.
 * It is an abstract class (not an interface) so it can contain @Transaction
 * helpers: several changes that succeed or fail together.
 */
@Dao
abstract class BoardDao {

    // ---- Watching data (the screens update automatically) ----------------

    @Query("SELECT * FROM projects WHERE isActive = 1 ORDER BY name COLLATE NOCASE")
    abstract fun observeProjects(): Flow<List<ProjectEntity>>

    @Query("SELECT * FROM board_columns WHERE projectId = :projectId ORDER BY position, id")
    abstract fun observeColumns(projectId: Long): Flow<List<ColumnEntity>>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId AND isActive = :open ORDER BY swimlaneId, position, id")
    abstract fun observeTasks(projectId: Long, open: Boolean): Flow<List<TaskEntity>>

    /** Every open task of every project (for the Today screen). */
    @Query("SELECT * FROM tasks WHERE isActive = 1")
    abstract fun observeAllOpenTasks(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM subtasks WHERE taskId = :taskId ORDER BY position, id")
    abstract fun observeSubtasks(taskId: Long): Flow<List<SubtaskEntity>>

    @Query("SELECT * FROM comments WHERE taskId = :taskId ORDER BY created, id")
    abstract fun observeComments(taskId: Long): Flow<List<CommentEntity>>

    @Query(
        "SELECT taskId, COUNT(*) AS total, " +
            "SUM(CASE WHEN status = 2 THEN 1 ELSE 0 END) AS done " +
            "FROM subtasks GROUP BY taskId",
    )
    abstract fun observeSubtaskCounts(): Flow<List<SubtaskCount>>

    @Query("SELECT COUNT(*) FROM pending_ops")
    abstract fun observePendingCount(): Flow<Int>

    @Query("SELECT DISTINCT taskId FROM pending_ops")
    abstract fun observePendingTaskIds(): Flow<List<Long>>

    // ---- One-off reads -------------------------------------------------------

    @Query("SELECT * FROM projects")
    abstract suspend fun allProjects(): List<ProjectEntity>

    @Query("SELECT * FROM projects WHERE id = :id")
    abstract suspend fun project(id: Long): ProjectEntity?

    @Query("SELECT * FROM board_columns WHERE projectId = :projectId ORDER BY position, id")
    abstract suspend fun columnsOf(projectId: Long): List<ColumnEntity>

    @Query("SELECT * FROM tasks WHERE projectId = :projectId ORDER BY swimlaneId, position, id")
    abstract suspend fun tasksOf(projectId: Long): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    abstract suspend fun task(id: Long): TaskEntity?

    /** Open tasks of one column AND swimlane, in order. Kanboard numbers positions per swimlane. */
    @Query("SELECT * FROM tasks WHERE columnId = :columnId AND swimlaneId = :swimlaneId AND isActive = 1 ORDER BY position, id")
    abstract suspend fun openTasksIn(columnId: Long, swimlaneId: Long): List<TaskEntity>

    /** The swimlane that tasks in this column already use (new tasks join it). */
    @Query("SELECT swimlaneId FROM tasks WHERE columnId = :columnId LIMIT 1")
    abstract suspend fun swimlaneInColumn(columnId: Long): Long?

    @Query("SELECT swimlaneId FROM tasks WHERE projectId = :projectId LIMIT 1")
    abstract suspend fun anySwimlane(projectId: Long): Long?

    /** Next free position at the bottom (max + 1, so gaps left by moved tasks never matter). */
    @Query("SELECT COALESCE(MAX(position), 0) + 1 FROM tasks WHERE columnId = :columnId AND swimlaneId = :swimlaneId AND isActive = 1")
    abstract suspend fun nextPosition(columnId: Long, swimlaneId: Long): Int

    // ---- New ids -----------------------------------------------------------------

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM projects")
    abstract suspend fun nextProjectId(): Long

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM board_columns")
    abstract suspend fun nextColumnId(): Long

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM tasks")
    abstract suspend fun nextTaskId(): Long

    /** Smallest task id (negative ids are offline-created tasks). */
    @Query("SELECT MIN(id) FROM tasks")
    abstract suspend fun minTaskId(): Long?

    // ---- Simple writes -----------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertProjects(projects: List<ProjectEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertColumns(columns: List<ColumnEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertTasks(tasks: List<TaskEntity>)

    @Query("UPDATE tasks SET title = :title, description = :description, dueDate = :dueDate, colorId = :colorId, priority = :priority WHERE id = :id")
    abstract suspend fun updateTaskText(
        id: Long,
        title: String,
        description: String,
        dueDate: String?,
        colorId: String,
        priority: Int,
    )

    @Query("UPDATE tasks SET isActive = :open WHERE id = :id")
    abstract suspend fun setTaskOpen(id: Long, open: Boolean)

    @Query("UPDATE tasks SET columnId = :columnId, position = :position WHERE id = :id")
    abstract suspend fun setTaskPlacement(id: Long, columnId: Long, position: Int)

    @Query("DELETE FROM tasks WHERE id = :id")
    abstract suspend fun deleteTask(id: Long)

    @Query("UPDATE projects SET isActive = :active WHERE id = :id")
    abstract suspend fun setProjectActive(id: Long, active: Boolean)

    @Query("DELETE FROM tasks WHERE projectId = :projectId")
    abstract suspend fun deleteTasksOf(projectId: Long)

    @Query("DELETE FROM board_columns WHERE projectId = :projectId")
    abstract suspend fun deleteColumnsOf(projectId: Long)

    @Query("DELETE FROM projects")
    abstract suspend fun deleteAllProjects()

    @Query("DELETE FROM board_columns")
    abstract suspend fun deleteAllColumns()

    @Query("DELETE FROM tasks")
    abstract suspend fun deleteAllTasks()

    @Query("DELETE FROM projects WHERE id NOT IN (:keep)")
    abstract suspend fun deleteProjectsNotIn(keep: List<Long>)

    @Query("DELETE FROM board_columns WHERE projectId NOT IN (:keep)")
    abstract suspend fun deleteColumnsOfProjectsNotIn(keep: List<Long>)

    @Query("DELETE FROM tasks WHERE projectId NOT IN (:keep)")
    abstract suspend fun deleteTasksOfProjectsNotIn(keep: List<Long>)

    // ---- The queue of changes waiting for the server ---------------------------------

    @Query("SELECT * FROM pending_ops ORDER BY id LIMIT 1")
    abstract suspend fun firstOp(): PendingOp?

    @Query("SELECT COUNT(*) FROM pending_ops")
    abstract suspend fun pendingCount(): Int

    /** How many queued changes of this kind exist for the task, not counting [exceptId]. */
    @Query("SELECT COUNT(*) FROM pending_ops WHERE type = :type AND taskId = :taskId AND id != :exceptId")
    abstract suspend fun countOps(type: String, taskId: Long, exceptId: Long): Int

    @Insert
    abstract suspend fun insertOp(op: PendingOp): Long

    @Query("DELETE FROM pending_ops WHERE id = :id")
    abstract suspend fun deleteOp(id: Long)

    @Query("DELETE FROM pending_ops WHERE taskId = :taskId")
    abstract suspend fun deleteOpsOfTask(taskId: Long)

    @Query("UPDATE pending_ops SET attempts = attempts + 1 WHERE id = :id")
    abstract suspend fun bumpAttempts(id: Long)

    @Query("DELETE FROM pending_ops")
    abstract suspend fun deleteAllOps()

    @Query("UPDATE pending_ops SET taskId = :newId WHERE taskId = :oldId")
    abstract suspend fun retargetOps(oldId: Long, newId: Long)

    @Query("UPDATE tasks SET id = :newId WHERE id = :oldId")
    abstract suspend fun retargetTask(oldId: Long, newId: Long)

    // ---- Several changes at once (all or nothing) --------------------------

    // ---- Subtasks and comments ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertSubtasks(subtasks: List<SubtaskEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun upsertComments(comments: List<CommentEntity>)

    @Query("DELETE FROM subtasks WHERE taskId = :taskId")
    abstract suspend fun deleteSubtasksOf(taskId: Long)

    @Query("DELETE FROM comments WHERE taskId = :taskId")
    abstract suspend fun deleteCommentsOf(taskId: Long)

    @Query("DELETE FROM subtasks")
    abstract suspend fun deleteAllSubtasks()

    @Query("DELETE FROM comments")
    abstract suspend fun deleteAllComments()

    @Query("DELETE FROM subtasks WHERE id = :id")
    abstract suspend fun deleteSubtask(id: Long)

    @Query("DELETE FROM comments WHERE id = :id")
    abstract suspend fun deleteComment(id: Long)

    @Query("UPDATE subtasks SET status = :status WHERE id = :id")
    abstract suspend fun setSubtaskStatus(id: Long, status: Int)

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM subtasks")
    abstract suspend fun nextSubtaskId(): Long

    @Query("SELECT COALESCE(MAX(id), 0) + 1 FROM comments")
    abstract suspend fun nextCommentId(): Long

    @Query("DELETE FROM subtasks WHERE taskId NOT IN (SELECT id FROM tasks)")
    abstract suspend fun deleteOrphanSubtasks()

    @Query("DELETE FROM comments WHERE taskId NOT IN (SELECT id FROM tasks)")
    abstract suspend fun deleteOrphanComments()

    @Query("SELECT COUNT(*) FROM tasks WHERE id = :id")
    abstract suspend fun taskCount(id: Long): Int

    /** Removes subtasks and comments whose task no longer exists. */
    @Transaction
    open suspend fun deleteOrphans() {
        deleteOrphanSubtasks()
        deleteOrphanComments()
    }

    /** Replaces the cached subtasks of one task with what the server said. */
    @Transaction
    open suspend fun replaceSubtasks(taskId: Long, subtasks: List<SubtaskEntity>) {
        deleteSubtasksOf(taskId)
        upsertSubtasks(subtasks)
    }

    /** Replaces the cached comments of one task with what the server said. */
    @Transaction
    open suspend fun replaceComments(taskId: Long, comments: List<CommentEntity>) {
        deleteCommentsOf(taskId)
        upsertComments(comments)
    }

    /** Wipes everything, including the queue. Used when switching between modes. */
    @Transaction
    open suspend fun clearAll() {
        deleteAllSubtasks()
        deleteAllComments()
        deleteAllOps()
        deleteAllTasks()
        deleteAllColumns()
        deleteAllProjects()
    }

    /** A task created offline got its real id from the server: switch the task and its queued changes over. */
    @Transaction
    open suspend fun remapCreatedTask(oldId: Long, newId: Long) {
        retargetTask(oldId, newId)
        retargetOps(oldId, newId)
    }

    /** Replaces the cached columns and tasks of one project with fresh server data. */
    @Transaction
    open suspend fun replaceProjectContent(
        projectId: Long,
        columns: List<ColumnEntity>,
        tasks: List<TaskEntity>,
    ) {
        deleteTasksOf(projectId)
        deleteColumnsOf(projectId)
        upsertColumns(columns)
        upsertTasks(tasks)
        deleteOrphans()
    }

    /** Stores the project list from the server and forgets projects that are gone. */
    @Transaction
    open suspend fun replaceProjectList(projects: List<ProjectEntity>) {
        val keep = projects.map { it.id }
        deleteTasksOfProjectsNotIn(keep)
        deleteColumnsOfProjectsNotIn(keep)
        deleteProjectsNotIn(keep)
        upsertProjects(projects)
        deleteOrphans()
    }

    /** Puts tasks into a column in exactly the given order (positions 1, 2, 3 ...). */
    @Transaction
    open suspend fun placeTasks(columnId: Long, orderedTaskIds: List<Long>) {
        orderedTaskIds.forEachIndexed { index, taskId ->
            setTaskPlacement(taskId, columnId, index + 1)
        }
    }

    /** Replaces the ENTIRE workspace in one step (a failed import leaves your data untouched). */
    @Transaction
    open suspend fun replaceEverything(
        projects: List<ProjectEntity>,
        columns: List<ColumnEntity>,
        tasks: List<TaskEntity>,
    ) {
        clearAll()
        upsertProjects(projects)
        upsertColumns(columns)
        upsertTasks(tasks)
    }

    /** Creates a whole project (columns and tasks) in one go. Used by local-only mode. */
    @Transaction
    open suspend fun insertProjectWithContent(
        project: ProjectEntity,
        columns: List<ColumnEntity>,
        tasks: List<TaskEntity>,
    ) {
        upsertProjects(listOf(project))
        upsertColumns(columns)
        upsertTasks(tasks)
    }
}

/** The database itself. Bump [version] and add a migration whenever a table changes. */
@Database(
    entities = [
        ProjectEntity::class, ColumnEntity::class, TaskEntity::class, PendingOp::class,
        SubtaskEntity::class, CommentEntity::class,
    ],
    version = 3,
    // No schema files: debug and release builds would write the same file at once and clash.
    exportSchema = false,
)
abstract class BoardDatabase : RoomDatabase() {
    abstract fun dao(): BoardDao

    companion object {
        /**
         * Version 1 -> 2: adds the queue table and the colour / priority of tasks.
         * Existing local-only data is kept untouched.
         */
        /** Version 2 -> 3: adds subtasks and comments. Existing data is kept untouched. */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `subtasks` (" +
                        "`id` INTEGER NOT NULL, `taskId` INTEGER NOT NULL, `title` TEXT NOT NULL, " +
                        "`status` INTEGER NOT NULL, `position` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_subtasks_taskId` ON `subtasks` (`taskId`)")
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `comments` (" +
                        "`id` INTEGER NOT NULL, `taskId` INTEGER NOT NULL, `author` TEXT NOT NULL, " +
                        "`content` TEXT NOT NULL, `created` INTEGER NOT NULL, PRIMARY KEY(`id`))",
                )
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_comments_taskId` ON `comments` (`taskId`)")
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `pending_ops` (" +
                        "`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "`type` TEXT NOT NULL, " +
                        "`taskId` INTEGER NOT NULL, " +
                        "`projectId` INTEGER NOT NULL, " +
                        "`attempts` INTEGER NOT NULL, " +
                        "`ref` TEXT NOT NULL)",
                )
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `colorId` TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE `tasks` ADD COLUMN `priority` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun create(context: Context): BoardDatabase =
            Room.databaseBuilder(context, BoardDatabase::class.java, "kancard.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}
