package com.kvelzer.snippets.widget

import android.appwidget.AppWidgetManager

object WidgetSizing {

    /**
     * True when the widget is roughly one cell wide — too narrow for a
     * readable title next to the icon/glyph, so providers hide the title and
     * let the icon identify the widget kind. 0 means the launcher reported no
     * size (options not set yet); treat that as wide so the title shows.
     */
    fun isCompact(manager: AppWidgetManager, appWidgetId: Int): Boolean {
        val minWidthDp = manager.getAppWidgetOptions(appWidgetId)
            .getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0)
        return minWidthDp in 1 until 100
    }
}
