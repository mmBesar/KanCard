package com.mmbesar.kancard.ui

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two ways to look at one project. */
enum class ProjectView {
    /** Columns (the Kanban board). */
    BOARD,

    /** A plain task list, grouped by column, with subtasks that open under each task. */
    LIST,
}

/**
 * Remembers HOW each project is shown.
 *  - Every project has its own default view (Board unless you chose otherwise).
 *  - "Remember the last view" (a setting): when on, changing the view of a project also changes
 *    that project's default, so it opens the way you left it. When off, the default only changes
 *    when you pick "Make this the default view".
 * Plain preferences are fine: nothing here is secret.
 */
class ViewPrefs(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val _defaults = MutableStateFlow(readAll(prefs))
    /** projectId -> its default view (projects not in the map use [ProjectView.BOARD]). */
    val defaults: StateFlow<Map<Long, ProjectView>> = _defaults.asStateFlow()

    private val _rememberLast = MutableStateFlow(prefs.getBoolean(KEY_REMEMBER, false))
    val rememberLast: StateFlow<Boolean> = _rememberLast.asStateFlow()

    fun setDefault(projectId: Long, view: ProjectView) {
        prefs.edit().putString(PREFIX + projectId, view.name).apply()
        _defaults.value = _defaults.value + (projectId to view)
    }

    fun setRememberLast(on: Boolean) {
        prefs.edit().putBoolean(KEY_REMEMBER, on).apply()
        _rememberLast.value = on
    }

    companion object {
        private const val FILE = "kancard_views"
        private const val PREFIX = "view_"
        private const val KEY_REMEMBER = "remember_last_view"

        private fun readAll(prefs: SharedPreferences): Map<Long, ProjectView> {
            val result = mutableMapOf<Long, ProjectView>()
            for ((key, value) in prefs.all) {
                if (!key.startsWith(PREFIX)) continue
                val id = key.removePrefix(PREFIX).toLongOrNull() ?: continue
                val view = runCatching { ProjectView.valueOf(value as? String ?: "") }.getOrNull() ?: continue
                result[id] = view
            }
            return result
        }
    }
}
