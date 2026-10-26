package com.mmbesar.kancard.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLException

/** Everything that can go wrong when talking to Kanboard, in a few simple kinds. */
class ApiException(
    val kind: Kind,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause) {

    enum class Kind {
        /** No connection, timeout, wrong address, DNS problem ... */
        NETWORK,

        /** The certificate is not trusted (unknown/self-signed/changed). */
        CERTIFICATE,

        /** 401: wrong username or token. */
        UNAUTHORIZED,

        /** 403: this account may not do that. */
        FORBIDDEN,

        /** Kanboard answered "no" (result: false). */
        REJECTED,

        /** Kanboard reported an error, or answered with an HTTP error. */
        SERVER,

        /** The answer was not Kanboard JSON (often a wrong address). */
        BAD_RESPONSE,

        /** No server has been set up yet. */
        NOT_CONFIGURED,
    }
}

/**
 * Typed client for Kanboard's JSON-RPC API (POST <server>/jsonrpc.php).
 * Keep ALL JSON-RPC calls in this one class.
 *
 * Login uses HTTP Basic auth. Kanboard accepts two kinds of credentials:
 *  - user credentials: your own username + your password (or a personal access token);
 *  - application credentials: the username "jsonrpc" + the application API token
 *    from Kanboard's Settings > API. These have no user behind them, so the
 *    "me" calls (getMe, getMyProjects) are replaced by their application-wide twins.
 *
 * Privacy rule: never log request or response bodies - they contain private
 * task data and credentials.
 */
class BoardApi(
    private val client: OkHttpClient,
    private val endpoint: String,
    private val username: String,
    private val token: String,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val nextId = AtomicInteger(0)

    // ---- Calls we use -----------------------------------------------------

    /** True when logged in with the application API token (username "jsonrpc"). */
    private val isApplication: Boolean get() = username == APPLICATION_USER

    /** Used to test the login. Fails with UNAUTHORIZED if the login is wrong. */
    suspend fun getMe() {
        // Application credentials have no "me"; asking for the version proves the login works.
        call(if (isApplication) "getVersion" else "getMe")
    }

    suspend fun getMyProjects(): List<ProjectDto> =
        decodeList(call(if (isApplication) "getAllProjects" else "getMyProjects", rejectFalse = false))

    /** A project always has columns, so "false" here is a real failure (never "empty"). */
    suspend fun getColumns(projectId: Long): List<ColumnDto> =
        decodeList(call("getColumns", buildJsonObject { put("project_id", projectId) }))

    /** status 1 = open tasks, 0 = closed tasks. */
    suspend fun getAllTasks(projectId: Long, open: Boolean): List<TaskDto> =
        decodeList(
            call(
                "getAllTasks",
                buildJsonObject {
                    put("project_id", projectId)
                    put("status_id", if (open) 1 else 0)
                },
                rejectFalse = false,
            ),
        )

    /** Returns the id of the new task. [dueDate] is "YYYY-MM-DD" or empty. */
    suspend fun createTask(
        projectId: Long,
        columnId: Long,
        swimlaneId: Long,
        title: String,
        description: String,
        dueDate: String,
        colorId: String,
        priority: Int,
        reference: String,
    ): Long {
        val result = call(
            "createTask",
            buildJsonObject {
                put("title", title)
                put("project_id", projectId)
                put("column_id", columnId)
                put("description", description)
                // Only send what is really set: an invalid colour would make Kanboard refuse the task.
                if (swimlaneId > 0) put("swimlane_id", swimlaneId)
                if (dueDate.isNotEmpty()) put("date_due", dueDate)
                if (colorId.isNotEmpty()) put("color_id", colorId)
                put("priority", priority)
                if (reference.isNotEmpty()) put("reference", reference)
            },
        )
        return result.jsonPrimitive.contentOrNull?.toLongOrNull()
            ?: throw ApiException(ApiException.Kind.BAD_RESPONSE, "createTask returned no id")
    }

    /** The id of the task carrying this reference tag, or null if there is none. */
    suspend fun findTaskIdByReference(projectId: Long, reference: String): Long? {
        val result = call(
            "getTaskByReference",
            buildJsonObject {
                put("project_id", projectId)
                put("reference", reference)
            },
            rejectFalse = false,
        )
        val task = result as? JsonObject ?: return null
        return task["id"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    }

    suspend fun updateTask(
        taskId: Long,
        title: String,
        description: String,
        dueDate: String,
        colorId: String,
        priority: Int,
    ) {
        call(
            "updateTask",
            buildJsonObject {
                put("id", taskId)
                put("title", title)
                put("description", description)
                // An empty text clears the due date.
                put("date_due", dueDate)
                if (colorId.isNotEmpty()) put("color_id", colorId)
                put("priority", priority)
            },
        )
    }

    /** [position] starts at 1 (top of the column). */
    suspend fun moveTaskPosition(
        projectId: Long,
        taskId: Long,
        columnId: Long,
        position: Int,
        swimlaneId: Long,
    ) {
        call(
            "moveTaskPosition",
            buildJsonObject {
                put("project_id", projectId)
                put("task_id", taskId)
                put("column_id", columnId)
                put("position", position)
                // 0 / unknown (task created offline, not refreshed yet): let Kanboard use its default.
                put("swimlane_id", if (swimlaneId > 0) swimlaneId else 0L)
            },
        )
    }

    suspend fun closeTask(taskId: Long) {
        call("closeTask", buildJsonObject { put("task_id", taskId) })
    }

    suspend fun openTask(taskId: Long) {
        call("openTask", buildJsonObject { put("task_id", taskId) })
    }

    suspend fun removeTask(taskId: Long) {
        call("removeTask", buildJsonObject { put("task_id", taskId) })
    }

    /** Returns the id of the new project. */
    suspend fun createProject(name: String): Long {
        val result = call("createProject", buildJsonObject { put("name", name) })
        return result.jsonPrimitive.contentOrNull?.toLongOrNull()
            ?: throw ApiException(ApiException.Kind.BAD_RESPONSE, "createProject returned no id")
    }

    /** Kanboard calls archiving "disabling" a project. Nothing is deleted. */
    suspend fun disableProject(projectId: Long) {
        call("disableProject", buildJsonObject { put("project_id", projectId) })
    }

    // ---- Subtasks and comments --------------------------------------------

    suspend fun getSubtasks(taskId: Long): List<SubtaskDto> =
        decodeList(call("getAllSubtasks", buildJsonObject { put("task_id", taskId) }, rejectFalse = false))

    /** Returns the id of the new subtask. */
    suspend fun createSubtask(taskId: Long, title: String): Long {
        val result = call(
            "createSubtask",
            buildJsonObject {
                put("task_id", taskId)
                put("title", title)
            },
        )
        return result.jsonPrimitive.contentOrNull?.toLongOrNull()
            ?: throw ApiException(ApiException.Kind.BAD_RESPONSE, "createSubtask returned no id")
    }

    /** status: 0 = to do, 1 = in progress, 2 = done. */
    suspend fun updateSubtaskStatus(subtaskId: Long, taskId: Long, status: Int) {
        call(
            "updateSubtask",
            buildJsonObject {
                put("id", subtaskId)
                put("task_id", taskId)
                put("status", status)
            },
        )
    }

    suspend fun removeSubtask(subtaskId: Long) {
        call("removeSubtask", buildJsonObject { put("subtask_id", subtaskId) })
    }

    suspend fun getComments(taskId: Long): List<CommentDto> =
        decodeList(call("getAllComments", buildJsonObject { put("task_id", taskId) }, rejectFalse = false))

    /** Returns the id of the new comment. */
    suspend fun createComment(taskId: Long, userId: Long, content: String): Long {
        val result = call(
            "createComment",
            buildJsonObject {
                put("task_id", taskId)
                put("user_id", userId)
                put("content", content)
            },
        )
        return result.jsonPrimitive.contentOrNull?.toLongOrNull()
            ?: throw ApiException(ApiException.Kind.BAD_RESPONSE, "createComment returned no id")
    }

    suspend fun removeComment(commentId: Long) {
        call("removeComment", buildJsonObject { put("comment_id", commentId) })
    }

    /** The logged-in user's id (0 for application credentials, which have no user). */
    suspend fun myUserId(): Long {
        if (isApplication) return 0L
        val me = call("getMe") as? JsonObject
        return me?.get("id")?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
    }

    // ---- Plumbing ---------------------------------------------------------

    /**
     * Sends one JSON-RPC request and returns its "result".
     * [rejectFalse]: Kanboard answers "false" when it refuses an action. For
     * list calls, "false" can also simply mean "nothing there", so those pass false.
     */
    private suspend fun call(
        method: String,
        params: JsonObject? = null,
        rejectFalse: Boolean = true,
    ): JsonElement = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            if (params != null) put("params", params)
            put("id", nextId.incrementAndGet())
        }.toString()

        val request = Request.Builder()
            .url(endpoint)
            .header("Authorization", Credentials.basic(username, token, Charsets.UTF_8))
            .post(body.toRequestBody(JSON_TYPE))
            .build()

        try {
            client.newCall(request).execute().use { response ->
                when (response.code) {
                    401 -> throw ApiException(ApiException.Kind.UNAUTHORIZED, "Wrong username or token")
                    403 -> throw ApiException(ApiException.Kind.FORBIDDEN, "This account may not do that")
                }
                if (!response.isSuccessful) {
                    throw ApiException(ApiException.Kind.SERVER, "Server answered HTTP ${response.code}")
                }
                val text = response.body?.string().orEmpty()
                val root = try {
                    json.parseToJsonElement(text).jsonObject
                } catch (e: SerializationException) {
                    throw ApiException(ApiException.Kind.BAD_RESPONSE, "Not a Kanboard answer", e)
                } catch (e: IllegalArgumentException) {
                    throw ApiException(ApiException.Kind.BAD_RESPONSE, "Not a Kanboard answer", e)
                }

                val error = root["error"]
                if (error != null && error !is JsonNull) {
                    val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull
                    throw ApiException(ApiException.Kind.SERVER, message ?: "Kanboard reported an error")
                }
                val result = root["result"]
                    ?: throw ApiException(ApiException.Kind.BAD_RESPONSE, "Answer has no result")
                if (rejectFalse && isFalse(result)) {
                    throw ApiException(ApiException.Kind.REJECTED, "Kanboard rejected $method")
                }
                result
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: SSLException) {
            // Untrusted, self-signed, changed or mismatching certificate.
            throw ApiException(ApiException.Kind.CERTIFICATE, "Certificate problem", e)
        } catch (e: IOException) {
            throw ApiException(ApiException.Kind.NETWORK, "Cannot reach the server", e)
        }
    }

    /** Kanboard list answers may be a JSON list, a map of id -> item, or "false" (= empty). */
    private fun elements(result: JsonElement): List<JsonElement> = when {
        result is JsonArray -> result.toList()
        result is JsonObject -> result.values.toList()
        else -> emptyList()
    }

    private inline fun <reified T> decodeList(result: JsonElement): List<T> =
        try {
            elements(result).map { json.decodeFromJsonElement(serializer<T>(), it) }
        } catch (e: SerializationException) {
            throw ApiException(ApiException.Kind.BAD_RESPONSE, "Unexpected answer format", e)
        }

    private fun isFalse(result: JsonElement): Boolean =
        result is JsonPrimitive && !result.isString && result.content == "false"

    private companion object {
        /** Kanboard's fixed username for the application-wide API token. */
        const val APPLICATION_USER = "jsonrpc"
        val JSON_TYPE = "application/json".toMediaType()
    }
}
