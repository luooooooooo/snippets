package com.kvelzer.snippets.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.kvelzer.snippets.NoteStore
import com.kvelzer.snippets.R
import com.kvelzer.snippets.SnippetsApp
import com.kvelzer.snippets.TemplateStore

/**
 * Shown when a widget is placed (APPWIDGET_CONFIGURE) and when a widget in
 * the "deleted" state is tapped. Serves BOTH widget kinds: the provider this
 * widget belongs to (looked up from AppWidgetManager) decides whether the
 * picker lists snippets or templates.
 *
 * Widget-config contract: result defaults to RESULT_CANCELED (so the launcher
 * discards the widget if the user backs out) and RESULT_OK confirms it.
 */
class WidgetConfigActivity : AppCompatActivity() {

    private var appWidgetId = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        SnippetsApp.applyColorTheme(this)
        super.onCreate(savedInstanceState)

        appWidgetId = intent.getIntExtra(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        )
        setResult(RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }

        val manager = AppWidgetManager.getInstance(this)
        val providerClass = manager.getAppWidgetInfo(appWidgetId)?.provider?.className
        // Both template widget kinds (fill + copy-as-is) pick from TemplateStore.
        val isTemplate = providerClass == TemplateWidgetProvider::class.java.name ||
            providerClass == TemplateCopyWidgetProvider::class.java.name
        val store = if (isTemplate) TemplateStore else NoteStore

        setContentView(R.layout.activity_widget_config)
        findViewById<TextView>(R.id.config_title).setText(
            if (isTemplate) R.string.config_prompt_template else R.string.config_prompt
        )

        val entries = store.all(this)
        val emptyView = findViewById<TextView>(R.id.config_empty)
        val list = findViewById<ListView>(R.id.config_list)

        if (entries.isEmpty()) {
            emptyView.setText(if (isTemplate) R.string.config_empty_template else R.string.config_empty)
            emptyView.visibility = View.VISIBLE
            return
        }

        val labels = entries.map { it.title.ifBlank { getString(R.string.untitled) } }
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        list.setOnItemClickListener { _, _, position, _ ->
            WidgetPrefs.setNoteId(this, appWidgetId, entries[position].id)
            when (providerClass) {
                TemplateWidgetProvider::class.java.name ->
                    TemplateWidgetProvider.updateWidget(this, manager, appWidgetId)
                TemplateCopyWidgetProvider::class.java.name ->
                    TemplateCopyWidgetProvider.updateWidget(this, manager, appWidgetId)
                else ->
                    SnippetsWidgetProvider.updateWidget(this, manager, appWidgetId)
            }
            setResult(RESULT_OK, resultIntent())
            finish()
        }
    }

    private fun resultIntent(): Intent =
        Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
}
