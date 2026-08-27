package com.kvelzer.snippets

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode

/**
 * 内容自动排版与粘贴清洗工具。
 *
 * 解决从微信公众号等平台复制内容后格式丢失的问题：
 * - 微信大量使用嵌套 <section> + 内联样式，粘贴后冗余标签被吞，换行缩进丢失
 * - 代码行被拆成独立 <p>/<div>，视觉上挤成一团
 *
 * 处理策略：
 * 1. 展开 <section> 等容器标签，保留内容
 * 2. 清理冗余内联样式，保留语义标签
 * 3. 识别代码块（<pre>、多行文本、等宽字体），统一为 <pre><code>
 * 4. 规范化列表、段落、换行
 * 5. 清理空标签和多余空白
 */
object ContentFormatter {

    /** 粘贴时的轻量清洗：保留格式，去除微信冗余标签。 */
    fun sanitizePastedHtml(html: String): String {
        if (html.isBlank()) return html
        val doc = Jsoup.parseBodyFragment(html)
        val body = doc.body()
        unwrapSections(body)
        removeWechatLineNumbers(body)
        cleanRedundantStyles(body)
        detectCodeBlocks(body)
        normalizeBreaks(body)
        return body.html()
    }

    /** 深度排版：在清洗基础上做段落、列表、空行整理。 */
    fun formatHtml(html: String): String {
        if (html.isBlank()) return html
        val doc = Jsoup.parseBodyFragment(html)
        val body = doc.body()

        unwrapSections(body)
        removeWechatLineNumbers(body)
        cleanRedundantStyles(body)
        normalizeNumberedLists(body)
        splitInlineNumberedLists(body)
        detectCodeBlocks(body)
        normalizeLists(body)
        normalizeParagraphs(body)
        normalizeBreaks(body)
        removeEmptyBlocks(body)

        return body.html()
    }

    // ===== 展开容器标签 =====

    private fun unwrapSections(root: Element) {
        val sections = root.select("section").toList()
        for (el in sections) {
            unwrapElement(el)
        }
        val spans = root.select("span").toList()
        for (el in spans) {
            if (el.attributes().size() == 0) {
                unwrapElement(el)
            }
        }
    }

    /** 展开一个元素：将子节点移到父元素中该元素的位置，然后删除自身。 */
    private fun unwrapElement(el: Element) {
        val children = el.childNodes().toList()
        for (child in children) {
            el.before(child)
        }
        el.remove()
    }

    /**
     * 移除微信公众号代码块的行号。
     * 微信行号通常是灰色/右对齐的纯数字 <span>，紧跟在内容前面。
     * 必须在 cleanRedundantStyles 之前调用（需要保留 style 判断）。
     */
    private fun removeWechatLineNumbers(root: Element) {
        val candidates = root.select("span, em, i").toList()
        for (el in candidates) {
            val text = el.wholeText().trim()
            if (text.isEmpty()) continue
            // 内容必须是纯数字，且在合理行号范围
            val num = text.toIntOrNull() ?: continue
            if (num < 1 || num > 999) continue
            val style = el.attr("style").lowercase()
            // 灰色文字 或 右对齐
            val isGray = Regex("color:\\s*(#([89a-f][89a-f][89a-f]|ccc|999|888|777|bbb|aaa)|gray|grey)", RegexOption.IGNORE_CASE)
                .containsMatchIn(style)
            val isRightAlign = style.contains("text-align") && style.contains("right")
            val isMono = style.contains("monospace") || style.contains("consolas")
            if ((isGray || isRightAlign) && !isMono) {
                // 后面必须有内容节点
                val next = el.nextSibling()
                if (next != null && next.toString().trim().isNotEmpty()) {
                    el.remove()
                }
            }
        }
    }

    // ===== 清理冗余样式 =====

    private fun cleanRedundantStyles(root: Element) {
        val all = root.allElements.toList()
        for (el in all) {
            el.removeAttr("class")
            val attrsToRemove = el.attributes()
                .filter { it.key.startsWith("data-") }
                .map { it.key }
            for (attr in attrsToRemove) el.removeAttr(attr)
            val tag = el.tagName()
            if (tag != "pre" && tag != "code") {
                el.removeAttr("style")
            }
            el.removeAttr("width")
            el.removeAttr("height")
        }
    }

    // ===== 代码块识别 =====

    private fun detectCodeBlocks(root: Element) {
        // 处理已有的 pre
        for (pre in root.select("pre").toList()) {
            if (pre.selectFirst("code") == null) {
                val code = Element("code")
                val children = pre.childNodes().toList()
                for (child in children) {
                    code.appendChild(child)
                }
                pre.empty()
                pre.appendChild(code)
            }
            // pre 内的 <br> 转为换行符
            for (br in pre.select("br").toList()) {
                br.replaceWith(TextNode("\n"))
            }
        }

        // 识别等宽字体的块级元素
        val monospaceBlocks = root.allElements.filter { el ->
            val style = el.attr("style").lowercase()
            el.isBlock && (style.contains("monospace") ||
                    style.contains("consolas") ||
                    style.contains("courier") ||
                    style.contains("monaco"))
        }.toList()

        for (el in monospaceBlocks) {
            if (el.tagName() == "pre") continue
            wrapAsCodeBlock(el)
        }

        // 识别连续代码行（微信代码常被拆成多个 <p>）
        detectConsecutiveCodeLines(root)
    }

    private fun detectConsecutiveCodeLines(root: Element) {
        val children = root.children().toList()
        var i = 0
        while (i < children.size) {
            val el = children[i]
            if (isCodeLineCandidate(el)) {
                val group = mutableListOf<Element>()
                var j = i
                while (j < children.size && isCodeLineCandidate(children[j])) {
                    group.add(children[j])
                    j++
                }
                if (group.size >= 3) {
                    val codeText = group.joinToString("\n") { it.wholeText().trimEnd() }
                    val pre = Element("pre")
                    val code = Element("code").appendText(codeText)
                    pre.appendChild(code)
                    group.first().replaceWith(pre)
                    for (k in 1 until group.size) {
                        group[k].remove()
                    }
                    i = j
                    continue
                }
            }
            i++
        }
    }

    private fun isCodeLineCandidate(el: Element): Boolean {
        val tag = el.tagName()
        if (tag !in listOf("p", "div", "span")) return false
        if (el.children().any { it.tagName() in listOf("img", "a", "h1", "h2", "h3", "blockquote", "ul", "ol") }) return false
        val text = el.wholeText().trim()
        if (text.isEmpty()) return false
        if (text.length > 200) return false
        // 编号列表行不当作代码行（由 normalizeNumberedLists 处理）
        if (Regex("^\\d+[.．、)]\\s").containsMatchIn(text)) return false
        val codePatterns = listOf("=", ";", "{", "}", "(", ")", "[", "]", "->", "=>",
            "import ", "function ", "class ", "def ", "const ", "let ", "var ", "return ",
            "if ", "for ", "while ", "public ", "private ", "void ", "int ", "string ")
        return codePatterns.any { text.contains(it) }
    }

    private fun wrapAsCodeBlock(el: Element) {
        val text = el.wholeText()
        val pre = Element("pre")
        val code = Element("code").appendText(text)
        pre.appendChild(code)
        el.replaceWith(pre)
    }

    // ===== 列表规范化 =====

    private fun normalizeLists(root: Element) {
        for (list in root.select("ul, ol").toList()) {
            for (child in list.children().toList()) {
                if (child.tagName() != "li") {
                    child.tagName("li")
                }
            }
        }
    }

    /**
     * 识别连续的"数字. 文字"段落，转为有序列表 <ol><li>。
     * 微信公众号的编号列表常被拆成独立 <p>，需要重新合并。
     */
    private fun normalizeNumberedLists(root: Element) {
        val children = root.children().toList()
        var i = 0
        while (i < children.size) {
            val el = children[i]
            val match = numberedLineMatch(el)
            if (match != null) {
                val group = mutableListOf<Pair<Element, String>>()
                var j = i
                var expectedNum = match.first
                while (j < children.size) {
                    val m = numberedLineMatch(children[j])
                    if (m != null && m.first == expectedNum) {
                        group.add(children[j] to m.second)
                        expectedNum++
                        j++
                    } else {
                        break
                    }
                }
                if (group.size >= 2) {
                    val ol = Element("ol")
                    for ((_, text) in group) {
                        ol.appendChild(Element("li").text(text))
                    }
                    group.first().first.replaceWith(ol)
                    for (k in 1 until group.size) {
                        group[k].first.remove()
                    }
                    i = j
                    continue
                }
            }
            i++
        }
    }

    private fun numberedLineMatch(el: Element): Pair<Int, String>? {
        val tag = el.tagName()
        if (tag !in listOf("p", "div", "span")) return null
        // 有子元素的不处理（可能是富文本）
        if (el.children().isNotEmpty()) return null
        val text = el.wholeText().trim()
        // 支持英文点、全角句号、顿号、右括号
        val regex = Regex("^(\\d+)[.．、)]\\s+(.+)$")
        val match = regex.find(text) ?: return null
        val num = match.groupValues[1].toIntOrNull() ?: return null
        val content = match.groupValues[2].trim()
        if (content.isEmpty()) return null
        return num to content
    }

    /**
     * 拆分同一段落内的内联编号列表。
     * 微信公众号常把"1．xxx；2．xxx；3．xxx"挤在一个 <p> 里，
     * 需要拆成引导语 + <ol><li> 结构。
     */
    private fun splitInlineNumberedLists(root: Element) {
        val paragraphs = root.select("p, div").toList()
        for (p in paragraphs) {
            if (p.children().isNotEmpty()) continue
            val text = p.wholeText()
            // 匹配编号：数字 + 点/全角句号/顿号/右括号 + 可选空格
            val pattern = Regex("(\\d+)[.．、)]\\s*")
            val matches = pattern.findAll(text).toList()
            if (matches.size < 2) continue

            val firstMatch = matches.first()
            // 编号之前的引导语
            val prefix = text.substring(0, firstMatch.range.first).trim()

            // 提取每个编号项的内容
            val items = mutableListOf<String>()
            for (i in matches.indices) {
                val start = matches[i].range.last + 1
                val end = if (i + 1 < matches.size) matches[i + 1].range.first else text.length
                var itemText = text.substring(start, end).trim()
                // 去掉末尾的分号或句号
                itemText = itemText.removeSuffix("；").removeSuffix(";")
                    .removeSuffix("。").removeSuffix(".").trim()
                if (itemText.isNotEmpty()) items.add(itemText)
            }

            if (items.size < 2) continue

            // 引导语保留为段落
            if (prefix.isNotEmpty()) {
                p.before(Element("p").text(prefix))
            }
            // 编号项转为有序列表
            val ol = Element("ol")
            for (item in items) {
                ol.appendChild(Element("li").text(item))
            }
            p.before(ol)
            p.remove()
        }
    }

    // ===== 段落规范化 =====

    private fun normalizeParagraphs(root: Element) {
        val blockTags = setOf("p", "li", "pre", "code", "blockquote",
            "h1", "h2", "h3", "h4", "h5", "h6")
        val textNodes = root.textNodes().toList()
        for (tn in textNodes) {
            if (tn.text().isBlank()) continue
            val parent = tn.parent() as? Element ?: continue
            if (parent.tagName() in blockTags) continue
            val p = Element("p")
            tn.replaceWith(p)
            p.appendChild(tn)
        }

        for (div in root.select("div").toList()) {
            if (div.children().none { it.isBlock }) {
                div.tagName("p")
            }
        }
    }

    // ===== 换行规范化 =====

    private fun normalizeBreaks(root: Element) {
        // 合并连续的 <br>（超过 2 个的，保留 2 个）
        val allEls = root.allElements.toList()
        for (el in allEls) {
            val children = el.childNodes().toList()
            var brCount = 0
            for (node in children) {
                if (node is Element && node.tagName() == "br") {
                    brCount++
                    if (brCount > 2) node.remove()
                } else if (node is TextNode && node.text().isBlank()) {
                    // 跳过空白
                } else {
                    brCount = 0
                }
            }
        }

        // 代码块外的连续换行转为 <br>
        for (el in root.select("p, li, blockquote").toList()) {
            if (el.selectFirst("pre, code") != null) continue
            val textNodes = el.textNodes().toList()
            for (tn in textNodes) {
                val text = tn.text()
                if (!text.contains("\n")) continue
                val parts = text.split("\n")
                val parent = tn.parent() as? Element ?: continue
                val idx = parent.childNodes().indexOf(tn)
                // 先移除原节点
                tn.remove()
                var insertIdx = idx
                for ((k, part) in parts.withIndex()) {
                    if (k > 0) {
                        val br = Element("br")
                        insertChildAt(parent, insertIdx, br)
                        insertIdx++
                    }
                    if (part.isNotEmpty()) {
                        insertChildAt(parent, insertIdx, TextNode(part))
                        insertIdx++
                    }
                }
            }
        }
    }

    /** 在指定索引插入子节点。 */
    private fun insertChildAt(parent: Element, index: Int, node: Node) {
        val children = parent.childNodes()
        if (index >= children.size) {
            parent.appendChild(node)
        } else {
            children[index].before(node)
        }
    }

    // ===== 清理空标签 =====

    private fun removeEmptyBlocks(root: Element) {
        var changed = true
        while (changed) {
            changed = false
            val blocks = root.select("p, div, span, li").toList()
            for (el in blocks) {
                if (el.selectFirst("img, br, pre, code, table, ul, ol, blockquote") != null) continue
                if (el.wholeText().isBlank()) {
                    el.remove()
                    changed = true
                }
            }
        }
    }
}
