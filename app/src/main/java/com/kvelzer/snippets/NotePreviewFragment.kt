package com.kvelzer.snippets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.TextView
import androidx.fragment.app.Fragment

/**
 * Read-only preview pane for the two-pane (tablet/foldable) layout. Shows the
 * selected note's title and rendered HTML, plus a copy button. Tapping edit
 * opens the full EditorActivity.
 */
class NotePreviewFragment : Fragment() {

    private var note: Note? = null

    companion object {
        private const val ARG_NOTE_ID = "note_id"
        private const val ARG_TEMPLATE = "is_template"

        fun newInstance(noteId: Long, isTemplate: Boolean): NotePreviewFragment {
            return NotePreviewFragment().apply {
                arguments = Bundle().apply {
                    putLong(ARG_NOTE_ID, noteId)
                    putBoolean(ARG_TEMPLATE, isTemplate)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        return inflater.inflate(R.layout.fragment_note_preview, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val noteId = arguments?.getLong(ARG_NOTE_ID) ?: return
        val isTemplate = arguments?.getBoolean(ARG_TEMPLATE) ?: false
        val store = if (isTemplate) TemplateStore else NoteStore
        note = store.get(requireContext(), noteId)
        bind(view)
    }

    private fun bind(view: View) {
        val n = note ?: return
        view.findViewById<TextView>(R.id.preview_title).text =
            n.title.ifBlank { getString(R.string.untitled) }
        val webView = view.findViewById<WebView>(R.id.preview_web)
        webView.settings.javaScriptEnabled = false
        webView.loadDataWithBaseURL(null, n.html, "text/html", "UTF-8", null)
        view.findViewById<View>(R.id.preview_copy).setOnClickListener {
            ClipboardHelper.copyNote(requireContext(), n, isTemplate = arguments?.getBoolean(ARG_TEMPLATE) ?: false)
            ClipboardHelper.showCopiedFeedback(requireContext())
        }
        view.findViewById<View>(R.id.preview_edit).setOnClickListener {
            startActivity(
                android.content.Intent(requireContext(), EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_NOTE_ID, n.id)
                    .putExtra(EditorActivity.EXTRA_TEMPLATE, arguments?.getBoolean(ARG_TEMPLATE) ?: false)
            )
        }
    }
}
