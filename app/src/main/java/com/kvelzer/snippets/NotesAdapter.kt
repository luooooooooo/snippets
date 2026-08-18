package com.kvelzer.snippets

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import androidx.recyclerview.widget.RecyclerView

class NotesAdapter(
    private val onOpen: (Note) -> Unit,
    private val onCopy: (Note) -> Unit,
    private val onDeleteRequest: (Note) -> Unit,
    private val onFill: (Note) -> Unit,
) : RecyclerView.Adapter<NotesAdapter.Holder>() {

    /** Templates tab shows the fill-from-clipboard button on each row. */
    var showFillButton = false

    private val notes = mutableListOf<Note>()

    // Deriving the preview parses HTML; cache it per note, keyed by updatedAt
    // so an edit invalidates the entry.
    private val previewCache = HashMap<Long, Pair<Long, String>>()

    fun submit(newNotes: List<Note>) {
        notes.clear()
        notes.addAll(newNotes)
        previewCache.keys.retainAll(newNotes.map { it.id }.toSet())
        notifyDataSetChanged()
    }

    /** Reorders in response to a drag; the caller persists via [noteIds] on drop. */
    fun moveItem(from: Int, to: Int) {
        if (from == to || from !in notes.indices || to !in notes.indices) return
        // ItemTouchHelper can report a jump of more than one position on a fast
        // drag; a swap would leave the passed-over items misplaced, a move won't.
        notes.add(to, notes.removeAt(from))
        notifyItemMoved(from, to)
    }

    fun noteIds(): List<Long> = notes.map { it.id }

    /** Returns the note at a visible position, or null if out of range. */
    fun noteAt(position: Int): Note? = notes.getOrNull(position)

    /**
     * 滑动删除时先把条目移出列表（让 ItemTouchHelper 与界面状态一致）；
     * 用户取消删除时由调用方 refresh() 从存储重新加载恢复。
     */
    fun removeAt(position: Int) {
        if (position !in notes.indices) return
        notes.removeAt(position)
        notifyItemRemoved(position)
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

    override fun getItemCount() = notes.size

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.bind(notes[position])
    }

    inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
        private val title: TextView = view.findViewById(R.id.note_title)
        private val preview: TextView = view.findViewById(R.id.note_preview)
        private val fillButton: ImageButton = view.findViewById(R.id.button_fill)
        private val tagsGroup: ChipGroup = view.findViewById(R.id.note_tags)

        fun bind(note: Note) {
            val context = itemView.context
            title.text = note.title.ifBlank { context.getString(R.string.untitled) }
            preview.text = previewFor(note)
            // 点按整行 = 复制（带格式）；编辑/删除改由左右滑动触发。
            itemView.setOnClickListener { onCopy(note) }
            fillButton.visibility = if (showFillButton) View.VISIBLE else View.GONE
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
}
