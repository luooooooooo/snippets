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
import com.kvelzer.snippets.FillTemplateActivity
import com.kvelzer.snippets.R
import com.kvelzer.snippets.TemplateStore

/**
 * Home-screen widget for templates: a tap fills the bound template's slot
 * with the CURRENT CLIPBOARD TEXT and puts the filled result back on the
 * clipboard (see FillTemplateActivity). Structure mirrors
 * SnippetsWidgetProvider — see there for the PendingIntent rules.
 */
class TemplateWidgetProvider : AppWidgetProvider() {

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
            val templateId = WidgetPrefs.getNoteId(context, appWidgetId)
            val template = templateId?.let { TemplateStore.get(context, it) }
            val views = RemoteViews(context.packageName, R.layout.widget_template)

            if (template != null) {
                views.setTextViewText(
                    R.id.widget_title,
                    template.title.ifBlank { context.getString(R.string.untitled) },
                )
                // 1x1: the { } glyph alone identifies the fill widget; the
                // deleted state below always keeps its text visible.
                views.setViewVisibility(
                    R.id.widget_title,
                    if (WidgetSizing.isCompact(manager, appWidgetId)) View.GONE else View.VISIBLE,
                )
                val intent = Intent(context, FillTemplateActivity::class.java)
                    .putExtra(FillTemplateActivity.EXTRA_TEMPLATE_ID, template.id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                views.setOnClickPendingIntent(R.id.widget_root, pending(context, appWidgetId, intent))
            } else {
                views.setTextViewText(R.id.widget_title, context.getString(R.string.widget_template_deleted))
                val intent = Intent(context, WidgetConfigActivity::class.java)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                views.setOnClickPendingIntent(R.id.widget_root, pending(context, appWidgetId, intent))
            }

            manager.updateAppWidget(appWidgetId, views)
        }

        fun updateAllWidgets(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(
                ComponentName(context, TemplateWidgetProvider::class.java)
            )
            for (id in ids) updateWidget(context, manager, id)
        }

        private fun pending(context: Context, appWidgetId: Int, intent: Intent): PendingIntent =
            PendingIntent.getActivity(
                context,
                appWidgetId,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
    }
}
