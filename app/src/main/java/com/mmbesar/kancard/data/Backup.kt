package com.mmbesar.kancard.data

import kotlinx.serialization.Required
import kotlinx.serialization.SerializationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The portable backup file: plain UTF-8 JSON, easy to read and keep.
 *
 * It holds project, column and task CONTENT only. It never contains the server
 * address, username, token, certificate pin or widget settings.
 */
@Serializable
data class BackupFile(
    // @Required: a file without these keys (for example "{}") is NOT a backup,
    // so it can never wipe the workspace by accident.
    @Required val format: String = FORMAT,
    @Required val version: Int = VERSION,
    @Required val projects: List<BackupProject> = emptyList(),
) {
    companion object {
        const val FORMAT = "kancard-backup"
        const val VERSION = 1
    }
}

@Serializable
data class BackupProject(
    val name: String,
    /** true for archived (hidden) projects, so they survive a restore. */
    val archived: Boolean = false,
    val columns: List<BackupColumn> = emptyList(),
)

@Serializable
data class BackupColumn(
    val title: String,
    val tasks: List<BackupTask> = emptyList(),
)

@Serializable
data class BackupTask(
    val title: String,
    val description: String = "",
    /** "YYYY-MM-DD" or null. */
    val dueDate: String? = null,
    val closed: Boolean = false,
    /** Kanboard colour name, "" = default. */
    val color: String = "",
    /** 0 (none) to 3 (high). */
    val priority: Int = 0,
)

/** Reads and writes backup files, and refuses files that look wrong. */
object BackupCodec {

    // Generous sanity limits so a corrupt or hostile file cannot fill the phone.
    private const val MAX_PROJECTS = 1_000
    private const val MAX_COLUMNS_PER_PROJECT = 100
    private const val MAX_TASKS_TOTAL = 200_000

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun encode(file: BackupFile): String = json.encodeToString(BackupFile.serializer(), file)

    /** Throws [IllegalArgumentException] with a short reason if the file is not a valid backup. */
    fun decode(text: String): BackupFile {
        val file = try {
            json.decodeFromString(BackupFile.serializer(), text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("This is not a KanCard backup file", e)
        }
        require(file.format == BackupFile.FORMAT) { "This is not a KanCard backup file" }
        require(file.version in 1..BackupFile.VERSION) { "This backup was made by a newer KanCard" }
        require(file.projects.size <= MAX_PROJECTS) { "The backup has too many projects" }
        var tasks = 0
        for (project in file.projects) {
            require(project.name.isNotBlank()) { "A project in the backup has no name" }
            require(project.columns.size <= MAX_COLUMNS_PER_PROJECT) { "A project has too many columns" }
            for (column in project.columns) {
                require(column.title.isNotBlank()) { "A column in the backup has no title" }
                tasks += column.tasks.size
                for (task in column.tasks) {
                    require(task.title.isNotBlank()) { "A task in the backup has no title" }
                    require(task.dueDate == null || DueDates.toPickerMillis(task.dueDate) != null) {
                        "A task in the backup has an invalid date"
                    }
                }
            }
        }
        require(tasks <= MAX_TASKS_TOTAL) { "The backup has too many tasks" }
        return file
    }
}
