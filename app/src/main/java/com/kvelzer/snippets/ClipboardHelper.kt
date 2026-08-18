package com.kvelzer.snippets

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast

object ClipboardHelper {

    /**
     * Puts the note on the clipboard with BOTH representations: a plain-text
     * fallback and the stored HTML, verbatim — whatever was pasted in or
     * edited comes back out unchanged. ClipData.newHtmlText is what makes
     * rich paste targets (Gmail's composer) receive formatting — newPlainText
     * would silently drop it.
     */
    fun copyNote(context: Context, note: Note) {
        val plain = HtmlConverter.plainText(note.html)
        val clipboard = context.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newHtmlText(note.title.ifBlank { "Snippet" }, plain, note.html))
    }

    /**
     * 复制后给出"已复制"提示。始终弹出 Toast，确保用户明确知道已复制到剪贴板
     * （即使系统本身也有"已复制"浮层，双提示在 Android 13+ 上也无害）。
     */
    fun showCopiedFeedback(context: Context) {
        Toast.makeText(context, R.string.copied, Toast.LENGTH_SHORT).show()
    }
}
