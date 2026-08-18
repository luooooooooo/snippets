package com.kvelzer.snippets

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.Menu
import android.view.MenuItem
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
import android.widget.Toast
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.kvelzer.snippets.widget.SnippetsWidgetProvider
import com.kvelzer.snippets.widget.TemplateCopyWidgetProvider
import com.kvelzer.snippets.widget.TemplateWidgetProvider
import org.json.JSONObject
import java.util.Locale

/**
 * WYSIWYG editor on a WebView running a contenteditable document
 * (assets/editor.html). Unlike the earlier EditText/Spannable editor, the
 * note's HTML is edited AS HTML by a real browser engine, so constructs the
 * span model can't represent (tables, fonts, nested lists...) render, remain
 * editable, and survive save/copy verbatim.
 *
 * Reading HTML out of a WebView is asynchronous, which would make save-on-back
 * fragile — so the page reports its HTML to [latestHtml] on every edit via the
 * JS bridge, and all save paths read that synchronously.
 */
class EditorActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NOTE_ID = "note_id"
        const val EXTRA_TEMPLATE = "template"
        private const val NO_ID = 0L
        private const val KEY_HTML = "html"
        private const val KEY_SOURCE_MODE = "source_mode"

        // Presets for the color picker; must stay in order with R.array.color_names.
        private val COLOR_VALUES = arrayOf(
            "#D32F2F", // red
            "#F57C00", // orange
            "#388E3C", // green
            "#1976D2", // blue
            "#7B1FA2", // purple
            "#616161", // gray
        )

        // Presets for the highlight picker; in order with R.array.highlight_names.
        private val HIGHLIGHT_VALUES = arrayOf(
            "transparent", // none
            "#FFF59D", // yellow
            "#A5D6A7", // green
            "#90CAF9", // blue
            "#F8BBD0", // pink
        )
    }

    private lateinit var titleEdit: EditText
    private lateinit var webView: WebView
    private lateinit var sourceEdit: EditText
    private lateinit var editTags: com.google.android.material.chip.ChipGroup

    // 该笔记当前的标签集合（在编辑器内增删，保存时一并写入）。
    private val currentTags = mutableSetOf<String>()

    @Volatile
    private var latestHtml = ""

    private var noteId = NO_ID
    private var deleted = false
    private var pageReady = false
    private var sourceMode = false
    private var webViewDestroyed = false

    private var isTemplate = false
    private val store: JsonNoteStore get() = if (isTemplate) TemplateStore else NoteStore

    // What the note looked like when opened; saving is skipped when unchanged,
    // so merely viewing a note doesn't rewrite the store.
    private var loadedTitle = ""
    private var loadedHtml = ""
    private var loadedTags = emptyList<String>()

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        SnippetsApp.applyColorTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_editor)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = ""

        titleEdit = findViewById(R.id.edit_title)
        webView = findViewById(R.id.edit_body)
        sourceEdit = findViewById(R.id.edit_source)
        editTags = findViewById(R.id.edit_tags)

        isTemplate = intent.getBooleanExtra(EXTRA_TEMPLATE, false)
        noteId = intent.getLongExtra(EXTRA_NOTE_ID, NO_ID)
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
            latestHtml = restored // titleEdit restores itself
        } else {
            titleEdit.setText(loadedTitle)
            latestHtml = loadedHtml
        }

        webView.settings.javaScriptEnabled = true
        webView.setBackgroundColor(Color.TRANSPARENT)
        webView.addJavascriptInterface(Bridge(), "Snippets")
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                pageReady = true
                pushContent()
                updateFillBar()
            }

            // Notes can contain links now; tapping one in the editor must not
            // navigate away from the editor page. Blocks all user-initiated
            // navigation (the initial loadUrl doesn't go through here).
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = true

            // The system can kill the WebView renderer under memory pressure;
            // returning false (the default) would take the whole app down.
            // latestHtml is safe on the Kotlin side, so save and bail out.
            override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                pageReady = false
                destroyWebView()
                saveIfMeaningful()
                finish()
                return true
            }
        }
        webView.loadUrl("file:///android_asset/editor.html")

        findViewById<Button>(R.id.button_bold).setOnClickListener { exec("bold") }
        findViewById<Button>(R.id.button_italic).setOnClickListener { exec("italic") }
        findViewById<Button>(R.id.button_underline).setOnClickListener { exec("underline") }
        findViewById<Button>(R.id.button_strike).apply {
            paintFlags = paintFlags or Paint.STRIKE_THRU_TEXT_FLAG
            setOnClickListener { exec("strikeThrough") }
        }
        findViewById<Button>(R.id.button_color).setOnClickListener { pickColor() }
        findViewById<Button>(R.id.button_highlight).setOnClickListener { pickHighlight() }
        findViewById<Button>(R.id.button_bullets).setOnClickListener { exec("insertUnorderedList") }
        findViewById<Button>(R.id.button_numbers).setOnClickListener { exec("insertOrderedList") }
        findViewById<Button>(R.id.button_link).setOnClickListener { pickLink() }
        findViewById<Button>(R.id.button_undo).setOnClickListener { exec("undo") }
        findViewById<Button>(R.id.button_redo).setOnClickListener { exec("redo") }

        findViewById<Button>(R.id.button_slot).apply {
            visibility = if (isTemplate) View.VISIBLE else View.GONE
            setOnClickListener { exec("toggleSlot", raw = true) }
        }
        findViewById<Button>(R.id.button_fill_apply).setOnClickListener { applyFillValue() }

        // 标签栏：点整行打开多选对话框（可勾选已有标签、可新建）。
        findViewById<View>(R.id.tag_row).setOnClickListener { pickTags() }

        if (savedInstanceState?.getBoolean(KEY_SOURCE_MODE) == true) {
            enterSourceMode() // sourceEdit's text restores itself
        }

        // Back saves silently (like Keep). Explicit Save in the menu also works.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                saveIfMeaningful()
                finish()
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyWebView()
    }

    /** Detach before destroy; idempotent (onRenderProcessGone + onDestroy both call it). */
    private fun destroyWebView() {
        if (webViewDestroyed) return
        webViewDestroyed = true
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    override fun onSupportNavigateUp(): Boolean {
        onBackPressedDispatcher.onBackPressed()
        return true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        syncFromSource()
        outState.putString(KEY_HTML, latestHtml)
        outState.putBoolean(KEY_SOURCE_MODE, sourceMode)
    }

    // ---- source mode ------------------------------------------------------

    /** In source mode the EditText is the live representation — pull it in. */
    private fun syncFromSource() {
        if (sourceMode) latestHtml = sourceEdit.text.toString()
    }

    private fun enterSourceMode() {
        sourceMode = true
        sourceEdit.setText(latestHtml)
        webView.visibility = View.GONE
        findViewById<View>(R.id.format_bar).visibility = View.GONE
        sourceEdit.visibility = View.VISIBLE
        updateFillBar()
        invalidateOptionsMenu()
    }

    private fun leaveSourceMode() {
        syncFromSource()
        sourceMode = false
        pushContent() // hand the (possibly hand-edited) HTML back to the WebView
        sourceEdit.visibility = View.GONE
        webView.visibility = View.VISIBLE
        findViewById<View>(R.id.format_bar).visibility = View.VISIBLE
        updateFillBar()
        invalidateOptionsMenu()
    }

    // ---- WebView plumbing --------------------------------------------------

    private inner class Bridge {
        @JavascriptInterface
        fun onHtmlChanged(html: String) {
            val slotChanged = TemplateFiller.hasSlot(html) != TemplateFiller.hasSlot(latestHtml)
            latestHtml = html
            if (slotChanged) runOnUiThread { updateFillBar() }
        }
    }

    /** The fill bar shows only for a template whose HTML currently has a slot. */
    private fun updateFillBar() {
        findViewById<View>(R.id.fill_bar).visibility =
            if (isTemplate && !sourceMode && TemplateFiller.hasSlot(latestHtml)) View.VISIBLE
            else View.GONE
    }

    private fun applyFillValue() {
        val value = findViewById<EditText>(R.id.fill_value).text.toString()
        exec("setSlotText(${JSONObject.quote(value)})", raw = true)
    }

    private fun pushContent() {
        val js = "setup(${JSONObject.quote(latestHtml)}, ${JSONObject.quote(themeTextColor())})"
        webView.evaluateJavascript(js, null)
    }

    /** [raw] runs the string as a JS expression instead of an execCommand. */
    private fun exec(command: String, value: String? = null, raw: Boolean = false) {
        if (!pageReady || sourceMode) return
        val js = when {
            raw -> if (command.endsWith(")")) command else "$command()"
            value != null -> "cmd(${JSONObject.quote(command)}, ${JSONObject.quote(value)})"
            else -> "cmd(${JSONObject.quote(command)})"
        }
        webView.evaluateJavascript(js, null)
    }

    private fun pickColor() {
        AlertDialog.Builder(this)
            .setTitle(R.string.text_color)
            .setItems(resources.getStringArray(R.array.color_names)) { _, which ->
                exec("foreColor", COLOR_VALUES[which])
            }
            .show()
    }

    /**
     * Wraps the current selection in a link (execCommand createLink; no-op on
     * a collapsed selection, like the other format buttons). The WebView keeps
     * its document selection while the dialog is up.
     */
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

    /** Empty input -> null; bare domains get https://. The page's sanitizer
     *  still strips javascript: URLs, so no scheme filtering here. */
    private fun normalizeUrl(raw: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) return null
        return if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(url)) url else "https://$url"
    }

    private fun pickHighlight() {
        AlertDialog.Builder(this)
            .setTitle(R.string.highlight)
            .setItems(resources.getStringArray(R.array.highlight_names)) { _, which ->
                exec("hiliteColor", HIGHLIGHT_VALUES[which])
            }
            .show()
    }

    private fun themeTextColor(): String {
        val tv = TypedValue()
        theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        val color = if (tv.resourceId != 0) ContextCompat.getColor(this, tv.resourceId) else tv.data
        return String.format(Locale.ROOT, "#%06X", 0xFFFFFF and color)
    }

    // ---- tags --------------------------------------------------------------

    /** 在编辑器内渲染当前所选标签（只读 chips），无标签时显示「添加标签」提示。 */
    private fun renderTags() {
        val row = findViewById<View>(R.id.tag_row)
        row.visibility = View.VISIBLE
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
            editTags.addView(Chip(this).apply {
                text = tag
                isClickable = false
                isCheckable = false
                setEnsureMinTouchTargetSize(false)
            })
        }
    }

    /** 多选对话框：勾选已有标签（可新建），确定后更新 currentTags 并重绘。 */
    private fun pickTags() {
        val allTags = TagStore.all(this).toMutableList()
        // 临时工作集，关对话框前不写库，避免新建了又取消。
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
        // 按钮显示顺序（左→右）：取消、确定、新建标签。
        builder.setPositiveButton(android.R.string.cancel, null)
        builder.setNegativeButton(android.R.string.ok) { _, _ ->
            currentTags.clear()
            currentTags.addAll(working)
            renderTags()
        }
        builder.setNeutralButton(R.string.new_tag) { _, _ -> promptNewTag(working, allTags) }
        builder.show()
    }

    /** 新建标签：输入名字（去重），加入工作集并刷新对话框。 */
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
                // 重新打开多选对话框，保留已选项。
                reopenTagPicker(working, allTags)
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> reopenTagPicker(working, allTags) }
            .show()
    }

    /** 重新弹出多选对话框（新建标签后保留用户已选状态）。 */
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
        // 按钮显示顺序（左→右）：取消、确定、新建标签。
        builder.setPositiveButton(android.R.string.cancel, null)
        builder.setNegativeButton(android.R.string.ok) { _, _ ->
            currentTags.clear()
            currentTags.addAll(working)
            renderTags()
        }
        builder.setNeutralButton(R.string.new_tag) { _, _ -> promptNewTag(working, allTags) }
        builder.show()
    }

    // ---- persistence ------------------------------------------------------

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_editor, menu)
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        menu.findItem(R.id.action_source)
            ?.setTitle(if (sourceMode) R.string.visual_editor else R.string.edit_source)
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_save -> {
            saveIfMeaningful()
            // An empty new note is discarded, not saved — don't claim otherwise.
            Toast.makeText(
                this,
                if (noteId == NO_ID) R.string.nothing_to_save else R.string.saved,
                Toast.LENGTH_SHORT,
            ).show()
            true
        }
        R.id.action_copy -> {
            saveIfMeaningful()
            // Copy the live buffer, not the store: an empty new note has no
            // stored entry (noteId stays 0) and copying must still respond.
            syncFromSource()
            ClipboardHelper.copyNote(
                this,
                Note(id = noteId, title = titleEdit.text.toString().trim(), html = latestHtml, updatedAt = 0),
            )
            ClipboardHelper.showCopiedFeedback(this)
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
        else -> super.onOptionsItemSelected(item)
    }

    private fun saveIfMeaningful() {
        if (deleted) return
        syncFromSource()
        val title = titleEdit.text.toString().trim()
        val html = latestHtml
        val tags = currentTags.toList()
        // 内容或标签有变化才保存（仅看不改动不会重写存储）。
        val unchanged = title == loadedTitle && html == loadedHtml && tags == loadedTags
        if (unchanged) return
        if (noteId == NO_ID && title.isEmpty() && !HtmlConverter.hasContent(html) && tags.isEmpty()) return
        val stored = store.save(this, Note(id = noteId, title = title, html = html, updatedAt = 0, tags = tags))
        noteId = stored.id
        loadedTitle = title
        loadedHtml = html
        loadedTags = tags
        refreshWidgets()
    }

    // A widget may show this entry's title — refresh the matching provider.
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
                    store.delete(this, noteId)
                    refreshWidgets()
                }
                finish()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}
