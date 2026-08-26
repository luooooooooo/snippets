package com.kvelzer.snippets

import android.widget.EditText

/**
 * Helpers for editing Markdown source in an EditText: wrapping selections,
 * prepending line prefixes, inserting link/image/table snippets. Designed to
 * mirror the muscle memory of desktop Markdown editors (Typora/Obsidian):
 * a format button wraps the selection or inserts an empty pair to type into.
 */
object MarkdownEditorHelper {

    /** Wrap the current selection (or insert an empty pair at cursor). */
    fun wrap(et: EditText, prefix: String, suffix: String, placeholder: String = "") {
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(0)
        val text = et.text
        val selected = if (start == end) placeholder else text.substring(start, end)
        val replacement = "$prefix$selected$suffix"
        text.replace(start, end, replacement)
        // Place cursor inside the pair when there was no selection.
        if (start == end && placeholder.isEmpty()) {
            et.setSelection(start + prefix.length)
        } else {
            et.setSelection(start + prefix.length, start + prefix.length + selected.length)
        }
    }

    /** Prepend [prefix] to every line that intersects the selection (or the
     *  current line if the selection is collapsed). Toggles off when every
     *  affected line already starts with [prefix]. */
    fun prependLines(et: EditText, prefix: String) {
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(0)
        val text = et.text
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = if (end == text.length) text.length
        else text.indexOf('\n', end).let { if (it < 0) text.length else it }

        val block = text.substring(lineStart, lineEnd)
        val lines = block.split("\n")
        val allHave = lines.all { it.startsWith(prefix) || it.isEmpty() }
        val transformed = lines.joinToString("\n") { line ->
            if (allHave && line.startsWith(prefix)) line.removePrefix(prefix)
            else if (!allHave) "$prefix$line"
            else line
        }
        text.replace(lineStart, lineEnd, transformed)
        et.setSelection(lineStart, lineStart + transformed.length)
    }

    /** Insert a raw snippet at the cursor, replacing any selection. */
    fun insert(et: EditText, snippet: String) {
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(0)
        et.text.replace(start, end, snippet)
        et.setSelection(start + snippet.length)
    }

    /** Cycle heading level on the current line: none → H1 → H2 → H3 → none. */
    fun toggleHeading(et: EditText) {
        val start = et.selectionStart.coerceAtLeast(0)
        val text = et.text
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = if (start == text.length) text.length
        else text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val newLine = when {
            line.startsWith("### ") -> line.removePrefix("### ")
            line.startsWith("## ") -> line.replaceFirst("## ", "### ")
            line.startsWith("# ") -> line.replaceFirst("# ", "## ")
            else -> "# $line"
        }
        text.replace(lineStart, lineEnd, newLine)
        et.setSelection(lineStart + newLine.length)
    }

    /** Insert a fenced code block; cursor lands inside. */
    fun insertCodeBlock(et: EditText) {
        val snippet = "\n```\n\n```\n"
        insert(et, snippet)
        // Move cursor between the fences.
        val pos = et.selectionStart - "\n```\n".length
        et.setSelection(pos)
    }

    /** Insert a 3×3 table template. */
    fun insertTable(et: EditText) {
        val snippet = buildString {
            append("\n| 列1 | 列2 | 列3 |\n")
            append("| --- | --- | --- |\n")
            append("|     |     |     |\n")
        }
        insert(et, snippet)
    }

    /** Wrap selection as a link; [url] may be empty to leave a placeholder. */
    fun insertLink(et: EditText, url: String) {
        val start = et.selectionStart.coerceAtLeast(0)
        val end = et.selectionEnd.coerceAtLeast(0)
        val text = et.text
        val label = if (start == end) "链接文字" else text.substring(start, end)
        val replacement = "[$label]($url)"
        text.replace(start, end, replacement)
        et.setSelection(start + 1, start + 1 + label.length)
    }

    /** Insert an image snippet. */
    fun insertImage(et: EditText, url: String) {
        val snippet = "![图片描述]($url)"
        insert(et, snippet)
    }

    /** Insert a horizontal rule on its own line. */
    fun insertHr(et: EditText) {
        insert(et, "\n\n---\n\n")
    }

    /** Toggle a task-list prefix (- [ ] / - [x]) on the current line. */
    fun toggleTask(et: EditText) {
        val start = et.selectionStart.coerceAtLeast(0)
        val text = et.text
        val lineStart = text.lastIndexOf('\n', start - 1) + 1
        val lineEnd = if (start == text.length) text.length
        else text.indexOf('\n', start).let { if (it < 0) text.length else it }
        val line = text.substring(lineStart, lineEnd)
        val newLine = when {
            line.startsWith("- [x] ") -> line.replaceFirst("- [x] ", "- [ ] ")
            line.startsWith("- [ ] ") -> line.removePrefix("- [ ] ")
            line.startsWith("- ") -> line.replaceFirst("- ", "- [ ] ")
            else -> "- [ ] $line"
        }
        text.replace(lineStart, lineEnd, newLine)
        et.setSelection(lineStart + newLine.length)
    }
}
