package com.mmbesar.kancard.widget

import android.content.Context

/**
 * Remembers which project each home-screen widget shows.
 * Key = the widget's Android id, value = the project id.
 */
object WidgetPrefs {

    private const val FILE = "kancard_widgets"

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun projectFor(context: Context, appWidgetId: Int): Long? =
        prefs(context).getLong(appWidgetId.toString(), 0L).takeIf { it > 0L }

    fun set(context: Context, appWidgetId: Int, projectId: Long) {
        prefs(context).edit().putLong(appWidgetId.toString(), projectId).apply()
    }

    fun remove(context: Context, appWidgetIds: IntArray) {
        val editor = prefs(context).edit()
        appWidgetIds.forEach { editor.remove(it.toString()) }
        editor.apply()
    }

    /** Every project that at least one widget shows (the background refresh needs these). */
    fun allProjectIds(context: Context): Set<Long> =
        prefs(context).all.values.mapNotNull { it as? Long }.filter { it > 0L }.toSet()
}
