package com.kvelzer.snippets

import android.text.Html

/**
 * The stored HTML is the source of truth and is never round-tripped through
 * a lossy converter anymore: the WebView editor edits it as HTML, and the
 * clipboard receives it verbatim. This helper only DERIVES plain text from
 * it — for list previews and the clipboard's plain-text fallback — where
 * fromHtml's lossiness (tables flattened, sizes ignored) doesn't matter.
 */
object HtmlConverter {

    fun plainText(html: String): String =
        Html.fromHtml(html, Html.FROM_HTML_MODE_LEGACY).toString().trim('\n')

    private val VISUAL_CONTENT =
        Regex("""<(img|hr|table|video|audio|svg|iframe|embed|object)\b""", RegexOption.IGNORE_CASE)

    /**
     * True when the HTML is worth keeping: has visible text, or contains
     * non-text content (an image, a table...) that plain-text extraction
     * can't see. Used by the editor's discard-empty-new-note check.
     */
    fun hasContent(html: String): Boolean =
        plainText(html).isNotBlank() || VISUAL_CONTENT.containsMatchIn(html)
}
