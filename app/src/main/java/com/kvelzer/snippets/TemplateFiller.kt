package com.kvelzer.snippets

import android.content.ClipboardManager
import android.content.Context
import com.kvelzer.snippets.widget.TemplateWidgetProvider

/**
 * The template slot is a marker element in the note's HTML:
 *
 *     <span data-slot="1">current value</span>
 *
 * Filling swaps the span's CONTENT and keeps the span, so the template stays
 * fillable forever and remembers its last value. The span carries no styling
 * of its own (the editor highlights it with page CSS only), so it is inert
 * when pasted into Gmail.
 */
object TemplateFiller {

    const val SLOT_MARKER = "data-slot"

    enum class FillResult { FILLED, EMPTY_CLIPBOARD, NO_SLOT }

    /**
     * The full "widget tap" operation, shared by FillTemplateActivity and the
     * template list's fill button: clipboard text -> slot, save, copy the
     * filled result back, refresh template widgets. Caller must be the
     * focused foreground app (Android 10+ clipboard rule).
     */
    fun fillFromClipboard(context: Context, template: Note): FillResult {
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        val value = clipboard.primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)?.coerceToText(context)?.toString()
            ?.trim()
        if (value.isNullOrEmpty()) return FillResult.EMPTY_CLIPBOARD
        val filled = fill(template.html, value) ?: return FillResult.NO_SLOT
        val saved = TemplateStore.save(context, template.copy(html = filled))
        ClipboardHelper.copyNote(context, saved)
        TemplateWidgetProvider.updateAllWidgets(context)
        return FillResult.FILLED
    }

    private val SLOT_OPEN_RE =
        Regex("""<span\b[^>]*\bdata-slot\b[^>]*>""", RegexOption.IGNORE_CASE)

    fun hasSlot(html: String): Boolean = SLOT_OPEN_RE.containsMatchIn(html)

    /** Current text inside the slot, entities not decoded. Null when no slot. */
    fun slotValue(html: String): String? {
        val open = SLOT_OPEN_RE.find(html) ?: return null
        val contentStart = open.range.last + 1
        val contentEnd = findSlotEnd(html, contentStart) ?: return null
        return html.substring(contentStart, contentEnd)
    }

    /**
     * Replaces the slot's content with [plainValue] (HTML-escaped, newlines
     * become <br>). Returns null when the HTML has no slot.
     */
    fun fill(html: String, plainValue: String): String? {
        val open = SLOT_OPEN_RE.find(html) ?: return null
        val contentStart = open.range.last + 1
        val contentEnd = findSlotEnd(html, contentStart) ?: return null
        return html.substring(0, contentStart) + escape(plainValue) + html.substring(contentEnd)
    }

    /**
     * Index of the slot's closing </span>, honoring nested <span>s inside the
     * slot (a plain regex would stop at the first close tag and mangle slots
     * that still contain formatted default text).
     */
    private fun findSlotEnd(html: String, from: Int): Int? {
        var depth = 0
        var i = from
        while (i < html.length) {
            val lt = html.indexOf('<', i)
            if (lt < 0) return null
            val gt = html.indexOf('>', lt)
            if (gt < 0) return null
            val tag = html.substring(lt, gt + 1).lowercase()
            when {
                tag.startsWith("<span") -> depth++
                tag.startsWith("</span") -> {
                    if (depth == 0) return lt
                    depth--
                }
            }
            i = gt + 1
        }
        return null
    }

    private fun escape(text: String): String = buildString(text.length + 16) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '\n' -> append("<br>")
                else -> append(c)
            }
        }
    }
}
