package com.kvelzer.snippets

import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import com.kvelzer.snippets.widget.TemplateCopyWidgetProvider
import com.kvelzer.snippets.widget.TemplateWidgetProvider

/**
 * Invisible trampoline for template-widget taps: takes the CURRENT CLIPBOARD
 * TEXT, fills the template's slot with it (persisting the value), and puts
 * the filled formatted result back on the clipboard. One tap turns
 * "copied value" into "copied filled snippet".
 *
 * Same focus dance as CopyNoteActivity — Android 10+ gates clipboard READS
 * the same way as writes, so both must happen in onWindowFocusChanged of a
 * briefly-focused activity, not in onCreate and not in a receiver.
 */
class FillTemplateActivity : Activity() {

    companion object {
        const val EXTRA_TEMPLATE_ID = "template_id"
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
        try {
            run()
        } finally {
            finish()
        }
    }

    private fun run() {
        val templateId = intent.getLongExtra(EXTRA_TEMPLATE_ID, -1L)
        val template = TemplateStore.get(this, templateId)
        if (template == null) {
            toast(R.string.note_deleted)
            // Both template widget kinds may point at the deleted entry.
            TemplateWidgetProvider.updateAllWidgets(this)
            TemplateCopyWidgetProvider.updateAllWidgets(this)
            return
        }
        when (TemplateFiller.fillFromClipboard(this, template)) {
            TemplateFiller.FillResult.EMPTY_CLIPBOARD -> toast(R.string.clipboard_empty)
            TemplateFiller.FillResult.NO_SLOT -> toast(R.string.template_no_slot)
            TemplateFiller.FillResult.FILLED ->
                // Below 13 there is no system overlay, so confirm ourselves.
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                    toast(R.string.filled_copied)
                }
        }
    }

    private fun toast(res: Int) {
        Toast.makeText(this, res, Toast.LENGTH_SHORT).show()
    }
}
