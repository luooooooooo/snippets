package com.kvelzer.snippets

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Holds the global set of tag labels (e.g. "工作", "生活"). Tags are free-form
 * strings the user creates; notes reference them by name via [Note.tags].
 *
 * One shared collection is used across snippets and templates so a label can be
 * reused on either list. Stored in its own tiny file with the same AtomicFile
 * safety as [JsonNoteStore].
 */
object TagStore {

    private const val FILE_NAME = "tags.json"
    private val lock = Any()
    private var cache: List<String>? = null

    fun all(context: Context): List<String> = synchronized(lock) {
        load(context)
    }

    /** Adds a tag if absent (returns false when it already exists). */
    fun add(context: Context, tag: String): Boolean = synchronized(lock) {
        val name = tag.trim()
        if (name.isEmpty()) return false
        val tags = load(context).toMutableList()
        if (tags.contains(name)) return false
        tags.add(name)
        persist(context, tags)
        true
    }

    /** Removes a tag everywhere: from this collection and from every note. */
    fun remove(context: Context, tag: String) {
        synchronized(lock) {
            val tags = load(context).toMutableList()
            if (!tags.remove(tag)) return
            persist(context, tags)
        }
        // Strip the tag from all notes (both stores) so it can't dangle.
        for (store in listOf(NoteStore, TemplateStore)) {
            val notes = store.all(context)
            notes.forEach { note ->
                if (note.tags.contains(tag)) {
                    store.save(context, note.copy(tags = note.tags - tag))
                }
            }
        }
    }

    /**
     * Renames a tag everywhere: in this collection and on every note.
     * Returns false when [newName] is blank, unchanged, already taken, or
     * [oldName] doesn't exist. Keeps note references consistent.
     */
    fun rename(context: Context, oldName: String, newName: String): Boolean {
        val clean = newName.trim()
        if (clean.isEmpty() || clean == oldName) return false
        val ok = synchronized(lock) {
            val tags = load(context).toMutableList()
            if (!tags.contains(oldName) || tags.contains(clean)) {
                false
            } else {
                tags[tags.indexOf(oldName)] = clean
                persist(context, tags)
                true
            }
        }
        if (!ok) return false
        // Update the label on every note that carries it (both stores).
        for (store in listOf(NoteStore, TemplateStore)) {
            val notes = store.all(context)
            notes.forEach { note ->
                if (note.tags.contains(oldName)) {
                    store.save(context, note.copy(tags = note.tags.map { if (it == oldName) clean else it }))
                }
            }
        }
        return true
    }

    private fun file(context: Context) = File(context.applicationContext.filesDir, FILE_NAME)

    private fun load(context: Context): MutableList<String> {
        cache?.let { return it.toMutableList() }
        val tags = mutableListOf<String>()
        val f = file(context)
        if (f.exists()) {
            try {
                val json = JSONObject(AtomicFile(f).readFully().toString(Charsets.UTF_8))
                val array = json.optJSONArray("tags")
                if (array != null) {
                    for (i in 0 until array.length()) tags.add(array.getString(i))
                }
            } catch (_: Exception) {
                f.renameTo(File(f.parentFile, "$FILE_NAME.corrupt"))
            }
        }
        cache = tags
        return tags.toMutableList()
    }

    private fun persist(context: Context, tags: List<String>) {
        cache = tags
        val array = JSONArray()
        for (t in tags) array.put(t)
        val payload = JSONObject().put("tags", array).toString()
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
            // Best-effort: a failed tag persist shouldn't crash the caller.
        }
    }
}
