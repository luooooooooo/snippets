package com.kvelzer.snippets

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kvelzer.snippets.widget.SnippetsWidgetProvider
import com.kvelzer.snippets.widget.TemplateCopyWidgetProvider
import com.kvelzer.snippets.widget.TemplateWidgetProvider
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_TEMPLATE = "template"
        private const val NO_ID = 0L
        private const val KEY_HTML = "html"
        private const val KEY_SOURCE_MODE = "source_mode"
        private const val KEY_MARKDOWN_MODE = "markdown_mode"
        private const val KEY_MARKDOWN_PREVIEW = "markdown_preview"

        private val COLOR_VALUES = arrayOf(
            "#D32F2F", "#F57C00", "#388E3C", "#1976D2", "#7B1FA2", "#616161",
        )
        private val HIGHLIGHT_VALUES = arrayOf(
            "transparent", "#FFF59D", "#A5D6A7", "#90CAF9", "#F8BBD0",
        )
    }

    private lateinit var titleEdit: EditText
    private lateinit var webView: WebView
    private lateinit var sourceEdit: EditText
    private lateinit var markdownEdit: EditText
    private lateinit var previewWebView: WebView
    private lateinit var editTags: com.google.android.material.chip.ChipGroup
    private lateinit var metaInfo: TextView
    private lateinit var formatPanel: View

    private val currentTags = mutableSetOf<String>()

    @Volatile
    private var latestHtml = ""

    private var noteId = NO_ID
    private var deleted = false
    private var pageReady = false
    private var sourceMode = false
    private var markdownMode = false
    private var markdownPreview = false
    private var webViewDestroyed = false
    private var panelVisible = false

    private var isTemplate = false
    private val store: JsonNoteStore get() = if (isTemplate) TemplateStore else NoteStore

    private var loadedTitle = ""
    private var loadedHtml = ""
    private var loadedTags = emptyList<String>()

    private val exportMdLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            uri?.let { exportMarkdown(it) }
        }

    private val exportTextLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
            uri?.let { exportPlainText(it) }
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        SnippetsApp.applyColorTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)

        titleEdit = findViewById(R.id.edit_title)
        webView = findViewById(R.id.edit_body)
        sourceEdit = findViewById(R.id.edit_source)
        markdownEdit = findViewById(R.id.edit_markdown)
        previewWebView = findViewById(R.id.markdown_preview)
        editTags = findViewById(R.id.edit_tags)
        metaInfo = findViewById(R.id.meta_info)
        formatPanel = findViewById(R.id.format_panel)

        isTemplate = intent.getBooleanExtra(EXTRA_TEMPLATE, false)
        noteId = intent.getLongExtra(EXTRA_NOTE_ID, NO_ID)

        handleShareIntent()

        if (noteId != NO_ID) {
            val note = store.get(this, noteId)
            if (note == null && savedInstanceState == null) {
                Toast.makeText(this, R.string.note_deleted, Toast.LENGTH_SHORT).show()
                finish()
                return
            }
            loadedTitle = note?.title.orEmpty()
            loadedHtml = note?.html.orEmpty()
            loadedTags = note?.tags ?: emptyList()
            currentTags.addAll(loadedTags)
        }
        renderTags()
        val restored = savedInstanceState?.getString(KEY_HTML)
        if (restored != null) {
            latestHtml = restored
        } else {
            titleEdit.setText(loadedTitle)
            latestHtml = loadedHtml
        }
        updateMetaInfo()

        webView.settings.javaScriptEnabled = true
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.addJavascriptInterface(Bridge(), "Snippets")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pushContent()
            }
            override fun shouldOverrideUrlLoading(
                view: WebView, request: WebResourceRequest,
            ): Boolean = true
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                pageReady = false
                destroyWebView()
                saveIfMeaningful()
                finish()
                return true
            }
        }
        webView.loadUrl("file:///android_asset/editor.html")
        previewWebView.settings.javaScriptEnabled = false

        // ===== 顶部导航栏 =====
        findViewById<View>(R.id.btn_back).setOnClickListener {
            saveIfMeaningful()
            finish()
        }
        findViewById<View>(R.id.btn_undo).setOnClickListener { exec("undo") }
        findViewById<View>(R.id.btn_redo).setOnClickListener { exec("redo") }
        findViewById<View>(R.id.btn_more).setOnClickListener { showMoreMenu(it) }
        findViewById<View>(R.id.btn_done).setOnClickListener {
            saveIfMeaningful()
            finish()
        }

        // ===== 标签行 =====
        findViewById<View>(R.id.tag_click_area).setOnClickListener { pickTags() }
        findViewById<TextView>(R.id.button_mode_toggle).setOnClickListener {
            if (markdownMode) switchToRichText() else switchToMarkdown()
        }
        findViewById<TextView>(R.id.button_format_inline).setOnClickListener { autoFormat() }

        // ===== 底部工具栏 =====
        findViewById<View>(R.id.btn_task).setOnClickListener { insertTask() }
        findViewById<View>(R.id.btn_format_panel).setOnClickListener { toggleFormatPanel() }

        // ===== 格式面板 - 标题级别 =====
        findViewById<Button>(R.id.fmt_h1).setOnClickListener { exec("formatBlock", "H1") }
        findViewById<Button>(R.id.fmt_h2).setOnClickListener { exec("formatBlock", "H2") }
        findViewById<Button>(R.id.fmt_h3).setOnClickListener { exec("formatBlock", "H3") }
        findViewById<Button>(R.id.fmt_body).setOnClickListener { exec("formatBlock", "P") }
        findViewById<Button>(R.id.fmt_quote).setOnClickListener { exec("formatBlock", "blockquote") }
        findViewById<Button>(R.id.fmt_code_block).setOnClickListener { exec("formatBlock", "pre") }

        // ===== 格式面板 - 行内格式 =====
        findViewById<Button>(R.id.fmt_bold).setOnClickListener { exec("bold") }
        findViewById<Button>(R.id.fmt_italic).setOnClickListener { exec("italic") }
        findViewById<Button>(R.id.fmt_strike).apply {
            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            setOnClickListener { exec("strikeThrough") }
        }
        findViewById<Button>(R.id.fmt_underline).apply {
            paintFlags = paintFlags or Paint.UNDERLINE_TEXT_FLAG
            setOnClickListener { exec("underline") }
        }
        findViewById<Button>(R.id.fmt_highlight).setOnClickListener { pickHighlight() }
        findViewById<Button>(R.id.fmt_color).setOnClickListener { pickColor() }

        // ===== 格式面板 - 列表与对齐 =====
        findViewById<Button>(R.id.fmt_ol).setOnClickListener { exec("insertOrderedList") }
        findViewById<Button>(R.id.fmt_link).setOnClickListener { pickLink() }
        findViewById<Button>(R.id.fmt_align_left).setOnClickListener { exec("justifyLeft") }
        findViewById<Button>(R.id.fmt_align_center).setOnClickListener { exec("justifyCenter") }
        findViewById<Button>(R.id.fmt_align_right).setOnClickListener { exec("justifyRight") }

        // 恢复模式状态
        if (savedInstanceState?.getBoolean(KEY_SOURCE_MODE) == true) enterSourceMode()
        if (savedInstanceState?.getBoolean(KEY_MARKDOWN_MODE) == true) {
            markdownMode = true
            applyMarkdownMode()
        }
        if (savedInstanceState?.getBoolean(KEY_MARKDOWN_PREVIEW) == true) {
            markdownPreview = true
            applyMarkdownPreview()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panelVisible) {
                    hideFormatPanel()
                    return
                }
                if (markdownPreview) {
                    markdownPreview = false
                    applyMarkdownPreview()
                    return
                }
                saveIfMeaningful()
                finish()
            }
        })
    }

    private fun handleShareIntent() {
        if (Intent.ACTION_SEND == intent.action && intent.type != null) {
            val sharedText = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (!sharedText.isNullOrBlank()) {
                val html = if (looksLikeMarkdown(sharedText)) {
                    MarkdownConverter.toHtml(sharedText)
                } else {
                    sharedText.replace("\n", "<br>")
                }
                loadedHtml = html
                latestHtml = html
                val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                if (!subject.isNullOrBlank()) {
                    loadedTitle = subject
                    titleEdit.setText(subject)
                }
            }
        }
    }

    private fun looksLikeMarkdown(text: String): Boolean {
        val mdPatterns = listOf(
            Regex("^#{1,6}\\s", RegexOption.MULTILINE),
            Regex("\\*\\*.+?\\*\\*"),
            Regex("^\\s*[-*+]\\s", RegexOption.MULTILINE),
            Regex("^\\s*\\d+\\.\\s", RegexOption.MULTILINE),
            Regex("```"),
            Regex("^>\\s", RegexOption.MULTILINE),
        )
        return mdPatterns.any { it.containsMatchIn(text) }
    }

    // ===== 元信息 =====

    private fun updateMetaInfo() {
        val date = SimpleDateFormat("yyyy/M/d HH:mm", Locale.getDefault()).format(Date())
        val wordCount = HtmlConverter.plainText(latestHtml).length
        metaInfo.text = "$date  |  $wordCount 字"
    }

    // ===== 更多菜单 =====

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_editor, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> {
                    saveIfMeaningful()
                    Toast.makeText(this, if (noteId == NO_ID) R.string.nothing_to_save else R.string.saved, Toast.LENGTH_SHORT).show()
                    true
                }
                R.id.action_copy -> {
                    saveIfMeaningful()
                    ClipboardHelper.copyNote(this, Note(id = noteId, title = titleEdit.text.toString().trim(), html = currentHtml(), updatedAt = 0), isTemplate)
                    ClipboardHelper.showCopiedFeedback(this)
                    true
                }
                R.id.action_export_md -> {
                    saveIfMeaningful()
                    val name = (titleEdit.text.toString().trim().ifBlank { "snippet" }) + ".md"
                    exportMdLauncher.launch(name)
                    true
                }
                R.id.action_export_text -> {
                    saveIfMeaningful()
                    val name = (titleEdit.text.toString().trim().ifBlank { "snippet" }) + ".txt"
                    exportTextLauncher.launch(name)
                    true
                }
                R.id.action_source -> {
                    if (sourceMode) leaveSourceMode() else enterSourceMode()
                    true
                }
                R.id.action_delete -> {
                    confirmDelete()
                    true
                }
                else -> false
            }
        }
        popup.show()
    }

    // ===== 插入待办 =====

    private fun insertTask() {
        if (markdownMode) {
            MarkdownEditorHelper.prependLines(markdownEdit, "- [ ] ")
        } else {
            exec("insertUnorderedList")
            Toast.makeText(this, "富文本模式下已插入列表，可手动添加复选框", Toast.LENGTH_SHORT).show()
        }
    }

    // ===== 格式面板 =====

    private fun toggleFormatPanel() {
        if (panelVisible) hideFormatPanel() else showFormatPanel()
    }

    private fun showFormatPanel() {
        formatPanel.visibility = View.VISIBLE
        panelVisible = true
    }

    private fun hideFormatPanel() {
        formatPanel.visibility = View.GONE
        panelVisible = false
    }

    // ===== 模式切换 =====

    private fun switchToMarkdown() {
        syncFromSource()
        val md = MarkdownConverter.toMarkdown(latestHtml)
        markdownEdit.setText(md)
        markdownEdit.setSelection(0)
        markdownMode = true
        markdownPreview = false
        hideFormatPanel()
        applyMarkdownMode()
    }

    private fun switchToRichText() {
        val md = markdownEdit.text.toString()
        latestHtml = MarkdownConverter.toHtml(md)
        markdownMode = false
        markdownPreview = false
        hideFormatPanel()
        applyMarkdownMode()
        if (pageReady) pushContent()
    }

    private fun toggleMarkdownPreview() {
        markdownPreview = !markdownPreview
        applyMarkdownPreview()
        if (markdownPreview) {
            val md = markdownEdit.text.toString()
            val html = MarkdownConverter.toHtml(md)
            val styled = """
                <html><head><meta charset="utf-8">
                <style>body{font-family:sans-serif;padding:16px;line-height:1.6;}
                table,td,th{border:1px solid #ccc;border-collapse:collapse;}
                td,th{padding:4px 8px;}
                pre{background:#f5f5f5;padding:12px;border-radius:6px;overflow-x:auto;}
                code{background:#f5f5f5;padding:2px 4px;border-radius:3px;}
                blockquote{border-left:4px solid #ccc;margin:0;padding-left:12px;color:#666;}
                </style></head><body>$html</body></html>
            """.trimIndent()
            previewWebView.loadDataWithBaseURL(null, styled, "text/html", "UTF-8", null)
        }
    }

    private fun applyMarkdownMode() {
        val toggleBtn = findViewById<TextView>(R.id.button_mode_toggle)
        if (markdownMode) {
            webView.visibility = View.GONE
            sourceEdit.visibility = View.GONE
            markdownEdit.visibility = View.VISIBLE
            previewWebView.visibility = View.GONE
            toggleBtn.setText(R.string.rich_text_mode)
            // Markdown 模式下隐藏格式面板
            findViewById<View>(R.id.btn_format_panel).visibility = View.GONE
        } else {
            markdownEdit.visibility = View.GONE
            previewWebView.visibility = View.GONE
            webView.visibility = View.VISIBLE
            toggleBtn.setText(R.string.markdown_mode)
            findViewById<View>(R.id.btn_format_panel).visibility = View.VISIBLE
        }
        updateMetaInfo()
    }

    private fun applyMarkdownPreview() {
        if (markdownPreview) {
            markdownEdit.visibility = View.GONE
            previewWebView.visibility = View.VISIBLE
        } else {
            previewWebView.visibility = View.GONE
            markdownEdit.visibility = View.VISIBLE
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyWebView()
    }

    private fun destroyWebView() {
        if (webViewDestroyed) return
        webViewDestroyed = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        syncFromSource()
        if (markdownMode) latestHtml = MarkdownConverter.toHtml(markdownEdit.text.toString())
        outState.putString(KEY_HTML, latestHtml)
        outState.putBoolean(KEY_SOURCE_MODE, sourceMode)
        outState.putBoolean(KEY_MARKDOWN_MODE, markdownMode)
        outState.putBoolean(KEY_MARKDOWN_PREVIEW, markdownPreview)
    }

    private fun syncFromSource() {
        if (sourceMode) latestHtml = sourceEdit.text.toString()
    }

    private fun enterSourceMode() {
        sourceMode = true
        sourceEdit.setText(latestHtml)
        webView.visibility = View.GONE
        sourceEdit.visibility = View.VISIBLE
        hideFormatPanel()
    }

    private fun leaveSourceMode() {
        syncFromSource()
        sourceMode = false
        pushContent()
        sourceEdit.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private inner class Bridge {
        @JavascriptInterface
        fun onHtmlChanged(html: String) {
            latestHtml = html
            runOnUiThread { updateMetaInfo() }
        }
    }

    private fun pushContent() {
        val js = "setup(${JSONObject.quote(latestHtml)}, ${JSONObject.quote(themeTextColor())})"
        webView.evaluateJavascript(js, null)
    }

    private fun exec(command: String, value: String? = null, raw: Boolean = false) {
        if (!pageReady || sourceMode || markdownMode) return
        val js = when {
            raw -> if (command.endsWith(")")) command else "$command()"
            value != null -> "cmd(${JSONObject.quote(command)}, ${JSONObject.quote(value)})"
            else -> "cmd(${JSONObject.quote(command)})"
        }
        webView.evaluateJavascript(js, null)
    }

    /** 自动排版：获取当前 HTML，格式化后推回编辑器。 */
    private fun autoFormat() {
        if (!pageReady || sourceMode || markdownMode) return
        webView.evaluateJavascript("getHtml()") { result ->
            val html = try {
                org.json.JSONTokener(result).nextValue() as? String ?: latestHtml
            } catch (_: Exception) {
                latestHtml
            }
            val formatted = ContentFormatter.formatHtml(html)
            if (formatted.isNotBlank() && formatted != html) {
                latestHtml = formatted
                pushContent()
                updateMetaInfo()
                Toast.makeText(this, R.string.auto_format_done, Toast.LENGTH_SHORT).show()
            } else if (formatted.isBlank()) {
                Toast.makeText(this, "排版结果为空，已保留原内容", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, R.string.auto_format_no_change, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ===== 颜色/高亮/链接对话框 =====

    private fun pickColor() {
        AlertDialog.Builder(this)
            .setTitle(R.string.text_color)
            .setItems(resources.getStringArray(R.array.color_names)) { _, which ->
                exec("foreColor", COLOR_VALUES[which])
            }
            .show()
    }

    private fun pickHighlight() {
        AlertDialog.Builder(this)
            .setTitle(R.string.highlight)
            .setItems(resources.getStringArray(R.array.highlight_names)) { _, which ->
                exec("hiliteColor", HIGHLIGHT_VALUES[which])
            }
            .show()
    }

    private fun pickLink() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            hint = getString(R.string.link_hint)
            maxLines = 1
        }
        val container = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.insert_link)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                normalizeUrl(input.text.toString())?.let { exec("createLink", it) }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun normalizeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url else "https://$url"
    }

    private fun themeTextColor(): String {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        val color = if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
        return String.format(Locale.ROOT, "#%06X", 0xFFFFFF and color)
    }

    // ===== 标签 =====

    private fun renderTags() {
        if (currentTags.isEmpty()) {
            editTags.visibility = View.GONE
            editTags.removeAllViews()
            findViewById<View>(R.id.tag_hint).visibility = View.VISIBLE
            return
        }
        findViewById<View>(R.id.tag_hint).visibility = View.GONE
        editTags.visibility = View.VISIBLE
        editTags.removeAllViews()
        for (tag in currentTags) {
            editTags.addView(com.google.android.material.chip.Chip(this).apply {
                text = tag
                isClickable = false
                isCheckable = false
                setEnsureMinTouchTargetSize(false)
                textSize = 11f
                chipMinHeight = 24f
                chipStartPadding = 6f
                chipEndPadding = 6f
            })
        }
    }

    private fun pickTags() {
        val allTags = TagStore.all(this).toMutableList()
        val working = currentTags.toMutableSet()
        val existingChecked = allTags.map { it in working }.toBooleanArray()
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.edit_tags)
        if (allTags.isEmpty()) {
            builder.setMessage(R.string.no_tags_yet)
        } else {
            builder.setMultiChoiceItems(allTags.toTypedArray(), existingChecked) { _, which, isChecked ->
                val tag = allTags[which]
                if (isChecked) working.add(tag) else working.remove(tag)
            }
        }
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            currentTags.clear()
            currentTags.addAll(working)
            renderTags()
        }
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.setNeutralButton(R.string.new_tag) { _, _ -> promptNewTag(working, allTags) }
        builder.show()
    }

    private fun promptNewTag(working: MutableSet<String>, allTags: MutableList<String>) {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.new_tag_hint)
            maxLines = 1
        }
        val container = FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_tag)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && !allTags.contains(name)) {
                    TagStore.add(this, name)
                    allTags.add(name)
                    working.add(name)
                }
                reopenTagPicker(working, allTags)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> reopenTagPicker(working, allTags) }
            .show()
    }

    private fun reopenTagPicker(working: MutableSet<String>, allTags: MutableList<String>) {
        val existingChecked = allTags.map { it in working }.toBooleanArray()
        val builder = AlertDialog.Builder(this)
        builder.setTitle(R.string.edit_tags)
        if (allTags.isEmpty()) {
            builder.setMessage(R.string.no_tags_yet)
        } else {
            builder.setMultiChoiceItems(allTags.toTypedArray(), existingChecked) { _, which, isChecked ->
                val tag = allTags[which]
                if (isChecked) working.add(tag) else working.remove(tag)
            }
        }
        builder.setPositiveButton(android.R.string.ok) { _, _ ->
            currentTags.clear()
            currentTags.addAll(working)
            renderTags()
        }
        builder.setNegativeButton(android.R.string.cancel, null)
        builder.setNeutralButton(R.string.new_tag) { _, _ -> promptNewTag(working, allTags) }
        builder.show()
    }

    // ===== 保存与导出 =====

    private fun currentHtml(): String {
        return if (markdownMode) {
            MarkdownConverter.toHtml(markdownEdit.text.toString())
        } else {
            syncFromSource()
            latestHtml
        }
    }

    private fun exportMarkdown(uri: Uri) {
        try {
            val title = titleEdit.text.toString().trim()
            val md = if (markdownMode) markdownEdit.text.toString() else MarkdownConverter.toMarkdown(currentHtml())
            val content = buildString {
                if (title.isNotEmpty()) append("# ").append(title).append("\n\n")
                append(md)
            }
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(content.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportPlainText(uri: Uri) {
        try {
            val title = titleEdit.text.toString().trim()
            val text = buildString {
                if (title.isNotEmpty()) append(title).append("\n\n")
                append(HtmlConverter.plainText(currentHtml()))
            }
            contentResolver.openOutputStream(uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun saveIfMeaningful() {
        if (deleted) return
        val html = currentHtml()
        val title = titleEdit.text.toString().trim()
        val tags = currentTags.toList()
        val unchanged = title == loadedTitle && html == loadedHtml && tags == loadedTags
        if (unchanged) return
        if (noteId == NO_ID && title.isEmpty() && !HtmlConverter.hasContent(html) && tags.isEmpty()) return
        val stored = store.save(this, Note(id = noteId, title = title, html = html, updatedAt = 0, tags = tags))
        noteId = stored.id
        loadedTitle = title
        loadedHtml = html
        loadedTags = tags
        latestHtml = html
        refreshWidgets()
    }

    private fun refreshWidgets() {
        if (isTemplate) {
            TemplateWidgetProvider.updateAllWidgets(this)
            TemplateCopyWidgetProvider.updateAllWidgets(this)
        } else {
            SnippetsWidgetProvider.updateAllWidgets(this)
        }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_note)
            .setMessage(R.string.delete_confirm_simple)
            .setPositiveButton(R.string.delete) { _, _ ->
                deleted = true
                if (noteId != NO_ID) {
                    val note = store.get(this, noteId)
                    if (note != null) TrashStore.add(this, note, isTemplate)
                    store.delete(this, noteId)
                    refreshWidgets()
                }
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
