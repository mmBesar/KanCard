package com.mmbesar.kancard.data

import com.mmbesar.kancard.network.ApiException

/** What the "sync" indicator shows. */
enum class SyncState {
    /** Nothing to report. */
    IDLE,

    /** Talking to the server right now. */
    SYNCING,

    /** No connection: changes are kept and sent later. */
    OFFLINE,

    /** The server refused the saved login: the token needs updating. */
    LOGIN,

    /** The server certificate is not the one that was trusted. */
    CERTIFICATE,

    /** The server answered with a problem; KanCard will try again. */
    ERROR,
}

/**
 * Result of one sync run.
 * [dropped] = changes the server refused for good (they were undone locally).
 * [error] = why the run stopped early, or null if it finished.
 */
data class SyncResult(
    val dropped: Int = 0,
    val error: ApiException? = null,
)

/** Result of sending the queue (internal). */
internal data class DrainResult(
    val dropped: Int,
    /** Projects that had refused changes: they are re-downloaded so the screen shows the truth. */
    val projects: Set<Long>,
    val error: ApiException?,
)
