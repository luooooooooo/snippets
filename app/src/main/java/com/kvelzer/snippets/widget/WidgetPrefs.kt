package com.kvelzer.snippets.widget

import android.content.Context

/** widgetId -> noteId mapping, kept in SharedPreferences. */
object WidgetPrefs {

    private const val PREFS = "widget_prefs"
    private const val KEY_PREFIX = "widget_note_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun setNoteId(context: Context, appWidgetId: Int, noteId: Long) {
        prefs(context).edit().putLong(KEY_PREFIX + appWidgetId, noteId).apply()
    }

    /** Returns null when the widget was never configured. */
    fun getNoteId(context: Context, appWidgetId: Int): Long? {
        val id = prefs(context).getLong(KEY_PREFIX + appWidgetId, -1L)
        return if (id == -1L) null else id
    }

    fun remove(context: Context, appWidgetId: Int) {
        prefs(context).edit().remove(KEY_PREFIX + appWidgetId).apply()
    }
}
