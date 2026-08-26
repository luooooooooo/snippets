package com.kvelzer.snippets

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView

class NotesAdapter(
    private val onOpen: (Note) -> Unit,
    private val onCopy: (Note) -> Unit,
    private val onDeleteRequest: (Note) -> Unit,
    private val onFill: (Note) -> Unit,
    private val onSelectionChanged: (Set<Long>) -> Unit = {},
) : ListAdapter<Note, NotesAdapter.Holder>(NoteDiffCallback()) {

    /** Templates tab shows the fill-from-clipboard button on each row. */
    var showFillButton = false

    /** Multi-select mode: when true, checkboxes are visible and taps toggle. */
    var selectionMode = false
        set(value) {
            field = value
            if (!value) selectedIds.clear()
            notifyDataSetChanged()
        }

    private val selectedIds = mutableSetOf<Long>()

    fun isSelected(id: Long) = id in selectedIds

    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        onSelectionChanged(selectedIds.toSet())
        notifyItemChanged(currentList.indexOfFirst { it.id == id })
    }

    fun selectAll() {
        selectedIds.clear()
        selectedIds.addAll(currentList.map { it.id })
        onSelectionChanged(selectedIds.toSet())
        notifyDataSetChanged()
    }

    fun clearSelection() {
        selectedIds.clear()
        onSelectionChanged(emptySet())
        notifyDataSetChanged()
    }

    fun getSelected(): List<Note> = currentList.filter { it.id in selectedIds }

    // Deriving the preview parses HTML; cache it per note, keyed by updatedAt
    // so an edit invalidates the entry.
    private val previewCache = HashMap<Long, Pair<Long, String>>()

    /** Reorders in response to a drag; the caller persists via [noteIds] on drop. */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in currentList.indices || to !in currentList.indices) return
        val newList = currentList.toMutableList()
        // ItemTouchHelper can report a jump of more than one position on a fast
        // drag; a swap would leave the passed-over items misplaced, a move won't.
        newList.add(to, newList.removeAt(from))
        submitList(newList)
    }

    fun noteIds(): List<Long> = currentList.map { it.id }

    /** Returns the note at a visible position, or null if out of range. */
    fun noteAt(position: Int): Note? = currentList.getOrNull(position)

    /**
     * 滑动删除时先把条目移出列表（让 ItemTouchHelper 与界面状态一致）；
     * 用户取消删除时由调用方 refresh() 从存储重新加载恢复。
     */
    fun removeAt(position: Int) {
        if (position !in currentList.indices) return
        val newList = currentList.toMutableList()
        newList.removeAt(position)
        submitList(newList)
    }

    private fun previewFor(note: Note): String {
        previewCache[note.id]?.let { (stamp, text) ->
            if (stamp == note.updatedAt) return text
        }
        val text = HtmlConverter.plainText(note.html).replace('\n', ' ').take(120)
        previewCache[note.id] = note.updatedAt to text
        return text
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_note, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.note_title)
        private val preview: TextView = view.findViewById(R.id.note_preview)
        private val fillButton: ImageButton = view.findViewById(R.id.button_fill)
        private val tagsGroup: ChipGroup = view.findViewById(R.id.note_tags)
        private val checkBox: CheckBox = view.findViewById(R.id.note_check)

        fun bind(note: Note) {
            val context = itemView.context
            title.text = note.title.ifBlank { context.getString(R.string.untitled) }
            preview.text = previewFor(note)

            // 多选模式：显示复选框，点按切换选中；普通模式：点按整行 = 复制。
            checkBox.visibility = if (selectionMode) View.VISIBLE else View.GONE
            checkBox.isChecked = note.id in selectedIds
            if (selectionMode) {
                itemView.setOnClickListener { toggleSelection(note.id) }
            } else {
                itemView.setOnClickListener { onCopy(note) }
            }

            fillButton.visibility = if (showFillButton && !selectionMode) View.VISIBLE else View.GONE
            fillButton.setOnClickListener { onFill(note) }

            // 渲染该笔记归属的标签（只读、不可点击）。
            tagsGroup.removeAllViews()
            if (note.tags.isEmpty()) {
                tagsGroup.visibility = View.GONE
            } else {
                tagsGroup.visibility = View.VISIBLE
                for (tag in note.tags) {
                    val chip = Chip(context).apply {
                        text = tag
                        isClickable = false
                        isCheckable = false
                        setEnsureMinTouchTargetSize(false)
                    }
                    tagsGroup.addView(chip)
                }
            }
        }
    }

    class NoteDiffCallback : DiffUtil.ItemCallback<Note>() {
        override fun areItemsTheSame(oldItem: Note, newItem: Note) = oldItem.id == newItem.id
        override fun areContentsTheSame(oldItem: Note, newItem: Note) = oldItem == newItem
    }
}
