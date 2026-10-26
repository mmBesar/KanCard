package com.mmbesar.kancard.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupCodecTest {

    private val sample = BackupFile(
        projects = listOf(
            BackupProject(
                name = "Home",
                columns = listOf(
                    BackupColumn("Backlog", listOf(BackupTask("Paint wall", "Blue", "2026-11-01", closed = false))),
                    BackupColumn("Done", listOf(BackupTask("Buy paint", closed = true))),
                ),
            ),
            BackupProject(name = "Old", archived = true),
        ),
    )

    @Test
    fun roundTripKeepsEverything() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sample))
        assertEquals(sample, decoded)
        assertTrue(decoded.projects[1].archived)
        assertTrue(decoded.projects[0].columns[1].tasks[0].closed)
    }

    @Test
    fun emptyBackupIsValid() {
        val decoded = BackupCodec.decode(BackupCodec.encode(BackupFile()))
        assertEquals(0, decoded.projects.size)
    }

    @Test
    fun backupNeverContainsLoginData() {
        val text = BackupCodec.encode(sample).lowercase()
        assertFalse(text.contains("token"))
        assertFalse(text.contains("password"))
        assertFalse(text.contains("endpoint"))
    }

    @Test
    fun wrongFormatIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode("""{"format":"something-else","version":1,"projects":[]}""")
        }
    }

    @Test
    fun emptyObjectIsRefused() {
        // Without the required fields it must not be mistaken for an empty backup.
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("{}") }
    }

    @Test
    fun garbageIsRefused() {
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode("hello") }
    }

    @Test
    fun newerVersionIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode("""{"format":"kancard-backup","version":99,"projects":[]}""")
        }
    }

    @Test
    fun blankNamesAndBadDatesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupCodec.decode(BackupCodec.encode(BackupFile(projects = listOf(BackupProject(name = " ")))))
        }
        val badDate = BackupFile(
            projects = listOf(
                BackupProject(
                    "P",
                    columns = listOf(BackupColumn("C", listOf(BackupTask("T", dueDate = "31/12/2026")))),
                ),
            ),
        )
        assertThrows(IllegalArgumentException::class.java) { BackupCodec.decode(BackupCodec.encode(badDate)) }
    }
}
