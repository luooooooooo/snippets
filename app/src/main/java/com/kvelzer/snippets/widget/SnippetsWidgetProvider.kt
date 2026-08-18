package com.kvelzer.snippets.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.kvelzer.snippets.CopyNoteActivity
import com.kvelzer.snippets.NoteStore
import com.kvelzer.snippets.R

/**
 * Home-screen widget: shows the bound note's title; a tap copies its
 * formatted content. Widgets don't run our code directly — the launcher
 * renders a RemoteViews we hand it, and the only interactivity we get is
 * attaching PendingIntents to views.
 */
class SnippetsWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) updateWidget(context, manager, id)
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        for (id in appWidgetIds) WidgetPrefs.remove(context, id)
    }

    // Resizing must re-render: a 1x1 widget hides the title, a wider one shows it.
    override fun onAppWidgetOptionsChanged(
        context: Context,
        manager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle,
    ) = updateWidget(context, manager, appWidgetId)

    companion object {

        fun updateWidget(context: Context, manager: AppWidgetManager, appWidgetId: Int) {
            val noteId = WidgetPrefs.getNoteId(context, appWidgetId)
            val note = noteId?.let { NoteStore.get(context, it) }
            val views = RemoteViews(context.packageName, R.layout.widget_snippet)

            if (note != null) {
                views.setTextViewText(
                    R.id.widget_title,
                    note.title.ifBlank { context.getString(R.string.untitled) },
                )
                // At ~1 cell there's no room for text next to the icon. The
                // deleted state below always keeps its text — an icon-only
                // "reconfigure" widget would be indistinguishable from a bound one.
                views.setViewVisibility(
                    R.id.widget_title,
                    if (WidgetSizing.isCompact(manager, appWidgetId)) View.GONE else View.VISIBLE,
                )
                // Tap -> transparent CopyNoteActivity. Android 10+ only lets a
                // focused foreground app write the clipboard, so the tap must
                // land in an Activity, not a BroadcastReceiver.
                val intent = Intent(context, CopyNoteActivity::class.java)
                    .putExtra(CopyNoteActivity.EXTRA_NOTE_ID, note.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                views.setOnClickPendingIntent(R.id.widget_root, pending(context, appWidgetId, intent))
            } else {
                // Note gone (or never configured): tap re-opens configuration.
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_note_deleted))
                val intent = Intent(context, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                views.setOnClickPendingIntent(R.id.widget_root, pending(context, appWidgetId, intent))
            }

            manager.updateAppWidget(appWidgetId, views)
        }

        /** Call after any note edit/delete so titles and deleted states refresh. */
        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, SnippetsWidgetProvider::class.java)
            )
            for (id in ids) updateWidget(context, manager, id)
        }

        // requestCode = appWidgetId keeps each widget's PendingIntent distinct;
        // with the same requestCode the system would dedupe them and every
        // widget would copy the same note.
        private fun pending(context: Context, appWidgetId: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
