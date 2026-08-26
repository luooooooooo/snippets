package com.kvelzer.snippets

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Stores deleted notes temporarily so they can be restored. Each entry keeps
 * the original Note plus whether it came from NoteStore or TemplateStore and
 * the deletion timestamp. One file, same AtomicFile safety as JsonNoteStore.
 */
object TrashStore {

    private const val FILE_NAME = "trash.json"
    private val lock = Any()
    private var cache: MutableList<TrashItem>? = null

    data class TrashItem(
        val note: Note,
        val isTemplate: Boolean,
        val deletedAt: Long,
    )

    fun all(context: Context): List<TrashItem> = synchronized(lock) {
        load(context).sortedByDescending { it.deletedAt }
    }

    fun add(context: Context, note: Note, isTemplate: Boolean) = synchronized(lock) {
        val items = load(context)
        items.add(TrashItem(note, isTemplate, System.currentTimeMillis()))
        persist(context, items)
    }

    /** Restore an item to its original store; returns the restored Note. */
    fun restore(context: Context, position: Int): Note? = synchronized(lock) {
        val items = load(context)
        if (position !in items.indices) return null
        val item = items.removeAt(position)
        persist(context, items)
        val store = if (item.isTemplate) TemplateStore else NoteStore
        // Re-insert on top with a fresh id.
        store.save(context, item.note.copy(id = 0))
    }

    fun deleteForever(context: Context, position: Int) = synchronized(lock) {
        val items = load(context)
        if (position in items.indices) {
            items.removeAt(position)
            persist(context, items)
        }
    }

    fun clear(context: Context) = synchronized(lock) {
        val items = load(context)
        items.clear()
        persist(context, items)
    }

    fun size(context: Context): Int = synchronized(lock) { load(context).size }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun load(context: Context): MutableList<TrashItem> {
        cache?.let { return it }
        val items = mutableListOf<TrashItem>()
        val f = file(context)
        if (f.exists()) {
            try {
                val json = JSONObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
                val array = json.getJSONArray("items")
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val n = o.getJSONObject("note")
                    items.add(
                        TrashItem(
                            note = Note(
                                id = n.getLong("id"),
                                title = n.getString("title"),
                                html = n.getString("html"),
                                updatedAt = n.getLong("updatedAt"),
                                sortOrder = n.optInt("sortOrder", 0),
                                tags = n.optJSONArray("tags")?.let { arr ->
                                    (0 until arr.length()).map { arr.getString(it) }
                                } ?: emptyList(),
                                useCount = n.optInt("useCount", 0),
                                lastUsedAt = n.optLong("lastUsedAt", 0),
                            ),
                            isTemplate = o.getBoolean("isTemplate"),
                            deletedAt = o.getLong("deletedAt"),
                        )
                    )
                }
            } catch (_: Exception) {
                f.renameTo(File(f.parentFile, "$FILE_NAME.corrupt"))
            }
        }
        cache = items
        return items
    }

    private fun persist(context: Context, items: MutableList<TrashItem>) {
        cache = items
        val array = JSONArray()
        for (item in items) {
            val n = item.note
            val tagArr = JSONArray()
            for (t in n.tags) tagArr.put(t)
            array.put(
                JSONObject()
                    .put(
                        "note",
                        JSONObject()
                            .put("id", n.id)
                            .put("title", n.title)
                            .put("html", n.html)
                            .put("updatedAt", n.updatedAt)
                            .put("sortOrder", n.sortOrder)
                            .put("tags", tagArr)
                            .put("useCount", n.useCount)
                            .put("lastUsedAt", n.lastUsedAt),
                    )
                    .put("isTemplate", item.isTemplate)
                    .put("deletedAt", item.deletedAt),
            )
        }
        val payload = JSONObject().put("items", array).toString()
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
        } catch (_: Exception) {
            // Best-effort.
        }
    }
}
