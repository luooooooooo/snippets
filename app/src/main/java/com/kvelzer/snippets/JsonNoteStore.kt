package com.kvelzer.snippets

import android.content.Context
import android.util.AtomicFile
import android.util.Log
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Tiny synchronous JSON-file store: all entries live in one file in internal
 * storage and in an in-memory cache. Synchronous reads are a hard requirement —
 * the widget trampolines must fetch an entry and use the clipboard inside the
 * brief window where their translucent activity holds focus, so no async layer
 * (Room + coroutines) is worth the complexity for lists of short snippets.
 *
 * Writes go through AtomicFile (write to temp file, then rename) so a crash
 * mid-write cannot corrupt existing data. An unreadable file is moved aside
 * (<name>.corrupt) instead of being silently overwritten by the next save.
 *
 * List order is manual: [Note.sortOrder] ascending, new entries on top.
 *
 * Two instances exist: [NoteStore] (snippets) and [TemplateStore] (templates).
 */
open class JsonNoteStore(private val fileName: String) {

    private val lock = Any()
    private var cache: MutableList<Note>? = null

    fun all(context: Context): List<Note> = synchronized(lock) {
        load(context).sortedBy { it.sortOrder }
    }

    fun get(context: Context, id: Long): Note? = synchronized(lock) {
        load(context).find { it.id == id }
    }

    /** Insert (id == 0, lands on top of the list) or update (keeps its position). */
    fun save(context: Context, note: Note): Note = synchronized(lock) {
        val notes = load(context)
        val stored = if (note.id == 0L) {
            note.copy(
                id = (notes.maxOfOrNull { it.id } ?: 0L) + 1,
                updatedAt = System.currentTimeMillis(),
                sortOrder = (notes.minOfOrNull { it.sortOrder } ?: 1) - 1,
            )
        } else {
            val existing = notes.find { it.id == note.id }
            notes.removeAll { it.id == note.id }
            note.copy(
                updatedAt = System.currentTimeMillis(),
                sortOrder = existing?.sortOrder
                    ?: ((notes.minOfOrNull { it.sortOrder } ?: 1) - 1),
            )
        }
        notes.add(stored)
        persist(context, notes)
        stored
    }

    fun delete(context: Context, id: Long): Unit = synchronized(lock) {
        val notes = load(context)
        if (notes.removeAll { it.id == id }) {
            persist(context, notes)
        }
    }

    /** Import (merge): [newNotes] land on top keeping their relative order; ids reassigned. */
    fun addAll(context: Context, newNotes: List<Note>): Unit = synchronized(lock) {
        if (newNotes.isEmpty()) return
        val notes = load(context)
        var id = notes.maxOfOrNull { it.id } ?: 0L
        val top = (notes.minOfOrNull { it.sortOrder } ?: 1) - newNotes.size
        newNotes.forEachIndexed { i, n ->
            notes.add(n.copy(id = ++id, sortOrder = top + i))
        }
        persist(context, notes)
    }

    /** Import (replace): the list becomes exactly [newNotes] (top first); ids reassigned. */
    fun replaceAll(context: Context, newNotes: List<Note>): Unit = synchronized(lock) {
        val notes = load(context)
        notes.clear()
        newNotes.forEachIndexed { i, n ->
            notes.add(n.copy(id = (i + 1).toLong(), sortOrder = i))
        }
        persist(context, notes)
    }

    /** Rewrites sortOrder to match [orderedIds] (top first), after a drag. */
    fun updateOrder(context: Context, orderedIds: List<Long>): Unit = synchronized(lock) {
        val notes = load(context)
        val position = orderedIds.withIndex().associate { (i, id) -> id to i }
        notes.replaceAll { note ->
            position[note.id]?.let { note.copy(sortOrder = it) } ?: note
        }
        persist(context, notes)
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, fileName)

    private fun load(context: Context): MutableList<Note> {
        cache?.let { return it }
        val notes = mutableListOf<Note>()
        val f = file(context)
        if (f.exists()) {
            try {
                val json = JSONObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
                val array = json.getJSONArray("notes")
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    notes.add(
                        Note(
                            id = o.getLong("id"),
                            title = o.getString("title"),
                            html = o.getString("html"),
                            updatedAt = o.getLong("updatedAt"),
                            sortOrder = o.optInt("sortOrder", Int.MIN_VALUE),
                            tags = o.optJSONArray("tags")?.let { arr ->
                                (0 until arr.length()).map { arr.getString(it) }
                            } ?: emptyList(),
                        )
                    )
                }
                migrateOrderIfNeeded(notes)
            } catch (_: Exception) {
                // Unreadable file: move it aside so the next save can't destroy
                // data that might still be (partially) recoverable by hand.
                notes.clear()
                f.renameTo(File(f.parentFile, "$fileName.corrupt"))
            }
        }
        cache = notes
        return notes
    }

    /** Files written before sortOrder existed: seed order from updatedAt (newest on top). */
    private fun migrateOrderIfNeeded(notes: MutableList<Note>) {
        if (notes.none { it.sortOrder == Int.MIN_VALUE }) return
        val seeded = notes.sortedByDescending { it.updatedAt }
            .mapIndexed { i, note -> note.copy(sortOrder = i) }
        notes.clear()
        notes.addAll(seeded)
    }

    /**
     * A failed write (disk full...) must not crash the caller — several save
     * paths run inside back-press or widget-tap handling where an exception
     * loses the user's edit AND takes the app down. The cache keeps the new
     * state for this session and the next successful persist writes it out;
     * the user is told so the failure isn't silent.
     */
    private fun persist(context: Context, notes: MutableList<Note>) {
        cache = notes
        val array = JSONArray()
        for (n in notes) {
            val tagArr = JSONArray()
            for (t in n.tags) tagArr.put(t)
            array.put(
                JSONObject()
                    .put("id", n.id)
                    .put("title", n.title)
                    .put("html", n.html)
                    .put("updatedAt", n.updatedAt)
                    .put("sortOrder", n.sortOrder)
                    .put("tags", tagArr)
            )
        }
        val payload = JSONObject().put("notes", array).toString()
        val atomic = AtomicFile(file(context))
        try {
            val out = atomic.startWrite()
            try {
                out.write(payload.toByteArray(Charsets.UTF_8))
                atomic.finishWrite(out)
            } catch (e: Exception) {
                atomic.failWrite(out)
                throw e
            }
        } catch (e: Exception) {
            Log.e("JsonNoteStore", "Failed to persist $fileName", e)
            Toast.makeText(
                context.applicationContext, R.string.save_failed, Toast.LENGTH_LONG
            ).show()
        }
    }
}

/** Regular snippets. */
object NoteStore : JsonNoteStore("notes.json")

/** Templates: snippets with one replaceable slot (see TemplateFiller). */
object TemplateStore : JsonNoteStore("templates.json")
