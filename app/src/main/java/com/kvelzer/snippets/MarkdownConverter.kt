package com.kvelzer.snippets

import org.commonmark.Extension
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * Bidirectional Markdown ↔ HTML converter.
 *
 * MD→HTML uses commonmark-java with GFM extensions (tables, strikethrough,
 * task lists). HTML→MD is a hand-written walker covering the tags the
 * WebView editor and commonmark produce, so round-tripping common content
 * stays readable.
 */
object MarkdownConverter {

    private val extensions: List<Extension> = listOf(
        TablesExtension.create(),
        StrikethroughExtension.create(),
        TaskListItemsExtension.create(),
    )

    private val parser: Parser = Parser.builder()
        .extensions(extensions)
        .build()

    private val renderer: HtmlRenderer = HtmlRenderer.builder()
        .extensions(extensions)
        .build()

    /** Parse Markdown text into HTML (the form stored by the app). */
    fun toHtml(markdown: String): String {
        val document = parser.parse(markdown.trim())
        return renderer.render(document).trim()
    }

    /** Convert stored HTML back to Markdown. Best-effort, covers common tags. */
    fun toMarkdown(html: String): String {
        if (html.isBlank()) return ""
        val doc = Jsoup.parseBodyFragment(html)
        val body = doc.body()
        val sb = StringBuilder()
        for (child in body.childNodes()) {
            sb.append(nodeToMarkdown(child, 0))
        }
        return sb.toString().trimEnd() + "\n"
    }

    // ---- HTML → MD walker --------------------------------------------------

    private fun nodeToMarkdown(node: Node, depth: Int): String {
        return when (node) {
            is TextNode -> escapeText(node.text())
            is Element -> elementToMarkdown(node, depth)
            else -> ""
        }
    }

    private fun elementToMarkdown(el: Element, depth: Int): String {
        return when (el.tagName().lowercase()) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val level = el.tagName().substring(1).toInt()
                "#".repeat(level) + " " + inlineText(el) + "\n\n"
            }
            "p" -> inlineText(el) + "\n\n"
            "br" -> "  \n"
            "hr" -> "---\n\n"
            "strong", "b" -> "**${inlineText(el)}**"
            "em", "i" -> "*${inlineText(el)}*"
            "u" -> "<u>${inlineText(el)}</u>"
            "s", "strike", "del" -> "~~${inlineText(el)}~~"
            "code" -> {
                if (el.parent()?.tagName()?.lowercase() == "pre") {
                    inlineText(el) // handled by <pre>
                } else {
                    "`${inlineText(el)}`"
                }
            }
            "pre" -> {
                val code = el.text()
                "```\n$code\n```\n\n"
            }
            "blockquote" -> {
                val inner = childrenMarkdown(el, depth).trimEnd()
                inner.lines().joinToString("\n") { "> $it" } + "\n\n"
            }
            "ul" -> listToMarkdown(el, depth, ordered = false)
            "ol" -> listToMarkdown(el, depth, ordered = true)
            "li" -> {
                // Handled by listToMarkdown; if called directly, just render content.
                inlineText(el) + "\n"
            }
            "a" -> {
                val href = el.attr("href")
                val text = inlineText(el)
                if (href.isNotEmpty()) "[$text]($href)" else text
            }
            "img" -> {
                val src = el.attr("src")
                val alt = el.attr("alt")
                "![$alt]($src)"
            }
            "table" -> tableToMarkdown(el)
            "thead", "tbody", "tr", "td", "th" -> {
                // Handled by tableToMarkdown; if called standalone, render inline.
                inlineText(el)
            }
            "div", "span" -> {
                // Pass-through: render children.
                childrenMarkdown(el, depth)
            }
            else -> inlineText(el)
        }
    }

    private fun inlineText(el: Element): String {
        val sb = StringBuilder()
        for (child in el.childNodes()) {
            sb.append(nodeToMarkdown(child, 0))
        }
        return sb.toString()
    }

    private fun childrenMarkdown(el: Element, depth: Int): String {
        val sb = StringBuilder()
        for (child in el.childNodes()) {
            sb.append(nodeToMarkdown(child, depth))
        }
        return sb.toString()
    }

    private fun listToMarkdown(el: Element, depth: Int, ordered: Boolean): String {
        val sb = StringBuilder()
        val indent = "  ".repeat(depth)
        var index = 1
        for (li in el.children()) {
            if (li.tagName().lowercase() != "li") continue
            val marker = if (ordered) "${index}. " else "- "
            // Task list item?
            val checkbox = li.selectFirst("input[type=checkbox]")
            val taskPrefix = if (checkbox != null) {
                if (checkbox.hasAttr("checked")) "[x] " else "[ ] "
            } else ""
            // Remove the checkbox from inline text rendering.
            checkbox?.remove()
            val content = inlineText(li).trim()
            sb.append("$indent$marker$taskPrefix$content\n")
            // Nested lists
            for (nested in li.children()) {
                if (nested.tagName().lowercase() in listOf("ul", "ol")) {
                    sb.append(listToMarkdown(nested, depth + 1, nested.tagName().lowercase() == "ol"))
                }
            }
            index++
        }
        if (depth == 0) sb.append("\n")
        return sb.toString()
    }

    private fun tableToMarkdown(el: Element): String {
        val sb = StringBuilder()
        val rows = el.select("tr")
        if (rows.isEmpty()) return ""
        // Header
        val headerRow = rows.first() ?: return ""
        val headerCells = headerRow.select("th,td")
        sb.append("| ${headerCells.joinToString(" | ") { it.text().trim() }} |\n")
        sb.append("| ${headerCells.joinToString(" | ") { "---" }} |\n")
        // Body
        for (row in rows.drop(1)) {
            val cells = row.select("td,th")
            sb.append("| ${cells.joinToString(" | ") { it.text().trim() }} |\n")
        }
        sb.append("\n")
        return sb.toString()
    }

    private fun escapeText(text: String): String {
        // Minimal escaping: backslashes and the chars that start Markdown syntax
        // at line boundaries. Inline escaping is intentionally light to keep
        // output readable — the HTML round-trip is the source of truth.
        return text
            .replace("\\", "\\\\")
    }
}
