package com.kvelzer.snippets

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Backup = one JSON document holding both stores. Read/written through the
 * Storage Access Framework (ACTION_CREATE_DOCUMENT / ACTION_OPEN_DOCUMENT via
 * the ActivityResult contracts in MainActivity), which needs no permissions —
 * the user picks the location and the app only touches that one Uri.
 *
 * Entry order in each array is the list order (top first). Ids are not
 * meaningful across devices (widget bindings don't survive a restore anyway),
 * so import ignores them and the store reassigns.
 */
object Backup {

    class Data(val notes: List<Note>, val templates: List<Note>)

    fun export(context: Context, uri: Uri) {
        val payload = JSONObject()
            .put("version", 1)
            .put("notes", toArray(NoteStore.all(context)))
            .put("templates", toArray(TemplateStore.all(context)))
            .toString()
        // "wt" truncates: CREATE_DOCUMENT can hand back an existing (longer)
        // file, and plain "w" would leave trailing garbage after the JSON.
        val out = context.contentResolver.openOutputStream(uri, "wt")
            ?: throw IOException("Cannot open $uri")
        out.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
    }

    /** Throws on unreadable/unparseable input; caller reports the failure. */
    fun parse(context: Context, uri: Uri): Data {
        val text = context.contentResolver.openInputStream(uri)
            ?.use { it.readBytes().toString(Charsets.UTF_8) }
            ?: throw IOException("Cannot open $uri")
        val json = JSONObject(text)
        return Data(
            notes = fromArray(json.optJSONArray("notes")),
            templates = fromArray(json.optJSONArray("templates")),
        )
    }

    private fun toArray(notes: List<Note>): JSONArray {
        val array = JSONArray()
        for (n in notes) {
            val tagArr = JSONArray()
            for (t in n.tags) tagArr.put(t)
            array.put(
                JSONObject()
                    .put("title", n.title)
                    .put("html", n.html)
                    .put("updatedAt", n.updatedAt)
                    .put("tags", tagArr)
            )
        }
        return array
    }

    private fun fromArray(array: JSONArray?): List<Note> {
        if (array == null) return emptyList()
        val notes = mutableListOf<Note>()
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            notes.add(
                Note(
                    id = 0,
                    title = o.optString("title"),
                    html = o.getString("html"),
                    updatedAt = o.optLong("updatedAt", System.currentTimeMillis()),
                    tags = o.optJSONArray("tags")?.let { arr ->
                        (0 until arr.length()).map { arr.getString(it) }
                    } ?: emptyList(),
                )
            )
        }
        return notes
    }
}
