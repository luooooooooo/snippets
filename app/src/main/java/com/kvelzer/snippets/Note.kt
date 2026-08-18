package com.kvelzer.snippets

/**
 * One snippet. [html] is the body, stored verbatim (never round-tripped through
 * a lossy HTML converter).
 * [sortOrder] is the user's manual list position (ascending); it is assigned
 * by NoteStore and never derived from timestamps.
 * [tags] are free-form category labels (multi-select) used for filtering on the
 * main list; each note may belong to several tags.
 */
data class Note(
    val id: Long,
    val title: String,
    val html: String,
    val updatedAt: Long,
    val sortOrder: Int = 0,
    val tags: List<String> = emptyList(),
)
