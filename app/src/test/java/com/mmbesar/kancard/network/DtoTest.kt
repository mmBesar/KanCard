package com.mmbesar.kancard.network

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

class DtoTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun stringNumbersAreAccepted() {
        val task = json.decodeFromString(
            TaskDto.serializer(),
            """{"id":"7","project_id":"2","column_id":"3","swimlane_id":"1","title":"Buy milk",
               "description":"2 litres","position":"4","date_due":"1790000000","is_active":"1"}""",
        )
        assertEquals(7L, task.id)
        assertEquals(2L, task.projectId)
        assertEquals(3L, task.columnId)
        assertEquals(4L, task.position)
        assertEquals(1790000000L, task.dateDue)
        assertEquals(1L, task.isActive)
    }

    @Test
    fun colourAndPriorityAreRead() {
        val task = json.decodeFromString(
            TaskDto.serializer(),
            """{"id":"1","title":"X","color_id":"red","priority":"2"}""",
        )
        assertEquals("red", task.colorId)
        assertEquals(2L, task.priority)
        // Missing or null values fall back to "no colour" and "no priority".
        val plain = json.decodeFromString(TaskDto.serializer(), """{"id":"2","color_id":null}""")
        assertEquals("", plain.colorId)
        assertEquals(0L, plain.priority)
    }

    @Test
    fun subtasksAndCommentsAreRead() {
        val sub = json.decodeFromString(
            SubtaskDto.serializer(),
            """{"id":"4","title":"Pack","status":"2","task_id":"9","position":"1"}""",
        )
        assertEquals(4L, sub.id)
        assertEquals(2L, sub.status)
        val comment = json.decodeFromString(
            CommentDto.serializer(),
            """{"id":"5","task_id":"9","date_creation":"1790000000","comment":"Hi **there**","name":null,"username":"mo"}""",
        )
        assertEquals("Hi **there**", comment.comment)
        assertEquals("", comment.name)
        assertEquals("mo", comment.username)
        assertEquals(1790000000L, comment.dateCreation)
    }

    @Test
    fun realNumbersAreAcceptedToo() {
        val task = json.decodeFromString(
            TaskDto.serializer(),
            """{"id":7,"project_id":2,"column_id":3,"title":"X","position":4,"is_active":0}""",
        )
        assertEquals(7L, task.id)
        assertEquals(0L, task.isActive)
    }

    @Test
    fun nullAndMissingFieldsBecomeDefaults() {
        val task = json.decodeFromString(
            TaskDto.serializer(),
            """{"id":"1","title":"Only a title","description":null,"date_due":null,"extra":"ignored"}""",
        )
        assertEquals("", task.description)
        assertEquals(0L, task.dateDue)
        assertEquals(0L, task.columnId)
    }

    @Test
    fun projectAndColumnDecode() {
        val project = json.decodeFromString(
            ProjectDto.serializer(),
            """{"id":"1","name":"Home","is_active":"1","token":"abc"}""",
        )
        assertEquals("Home", project.name)
        assertEquals(1L, project.isActive)

        val column = json.decodeFromString(
            ColumnDto.serializer(),
            """{"id":"5","title":"Ready","position":"2","project_id":"1"}""",
        )
        assertEquals("Ready", column.title)
        assertEquals(2L, column.position)
    }
}
