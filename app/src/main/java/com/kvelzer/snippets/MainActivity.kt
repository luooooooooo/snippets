package com.kvelzer.snippets

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.view.ActionMode
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout
import com.kvelzer.snippets.widget.SnippetsWidgetProvider
import com.kvelzer.snippets.widget.TemplateCopyWidgetProvider
import com.kvelzer.snippets.widget.TemplateWidgetProvider
import java.text.Collator
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var adapter: NotesAdapter
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView

    // 滑动手势时的背景色与图标：右滑=编辑（绿+铅笔），左滑=删除（红+垃圾桶）
    private val editBgColor by lazy { ContextCompat.getColor(this, R.color.swipe_edit_bg) }
    private val deleteBgColor by lazy { ContextCompat.getColor(this, R.color.swipe_delete_bg) }
    private val swipeIconTint by lazy { ContextCompat.getColor(this, android.R.color.white) }
    private val editIcon by lazy { ContextCompat.getDrawable(this, R.drawable.ic_swipe_edit) }
    private val deleteIcon by lazy { ContextCompat.getDrawable(this, R.drawable.ic_delete) }
    private val swipeIconSize by lazy { (28 * resources.displayMetrics.density).toInt() }
    private val swipeIconMargin by lazy { (24 * resources.displayMetrics.density).toInt() }

    private var templatesTab = false
    private var searchQuery = ""
    private val store: JsonNoteStore get() = if (templatesTab) TemplateStore else NoteStore

    // 标签筛选：选中的标签集合（多选）；空集合表示「全部」。
    private val selectedTags = mutableSetOf<String>()
    private lateinit var tagBar: ChipGroup

    // 滑动删除进行中：条目已从列表移除、尚未定案（确认/取消）。
    private var swipeDeleteInProgress = false

    // 双栏模式（平板/折叠屏）：右侧有预览容器时为 true。
    private var twoPane = false

    // 多选 ActionMode
    private var actionMode: ActionMode? = null

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { doExport(it) }
        }

    private val exportMdLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/markdown")) { uri ->
            uri?.let { doExportMarkdown(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmImport(it) }
        }

    private val importMdLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importMarkdown(it) }
        }

    // 用于从自动备份列表导出某个备份文件
    private var pendingBackupFile: java.io.File? = null
    private val exportBackupLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { exportPendingBackup(it) }
        }

    private val actionModeCallback = object : ActionMode.Callback {
        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            mode.menuInflater.inflate(R.menu.menu_batch, menu)
            adapter.selectionMode = true
            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            return when (item.itemId) {
                R.id.action_batch_delete -> {
                    confirmBatchDelete()
                    true
                }
                R.id.action_batch_tag -> {
                    batchTag()
                    true
                }
                R.id.action_batch_select_all -> {
                    adapter.selectAll()
                    updateActionModeTitle()
                    true
                }
                else -> false
            }
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            adapter.selectionMode = false
            actionMode = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        SnippetsApp.applyColorTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        twoPane = findViewById<View?>(R.id.detail_container) != null

        emptyView = findViewById(R.id.empty_view)
        emptyText = findViewById(R.id.empty_text)

        templatesTab = savedInstanceState?.getBoolean(KEY_TAB) == true
        val tabs = findViewById<TabLayout>(R.id.tabs)
        if (templatesTab) tabs.selectTab(tabs.getTabAt(1))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                templatesTab = tab.position == 1
                actionMode?.finish()
                refresh()
            }
            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        val onOpen: (Note) -> Unit = { note -> openNote(note) }
        val onCopy: (Note) -> Unit = { note ->
            ClipboardHelper.copyNote(this, note, isTemplate = templatesTab)
            ClipboardHelper.showCopiedFeedback(this)
            updateShortcuts()
        }
        val onDeleteRequest: (Note) -> Unit = { note -> confirmDelete(note) }
        val onFill: (Note) -> Unit = { note -> fillFromClipboard(note) }

        adapter = NotesAdapter(
            onOpen = onOpen,
            onCopy = onCopy,
            onDeleteRequest = onDeleteRequest,
            onFill = onFill,
            onSelectionChanged = { updateActionModeTitle() },
        )

        val list = findViewById<RecyclerView>(R.id.notes_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // 顶部标签栏
        tagBar = findViewById(R.id.tag_bar)
        buildTagBar()

        // 手势：长按拖动排序 + 左右滑动
        ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                ItemTouchHelper.START or ItemTouchHelper.END,
            ) {
            override fun isLongPressDragEnabled() =
                searchQuery.isBlank() && !adapter.selectionMode && SnippetsApp.getListSort(this@MainActivity) == SORT_MANUAL

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder,
            ): Boolean {
                adapter.moveItem(viewHolder.bindingAdapterPosition, target.bindingAdapterPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
                val position = viewHolder.bindingAdapterPosition
                val note = adapter.noteAt(position) ?: return
                when (direction) {
                    ItemTouchHelper.END -> {
                        // 编辑：先重置滑动偏移并通知重绑，否则跳转返回后
                        // ViewHolder 会停留在右滑后的位置，绿色背景残留。
                        viewHolder.itemView.translationX = 0f
                        adapter.notifyItemChanged(position)
                        onOpen(note)
                    }
                    ItemTouchHelper.START -> {
                        adapter.removeAt(position)
                        swipeDeleteInProgress = true
                        confirmDelete(note)
                    }
                }
            }

            override fun onChildDraw(
                c: Canvas, recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder,
                dX: Float, dY: Float, actionState: Int, isCurrentlyActive: Boolean,
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val top = itemView.top.toFloat()
                    val bottom = itemView.bottom.toFloat()
                    val paint = Paint().apply {
                        color = if (dX > 0) editBgColor else deleteBgColor
                    }
                    if (dX > 0) {
                        c.drawRect(0f, top, dX, bottom, paint)
                        drawSwipeIcon(c, editIcon, swipeIconMargin + swipeIconSize / 2f, (top + bottom) / 2f)
                    } else if (dX < 0) {
                        val right = recyclerView.width.toFloat()
                        c.drawRect(right + dX, top, right, bottom, paint)
                        drawSwipeIcon(c, deleteIcon, right - swipeIconMargin - swipeIconSize / 2f, (top + bottom) / 2f)
                    }
                }
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive)
            }

            private fun drawSwipeIcon(c: Canvas, drawable: Drawable?, cx: Float, cy: Float) {
                drawable ?: return
                DrawableCompat.setTint(drawable, swipeIconTint)
                val half = swipeIconSize / 2
                drawable.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
                drawable.draw(c)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                if (!swipeDeleteInProgress && SnippetsApp.getListSort(this@MainActivity) == SORT_MANUAL) {
                    store.updateOrder(this@MainActivity, adapter.noteIds())
                }
            }
        }).attachToRecyclerView(list)

        findViewById<FloatingActionButton>(R.id.fab_add).setOnClickListener {
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_TEMPLATE, templatesTab)
            )
        }

        updateShortcuts()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_TAB, templatesTab)
    }

    override fun onResume() {
        super.onResume()
        buildTagBar()
        refresh()
        updateShortcuts()
    }

    private fun openNote(note: Note) {
        if (twoPane) {
            val fragment = NotePreviewFragment.newInstance(note.id, templatesTab)
            supportFragmentManager.beginTransaction()
                .replace(R.id.detail_container, fragment)
                .commit()
        } else {
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_NOTE_ID, note.id)
                    .putExtra(EditorActivity.EXTRA_TEMPLATE, templatesTab)
            )
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_main, menu)
        val searchView = menu.findItem(R.id.action_search).actionView as SearchView
        searchView.queryHint = getString(R.string.search_hint)
        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String): Boolean = false
            override fun onQueryTextChange(newText: String): Boolean {
                searchQuery = newText
                refresh()
                return true
            }
        })
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_select -> {
            actionMode = startSupportActionMode(actionModeCallback)
            true
        }
        R.id.action_theme -> { pickTheme(); true }
        R.id.action_color -> { pickColorTheme(); true }
        R.id.action_sort_tags -> { showTagSortDialog(); true }
        R.id.action_sort_list -> { showListSortDialog(); true }
        R.id.action_trash -> {
            startActivity(Intent(this, TrashActivity::class.java))
            true
        }
        R.id.action_export -> {
            exportLauncher.launch(defaultBackupName())
            true
        }
        R.id.action_export_md -> {
            exportMdLauncher.launch("snippets-export.md")
            true
        }
        R.id.action_import -> {
            importLauncher.launch(arrayOf("application/json", "*/*"))
            true
        }
        R.id.action_import_md -> {
            importMdLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*"))
            true
        }
        R.id.action_auto_backup -> {
            showAutoBackupDialog()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun updateActionModeTitle() {
        val count = adapter.getSelected().size
        actionMode?.title = getString(R.string.selected_count, count)
        if (count == 0) actionMode?.finish()
    }

    private fun confirmBatchDelete() {
        val selected = adapter.getSelected()
        if (selected.isEmpty()) return
        AlertDialog.Builder(this)
            .setTitle(R.string.batch_delete)
            .setMessage(getString(R.string.batch_delete_confirm, selected.size))
            .setPositiveButton(R.string.delete) { _, _ ->
                for (note in selected) {
                    TrashStore.add(this, note, templatesTab)
                    store.delete(this, note.id)
                }
                refreshWidgets()
                actionMode?.finish()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun batchTag() {
        val selected = adapter.getSelected()
        if (selected.isEmpty()) return
        val allTags = TagStore.all(this).toMutableList()
        val working = mutableSetOf<String>()
        val existingChecked = allTags.map { false }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.batch_tag)
            .setMultiChoiceItems(allTags.toTypedArray(), existingChecked) { _, which, isChecked ->
                if (isChecked) working.add(allTags[which]) else working.remove(allTags[which])
            }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                for (note in selected) {
                    val newTags = (note.tags + working).distinct()
                    store.save(this, note.copy(tags = newTags))
                }
                actionMode?.finish()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.new_tag) { _, _ ->
                // 简化：新建标签后直接加到所有选中项
                val input = android.widget.EditText(this).apply {
                    inputType = android.text.InputType.TYPE_CLASS_TEXT
                    hint = getString(R.string.new_tag_hint)
                    maxLines = 1
                }
                val container = android.widget.FrameLayout(this).apply {
                    val pad = (16 * resources.displayMetrics.density).toInt()
                    setPadding(pad, 0, pad, 0)
                    addView(input)
                }
                AlertDialog.Builder(this)
                    .setTitle(R.string.new_tag)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        val name = input.text.toString().trim()
                        if (name.isNotEmpty()) {
                            TagStore.add(this, name)
                            for (note in selected) {
                                store.save(this, note.copy(tags = note.tags + name))
                            }
                            actionMode?.finish()
                            refresh()
                        }
                    }
                    .setNegativeButton(android.R.string.cancel, null)
                    .show()
            }
            .show()
    }

    private fun pickTheme() {
        val modes = intArrayOf(
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM,
            AppCompatDelegate.MODE_NIGHT_NO,
            AppCompatDelegate.MODE_NIGHT_YES,
        )
        val current = modes.indexOf(SnippetsApp.getThemeMode(this)).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.theme)
            .setSingleChoiceItems(resources.getStringArray(R.array.theme_names), current) { dialog, which ->
                SnippetsApp.setThemeMode(this, modes[which])
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickColorTheme() {
        val names = resources.getStringArray(R.array.palette_names)
        val current = SnippetsApp.getColorTheme(this).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle(R.string.color_theme)
            .setSingleChoiceItems(names, current) { dialog, which ->
                SnippetsApp.setColorTheme(this, which)
                dialog.dismiss()
                recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        swipeDeleteInProgress = false
        val all = store.all(this)
        val query = searchQuery.trim()
        val filtered = all.filter { note ->
            val matchesQuery = query.isEmpty() || note.title.contains(query, ignoreCase = true) ||
                HtmlConverter.plainText(note.html).contains(query, ignoreCase = true)
            val matchesTags = selectedTags.isEmpty() || note.tags.any { it in selectedTags }
            matchesQuery && matchesTags
        }
        val sorted = sortNotes(filtered)
        adapter.showFillButton = templatesTab
        adapter.submitList(sorted) {
            // 安全网：重置可能残留的滑动偏移（右滑编辑跳转返回后，
            // DiffUtil 若检测到内容无变化不会重绑，translationX 会残留）。
            val rv = findViewById<RecyclerView>(R.id.notes_list)
            for (i in 0 until rv.childCount) {
                rv.getChildAt(i).translationX = 0f
            }
        }
        emptyText.setText(
            when {
                query.isNotEmpty() -> R.string.search_no_results
                selectedTags.isEmpty() -> if (templatesTab) R.string.empty_hint_templates else R.string.empty_hint
                else -> R.string.empty_filtered
            }
        )
        emptyView.visibility = if (sorted.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun sortNotes(notes: List<Note>): List<Note> {
        return when (SnippetsApp.getListSort(this)) {
            SORT_MANUAL -> notes.sortedBy { it.sortOrder }
            SORT_RECENT_USED -> notes.sortedByDescending { it.lastUsedAt }
            SORT_MOST_USED -> notes.sortedByDescending { it.useCount }
            SORT_NAME_ASC -> notes.sortedWith(compareBy(chineseCollator) { it.title.ifBlank { it.id.toString() } })
            SORT_NAME_DESC -> notes.sortedWith(compareByDescending(chineseCollator) { it.title.ifBlank { it.id.toString() } })
            else -> notes.sortedBy { it.sortOrder }
        }
    }

    private fun showListSortDialog() {
        val names = resources.getStringArray(R.array.list_sort_names)
        val current = SnippetsApp.getListSort(this).coerceIn(0, names.lastIndex)
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_list)
            .setSingleChoiceItems(names, current) { dialog, which ->
                SnippetsApp.setListSort(this, which)
                dialog.dismiss()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun buildTagBar() {
        tagBar.removeAllViews()
        val context = this

        val allChip = Chip(context).apply {
            text = getString(R.string.tag_all)
            isCheckable = true
            isChecked = selectedTags.isEmpty()
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    selectedTags.clear()
                    for (i in 0 until tagBar.childCount) {
                        val c = tagBar.getChildAt(i) as? Chip ?: continue
                        if (c !== this) c.isChecked = false
                    }
                    refresh()
                }
            }
        }
        tagBar.addView(allChip)

        for (tag in sortTags(context, TagStore.all(context), SnippetsApp.getTagSort(context))) {
            val chip = Chip(context).apply {
                text = tag
                isCheckable = true
                isChecked = tag in selectedTags
                setOnCheckedChangeListener { _, checked ->
                    if (checked) {
                        selectedTags.add(tag)
                        allChip.isChecked = false
                    } else {
                        selectedTags.remove(tag)
                        if (selectedTags.isEmpty()) allChip.isChecked = true
                    }
                    refresh()
                }
                setOnLongClickListener {
                    showTagMenu(it, tag)
                    true
                }
            }
            tagBar.addView(chip)
        }

        val addChip = Chip(context).apply {
            text = "+"
            isCheckable = false
            setOnClickListener { promptAddTag() }
        }
        tagBar.addView(addChip)
    }

    private fun promptAddTag() {
        val input = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            hint = getString(R.string.new_tag_hint)
            maxLines = 1
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.new_tag)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty() && TagStore.add(this, name)) {
                    selectedTags.add(name)
                    buildTagBar()
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTagMenu(anchor: View, tag: String) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, 1, 0, R.string.edit_tag).setOnMenuItemClickListener {
                editTag(tag); true
            }
            menu.add(Menu.NONE, 2, 1, R.string.delete_tag).setOnMenuItemClickListener {
                confirmDeleteTag(tag); true
            }
            show()
        }
    }

    private fun editTag(oldName: String) {
        val input = android.widget.EditText(this).apply {
            setText(oldName)
            inputType = android.text.InputType.TYPE_CLASS_TEXT
            maxLines = 1
            selectAll()
        }
        val container = android.widget.FrameLayout(this).apply {
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, 0, pad, 0)
            addView(input)
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.rename_tag_title)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val newName = input.text.toString().trim()
                when {
                    newName == oldName -> Unit
                    newName.isEmpty() -> Toast.makeText(this, R.string.new_tag_hint, Toast.LENGTH_SHORT).show()
                    TagStore.rename(this, oldName, newName) -> {
                        if (selectedTags.remove(oldName)) selectedTags.add(newName)
                        buildTagBar()
                        refresh()
                    }
                    else -> Toast.makeText(this, R.string.tag_exists, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteTag(tag: String) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_tag)
            .setMessage(getString(R.string.delete_tag_message, tag))
            .setPositiveButton(R.string.delete) { _, _ ->
                TagStore.remove(this, tag)
                selectedTags.remove(tag)
                buildTagBar()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showTagSortDialog() {
        val names = resources.getStringArray(R.array.tag_sort_names)
        val current = SnippetsApp.getTagSort(this).coerceIn(0, names.lastIndex)
        AlertDialog.Builder(this)
            .setTitle(R.string.sort_tags)
            .setSingleChoiceItems(names, current) { dialog, which ->
                SnippetsApp.setTagSort(this, which)
                buildTagBar()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun sortTags(context: Context, tags: List<String>, mode: Int): List<String> {
        return when (mode) {
            TAG_SORT_NAME_ASC -> tags.sortedWith(chineseCollator)
            TAG_SORT_NAME_DESC -> tags.sortedWith(chineseCollator.reversed())
            TAG_SORT_USAGE -> {
                val counts = usageCounts(context)
                tags.sortedWith(compareByDescending<String> { counts[it] ?: 0 }.thenBy(chineseCollator, { it }))
            }
            TAG_SORT_RECENT -> tags.reversed()
            else -> tags
        }
    }

    private fun usageCounts(context: Context): Map<String, Int> {
        val counts = mutableMapOf<String, Int>()
        for (note in NoteStore.all(context) + TemplateStore.all(context)) {
            for (tag in note.tags) counts[tag] = (counts[tag] ?: 0) + 1
        }
        return counts
    }

    private fun defaultBackupName(): String {
        val date = android.text.format.DateFormat.format("yyyy-MM-dd", System.currentTimeMillis())
        return "snippets-backup-$date.json"
    }

    private fun doExport(uri: Uri) {
        val message = try {
            Backup.export(this, uri)
            R.string.export_done
        } catch (_: Exception) {
            R.string.export_failed
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun doExportMarkdown(uri: Uri) {
        try {
            val sb = StringBuilder()
            for (note in NoteStore.all(this)) {
                sb.append("# ").append(note.title.ifBlank { getString(R.string.untitled) }).append("\n\n")
                sb.append(MarkdownConverter.toMarkdown(note.html))
                sb.append("\n\n---\n\n")
            }
            if (TemplateStore.all(this).isNotEmpty()) {
                sb.append("# 模板\n\n")
                for (tpl in TemplateStore.all(this)) {
                    sb.append("## ").append(tpl.title.ifBlank { getString(R.string.untitled) }).append("\n\n")
                    sb.append(MarkdownConverter.toMarkdown(tpl.html))
                    sb.append("\n\n---\n\n")
                }
            }
            contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(sb.toString().toByteArray(Charsets.UTF_8))
            }
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmImport(uri: Uri) {
        val data = try {
            Backup.parse(this, uri)
        } catch (_: Exception) {
            Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.import_backup)
            .setMessage(getString(R.string.import_message, data.notes.size, data.templates.size))
            .setPositiveButton(R.string.import_merge) { _, _ -> applyImport(data, replace = false) }
            .setNegativeButton(R.string.import_replace) { _, _ -> applyImport(data, replace = true) }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun importMarkdown(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: run {
                Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
                return
            }
            // Split by top-level headings to create multiple notes.
            val sections = splitMarkdownByHeadings(text)
            for ((title, body) in sections) {
                val html = MarkdownConverter.toHtml(body)
                if (html.isNotBlank()) {
                    NoteStore.save(this, Note(id = 0, title = title, html = html, updatedAt = 0))
                }
            }
            refresh()
            Toast.makeText(this, R.string.import_done, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.import_markdown_failed, Toast.LENGTH_SHORT).show()
        }
    }

    /** Split a Markdown document by top-level (#) headings into (title, body) pairs. */
    private fun splitMarkdownByHeadings(md: String): List<Pair<String, String>> {
        val lines = md.lines()
        val sections = mutableListOf<Pair<String, String>>()
        var currentTitle = ""
        val currentBody = mutableListOf<String>()
        for (line in lines) {
            if (line.startsWith("# ") && currentBody.isNotEmpty()) {
                sections.add(currentTitle to currentBody.joinToString("\n"))
                currentTitle = line.removePrefix("# ").trim()
                currentBody.clear()
            } else {
                if (currentTitle.isEmpty() && line.startsWith("# ")) {
                    currentTitle = line.removePrefix("# ").trim()
                } else {
                    currentBody.add(line)
                }
            }
        }
        if (currentBody.isNotEmpty() || currentTitle.isNotEmpty()) {
            sections.add(currentTitle to currentBody.joinToString("\n"))
        }
        return if (sections.isEmpty()) listOf("" to md) else sections
    }

    private fun applyImport(data: Backup.Data, replace: Boolean) {
        val importedTags = (data.notes + data.templates).flatMap { it.tags }
        if (replace) {
            NoteStore.replaceAll(this, data.notes)
            TemplateStore.replaceAll(this, data.templates)
            TagStore.replaceAll(this, importedTags)
        } else {
            NoteStore.addAll(this, data.notes)
            TemplateStore.addAll(this, data.templates)
            for (tag in importedTags) if (tag.isNotBlank()) TagStore.add(this, tag)
        }
        buildTagBar()
        refreshWidgets()
        refresh()
        Toast.makeText(this, R.string.import_done, Toast.LENGTH_SHORT).show()
    }

    private fun showAutoBackupDialog() {
        val enabled = AutoBackupWorker.isEnabled(this)
        val items = arrayOf(
            getString(if (enabled) R.string.auto_backup_disabled else R.string.auto_backup_enabled),
            getString(R.string.manage_backups),
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.auto_backup)
            .setMessage(R.string.auto_backup_summary)
            .setSingleChoiceItems(items, -1) { dialog, which ->
                when (which) {
                    0 -> {
                        AutoBackupWorker.setEnabled(this, !enabled)
                        Toast.makeText(
                            this,
                            if (!enabled) R.string.auto_backup_enabled else R.string.auto_backup_disabled,
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                    1 -> showBackupsList()
                }
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun showBackupsList() {
        val backups = AutoBackupWorker.listBackups(this)
        if (backups.isEmpty()) {
            Toast.makeText(this, R.string.no_backups, Toast.LENGTH_SHORT).show()
            return
        }
        val names = backups.map { it.name }
        AlertDialog.Builder(this)
            .setTitle(R.string.manage_backups)
            .setItems(names.toTypedArray()) { _, which ->
                pendingBackupFile = backups[which]
                exportBackupLauncher.launch(backups[which].name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportPendingBackup(uri: Uri) {
        val file = pendingBackupFile ?: return
        try {
            contentResolver.openOutputStream(uri, "wt")?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            }
            Toast.makeText(this, R.string.export_done, Toast.LENGTH_SHORT).show()
        } catch (_: Exception) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show()
        }
        pendingBackupFile = null
    }

    private fun fillFromClipboard(template: Note) {
        val message = when (TemplateFiller.fillFromClipboard(this, template)) {
            TemplateFiller.FillResult.EMPTY_CLIPBOARD -> R.string.clipboard_empty
            TemplateFiller.FillResult.NO_SLOT -> R.string.template_no_slot
            TemplateFiller.FillResult.FILLED -> {
                refresh()
                R.string.filled_copied
            }
        }
        message?.let { Toast.makeText(this, it, Toast.LENGTH_SHORT).show() }
    }

    private fun confirmDelete(note: Note) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_note)
            .setMessage(getString(R.string.delete_confirm, note.title.ifBlank { getString(R.string.untitled) }))
            .setPositiveButton(R.string.delete) { _, _ ->
                TrashStore.add(this, note, templatesTab)
                store.delete(this, note.id)
                refreshWidgets()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { refresh() }
            .show()
    }

    private fun refreshWidgets() {
        if (templatesTab) {
            TemplateWidgetProvider.updateAllWidgets(this)
            TemplateCopyWidgetProvider.updateAllWidgets(this)
        } else {
            SnippetsWidgetProvider.updateAllWidgets(this)
        }
    }

    /** 更新 App 快捷方式：静态「新建片段」+ 动态最近使用的片段。 */
    private fun updateShortcuts() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val shortcutManager = getSystemService(ShortcutManager::class.java) ?: return
        val dynamic = mutableListOf<ShortcutInfo>()
        val recent = NoteStore.all(this)
            .filter { it.lastUsedAt > 0 }
            .sortedByDescending { it.lastUsedAt }
            .take(3)
        for ((i, note) in recent.withIndex()) {
            val intent = Intent(this, MainActivity::class.java).apply {
                action = Intent.ACTION_VIEW
                putExtra("shortcut_note_id", note.id)
                flags = Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
            dynamic.add(
                ShortcutInfo.Builder(this, "recent_$i")
                    .setShortLabel(note.title.ifBlank { getString(R.string.untitled) })
                    .setLongLabel(note.title.ifBlank { getString(R.string.untitled) })
                    .setIcon(Icon.createWithResource(this, R.drawable.ic_copy))
                    .setIntent(intent)
                    .build()
            )
        }
        try {
            shortcutManager.dynamicShortcuts = dynamic
        } catch (_: Exception) {
            // 快捷方式数量超限等异常，忽略即可。
        }
    }

    companion object {
        private const val KEY_TAB = "templates_tab"

        // 标签排序
        private const val TAG_SORT_NAME_ASC = 0
        private const val TAG_SORT_NAME_DESC = 1
        private const val TAG_SORT_USAGE = 2
        private const val TAG_SORT_RECENT = 3

        // 列表排序
        private const val SORT_MANUAL = 0
        private const val SORT_RECENT_USED = 1
        private const val SORT_MOST_USED = 2
        private const val SORT_NAME_ASC = 3
        private const val SORT_NAME_DESC = 4

        private val chineseCollator: Collator = Collator.getInstance(Locale.CHINESE)
    }
}
