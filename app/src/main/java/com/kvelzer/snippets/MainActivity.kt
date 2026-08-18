package com.kvelzer.snippets

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kvelzer.snippets.widget.SnippetsWidgetProvider
import com.kvelzer.snippets.widget.TemplateCopyWidgetProvider
import com.kvelzer.snippets.widget.TemplateWidgetProvider
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.tabs.TabLayout

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
    // 期间 clearView 的 updateOrder 会基于“少一条”的列表重排 sortOrder，
    // 若用户取消删除，恢复的条目会与重排后的兄弟错位；故滑动删除时跳过。
    private var swipeDeleteInProgress = false

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            uri?.let { doExport(it) }
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { confirmImport(it) }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        SnippetsApp.applyColorTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(findViewById(R.id.toolbar))

        emptyView = findViewById(R.id.empty_view)
        emptyText = findViewById(R.id.empty_text)

        templatesTab = savedInstanceState?.getBoolean(KEY_TAB) == true
        val tabs = findViewById<TabLayout>(R.id.tabs)
        if (templatesTab) tabs.selectTab(tabs.getTabAt(1))
        tabs.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                templatesTab = tab.position == 1
                refresh()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) = Unit
            override fun onTabReselected(tab: TabLayout.Tab) = Unit
        })

        val onOpen: (Note) -> Unit = { note ->
            startActivity(
                Intent(this, EditorActivity::class.java)
                    .putExtra(EditorActivity.EXTRA_NOTE_ID, note.id)
                    .putExtra(EditorActivity.EXTRA_TEMPLATE, templatesTab)
            )
        }
        val onCopy: (Note) -> Unit = { note ->
            ClipboardHelper.copyNote(this, note)
            ClipboardHelper.showCopiedFeedback(this)
        }
        val onDeleteRequest: (Note) -> Unit = { note -> confirmDelete(note) }
        val onFill: (Note) -> Unit = { note -> fillFromClipboard(note) }

        adapter = NotesAdapter(
            onOpen = onOpen,
            onCopy = onCopy,
            onDeleteRequest = onDeleteRequest,
            onFill = onFill,
        )

        val list = findViewById<RecyclerView>(R.id.notes_list)
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        // 顶部标签栏：可左右滑动、多选筛选；末尾 + 用于新建标签。
        tagBar = findViewById(R.id.tag_bar)
        buildTagBar()

        // 长按拖动重新排序；水平滑动触发动作：
        // 从左往右滑（END）打开该条进行编辑，从右往左滑（START）删除该条。
        // 普通点按（在适配器里处理）则复制该条。
        ItemTouchHelper(object :
            ItemTouchHelper.SimpleCallback(
                ItemTouchHelper.UP or ItemTouchHelper.DOWN,
                ItemTouchHelper.START or ItemTouchHelper.END,
            ) {
            // Dragging a filtered list would persist only the visible subset's
            // order and interleave it with the hidden notes unpredictably.
            override fun isLongPressDragEnabled() = searchQuery.isBlank()

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
                    // 从左往右滑：编辑。编辑是整页跳转，返回时 onResume 会 refresh 恢复列表。
                    ItemTouchHelper.END -> onOpen(note)
                    // 从右往左滑：删除。先移出列表，让 ItemTouchHelper 完成移除动画、
                    // 不残留滑出状态；再弹确认框。取消或点空白时 confirmDelete 内部
                    // refresh() 从存储恢复该条目——不会再卡在半删状态。
                    ItemTouchHelper.START -> {
                        adapter.removeAt(position)
                        swipeDeleteInProgress = true
                        confirmDelete(note)
                    }
                }
            }

            override fun onChildDraw(
                c: Canvas,
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                dX: Float,
                dY: Float,
                actionState: Int,
                isCurrentlyActive: Boolean,
            ) {
                if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                    val itemView = viewHolder.itemView
                    val top = itemView.top.toFloat()
                    val bottom = itemView.bottom.toFloat()
                    val paint = Paint().apply {
                        color = if (dX > 0) editBgColor else deleteBgColor
                    }
                    if (dX > 0) {
                        // 从左往右滑：编辑（绿底 + 铅笔）
                        c.drawRect(0f, top, dX, bottom, paint)
                        drawSwipeIcon(c, editIcon, swipeIconMargin + swipeIconSize / 2f, (top + bottom) / 2f)
                    } else if (dX < 0) {
                        // 从右往左滑：删除（红底 + 垃圾桶）
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
                drawable.setBounds(
                    (cx - half).toInt(),
                    (cy - half).toInt(),
                    (cx + half).toInt(),
                    (cy + half).toInt(),
                )
                drawable.draw(c)
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                // 滑动删除进行中：条目已临时移出列表，此时按“少一条”的列表重排
                // 顺序会让取消恢复时错位，跳过；refresh() 会从存储整体恢复。
                if (!swipeDeleteInProgress) {
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
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_TAB, templatesTab)
    }

    override fun onResume() {
        super.onResume()
        // 重建标签栏：编辑器内新建的标签（TagStore 已写库）返回首页后要能显示。
        buildTagBar()
        refresh()
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
        R.id.action_theme -> {
            pickTheme()
            true
        }
        R.id.action_color -> {
            pickColorTheme()
            true
        }
        R.id.action_export -> {
            exportLauncher.launch(defaultBackupName())
            true
        }
        R.id.action_import -> {
            importLauncher.launch(arrayOf("*/*"))
            true
        }
        else -> super.onOptionsItemSelected(item)
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
                recreate() // 重新创建以应用新的配色
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun refresh() {
        // 滑动删除的确认/取消都会走到这里，结束本轮删除流程。
        swipeDeleteInProgress = false
        val all = store.all(this)
        val query = searchQuery.trim()
        val filtered = all.filter { note ->
            val matchesQuery = query.isEmpty() || note.title.contains(query, ignoreCase = true) ||
                HtmlConverter.plainText(note.html).contains(query, ignoreCase = true)
            // 标签多选：未选任何标签 = 全部；否则命中任一选中标签即显示。
            val matchesTags = selectedTags.isEmpty() || note.tags.any { it in selectedTags }
            matchesQuery && matchesTags
        }
        adapter.showFillButton = templatesTab
        adapter.submit(filtered)
        emptyText.setText(
            when {
                query.isNotEmpty() -> R.string.search_no_results
                selectedTags.isEmpty() -> if (templatesTab) R.string.empty_hint_templates else R.string.empty_hint
                else -> R.string.empty_filtered
            }
        )
        emptyView.visibility = if (filtered.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 重建顶部标签栏：固定「全部」+ 各标签 chip + 末尾「+」新建。 */
    private fun buildTagBar() {
        tagBar.removeAllViews()
        val context = this

        // 「全部」：选中态表示未筛选；它被选中时清空 selectedTags。
        val allChip = Chip(context).apply {
            text = getString(R.string.tag_all)
            isCheckable = true
            isChecked = selectedTags.isEmpty()
            setOnCheckedChangeListener { _, checked ->
                if (checked) {
                    // 选「全部」即取消其它已选标签，回到未筛选。
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

        for (tag in TagStore.all(context)) {
            val chip = Chip(context).apply {
                text = tag
                isCheckable = true
                isChecked = tag in selectedTags
                setOnCheckedChangeListener { _, checked ->
                    // 选了具体标签，就取消「全部」的选中态，避免视觉矛盾。
                    if (checked) {
                        selectedTags.add(tag)
                        allChip.isChecked = false
                    } else {
                        selectedTags.remove(tag)
                        // 取消到最后一个标签时自动回到「全部」。
                        if (selectedTags.isEmpty()) allChip.isChecked = true
                    }
                    refresh()
                }
                // 长按弹出操作菜单：重命名 / 删除。
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

    /** 弹出输入框让用户新建一个标签（自动去重，空名忽略）。 */
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
                    selectedTags.add(name) // 新建后默认选中它
                    buildTagBar()
                    refresh()
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    /** 长按标签 chip 弹出的操作菜单：重命名 / 删除。 */
    private fun showTagMenu(anchor: View, tag: String) {
        PopupMenu(this, anchor).apply {
            menu.add(Menu.NONE, 1, 0, R.string.edit_tag).setOnMenuItemClickListener {
                editTag(tag)
                true
            }
            menu.add(Menu.NONE, 2, 1, R.string.delete_tag).setOnMenuItemClickListener {
                confirmDeleteTag(tag)
                true
            }
            show()
        }
    }

    /** 重命名标签：预填当前名称，确定后同步标签集合与所有笔记。 */
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
                    newName == oldName -> Unit // 没改，跳过
                    newName.isEmpty() -> Toast.makeText(this, R.string.new_tag_hint, Toast.LENGTH_SHORT).show()
                    TagStore.rename(this, oldName, newName) -> {
                        // 若正在筛选旧标签，选中集合跟着换名。
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

    /** 删除标签：确认后从标签集合与所有笔记中剥离，并退出该标签的筛选。 */
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

    private fun applyImport(data: Backup.Data, replace: Boolean) {
        if (replace) {
            NoteStore.replaceAll(this, data.notes)
            TemplateStore.replaceAll(this, data.templates)
        } else {
            NoteStore.addAll(this, data.notes)
            TemplateStore.addAll(this, data.templates)
        }
        SnippetsWidgetProvider.updateAllWidgets(this)
        TemplateWidgetProvider.updateAllWidgets(this)
        TemplateCopyWidgetProvider.updateAllWidgets(this)
        refresh()
        Toast.makeText(this, R.string.import_done, Toast.LENGTH_SHORT).show()
    }

    /** Same operation as tapping the template's home-screen widget. */
    private fun fillFromClipboard(template: Note) {
        val message = when (TemplateFiller.fillFromClipboard(this, template)) {
            TemplateFiller.FillResult.EMPTY_CLIPBOARD -> R.string.clipboard_empty
            TemplateFiller.FillResult.NO_SLOT -> R.string.template_no_slot
            TemplateFiller.FillResult.FILLED -> {
                refresh() // preview shows the new value
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
                store.delete(this, note.id)
                if (templatesTab) {
                    TemplateWidgetProvider.updateAllWidgets(this)
                    TemplateCopyWidgetProvider.updateAllWidgets(this)
                } else {
                    SnippetsWidgetProvider.updateAllWidgets(this)
                }
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            // 点对话框外空白处也会走到这里：条目还没删，刷新列表即可恢复原位，
            // 不再需要切换标签页来解除“半删”卡死状态。
            .setOnDismissListener { refresh() }
            .show()
    }

    companion object {
        private const val KEY_TAB = "templates_tab"
    }
}
