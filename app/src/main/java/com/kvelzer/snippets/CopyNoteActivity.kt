package com.kvelzer.snippets

import android.app.Activity
import android.os.Bundle
import android.widget.Toast
import com.kvelzer.snippets.widget.SnippetsWidgetProvider
import com.kvelzer.snippets.widget.TemplateCopyWidgetProvider
import com.kvelzer.snippets.widget.TemplateWidgetProvider

/**
 * Invisible trampoline for widget taps. Android 10+ rejects clipboard writes
 * from apps that are not the focused foreground app, so the widget cannot
 * copy from a BroadcastReceiver. Instead its PendingIntent launches this
 * translucent Activity, which briefly IS the focused app, writes the
 * clipboard, and finishes — the user only sees a toast (below Android 13).
 *
 * The copy happens in onWindowFocusChanged, not onCreate: during onCreate the
 * window doesn't have focus yet and the write can be silently dropped.
 */
class CopyNoteActivity : Activity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"

        /** When true the id refers to TemplateStore: copy the template as-is. */
        const val EXTRA_TEMPLATE = "is_template"
    }

    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // No setContentView: the translucent theme means nothing is drawn.
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus || done) return
        done = true

        val noteId = intent.getLongExtra(EXTRA_NOTE_ID, -1L)
        val isTemplate = intent.getBooleanExtra(EXTRA_TEMPLATE, false)
        val note = (if (isTemplate) TemplateStore else NoteStore).get(this, noteId)
        if (note == null) {
            // Entry was deleted after the widget was placed.
            Toast.makeText(this, R.string.note_deleted, Toast.LENGTH_SHORT).show()
            if (isTemplate) {
                // Both template widget kinds may point at the deleted entry.
                TemplateCopyWidgetProvider.updateAllWidgets(this)
                TemplateWidgetProvider.updateAllWidgets(this)
            } else {
                SnippetsWidgetProvider.updateAllWidgets(this)
            }
        } else {
            ClipboardHelper.copyNote(this, note)
            ClipboardHelper.showCopiedFeedback(this)
        }
        finish()
    }
}
