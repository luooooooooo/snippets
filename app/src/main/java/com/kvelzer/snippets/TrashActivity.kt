package com.kvelzer.snippets

import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kvelzer.snippets.widget.SnippetsWidgetProvider
import com.kvelzer.snippets.widget.TemplateCopyWidgetProvider
import com.kvelzer.snippets.widget.TemplateWidgetProvider
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TrashActivity : AppCompatActivity() {

    private lateinit var adapter: TrashAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        SnippetsApp.applyColorTheme(this)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_trash)
        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = getString(R.string.trash)

        adapter = TrashAdapter(
            onRestore = { position -> restoreItem(position) },
            onDeleteForever = { position -> confirmDeleteForever(position) },
        )

        findViewById<RecyclerView>(R.id.trash_list).apply {
            layoutManager = LinearLayoutManager(this@TrashActivity)
            this.adapter = this@TrashActivity.adapter
        }

        refresh()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_trash, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean = when (item.itemId) {
        R.id.action_empty_trash -> {
            confirmEmptyTrash()
            true
        }
        else -> super.onOptionsItemSelected(item)
    }

    private fun refresh() {
        val items = TrashStore.all(this)
        adapter.submit(items)
        findViewById<View>(R.id.trash_empty).visibility =
            if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun restoreItem(position: Int) {
        val restored = TrashStore.restore(this, position)
        if (restored != null) {
            SnippetsWidgetProvider.updateAllWidgets(this)
            TemplateWidgetProvider.updateAllWidgets(this)
            TemplateCopyWidgetProvider.updateAllWidgets(this)
            Toast.makeText(this, R.string.restored, Toast.LENGTH_SHORT).show()
        }
        refresh()
    }

    private fun confirmDeleteForever(position: Int) {
        AlertDialog.Builder(this)
            .setTitle(R.string.delete_forever)
            .setMessage(R.string.delete_forever_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                TrashStore.deleteForever(this, position)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun confirmEmptyTrash() {
        if (TrashStore.size(this) == 0) return
        AlertDialog.Builder(this)
            .setTitle(R.string.empty_trash)
            .setMessage(R.string.empty_trash_confirm)
            .setPositiveButton(R.string.delete) { _, _ ->
                TrashStore.clear(this)
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    class TrashAdapter(
        private val onRestore: (Int) -> Unit,
        private val onDeleteForever: (Int) -> Unit,
    ) : RecyclerView.Adapter<TrashAdapter.Holder>() {

        private val items = mutableListOf<TrashStore.TrashItem>()
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

        fun submit(newItems: List<TrashStore.TrashItem>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_trash, parent, false)
            return Holder(view)
        }

        override fun getItemCount() = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], position)
        }

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            private val title: TextView = view.findViewById(R.id.trash_title)
            private val preview: TextView = view.findViewById(R.id.trash_preview)
            private val type: TextView = view.findViewById(R.id.trash_type)
            private val date: TextView = view.findViewById(R.id.trash_date)

            fun bind(item: TrashStore.TrashItem, position: Int) {
                val context = itemView.context
                title.text = item.note.title.ifBlank { context.getString(R.string.untitled) }
                preview.text = HtmlConverter.plainText(item.note.html).replace('\n', ' ').take(80)
                type.text = if (item.isTemplate) context.getString(R.string.tab_templates)
                else context.getString(R.string.tab_snippets)
                date.text = dateFormat.format(Date(item.deletedAt))
                itemView.setOnClickListener { onRestore(position) }
                itemView.setOnLongClickListener {
                    onDeleteForever(position)
                    true
                }
            }
        }
    }
}
