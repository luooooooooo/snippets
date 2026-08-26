package com.kvelzer.snippets

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Periodic automatic backup: writes a JSON snapshot (same format as manual
 * export) into the app's internal backups/ directory, keeping only the most
 * recent [KEEP] files. Runs once per day by default. Fully offline — no
 * permissions needed because it stays in internal storage.
 */
class AutoBackupWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val ctx = applicationContext
            val dir = File(ctx.filesDir, "backups").apply { mkdirs() }
            val timestamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val file = File(dir, "auto-$timestamp.json")

            val payload = JSONObject()
                .put("version", 2)
                .put("notes", BackupData.toArray(NoteStore.all(ctx)))
                .put("templates", BackupData.toArray(TemplateStore.all(ctx)))
                .toString()

            file.writeText(payload, Charsets.UTF_8)

            // Keep only the most recent KEEP backups.
            val all = dir.listFiles { f -> f.name.startsWith("auto-") && f.name.endsWith(".json") }
                ?.sortedBy { it.name } ?: emptyList()
            if (all.size > KEEP) {
                all.take(all.size - KEEP).forEach { it.delete() }
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val KEEP = 7
        private const val WORK_NAME = "snippets-auto-backup"

        fun schedule(context: Context, enabled: Boolean) {
            val wm = WorkManager.getInstance(context)
            if (enabled) {
                val request = PeriodicWorkRequestBuilder<AutoBackupWorker>(1, TimeUnit.DAYS)
                    .build()
                wm.enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
            } else {
                wm.cancelUniqueWork(WORK_NAME)
            }
        }

        fun isEnabled(context: Context): Boolean =
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .getBoolean("auto_backup", false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences("settings", Context.MODE_PRIVATE)
                .edit().putBoolean("auto_backup", enabled).apply()
            schedule(context, enabled)
        }

        fun listBackups(context: Context): List<File> {
            val dir = File(context.filesDir, "backups")
            return dir.listFiles { f -> f.name.startsWith("auto-") && f.name.endsWith(".json") }
                ?.sortedByDescending { it.name } ?: emptyList()
        }
    }
}

/** Tiny helper to avoid duplicating Backup's private toArray. */
private object BackupData {
    fun toArray(notes: List<Note>): org.json.JSONArray {
        val array = org.json.JSONArray()
        for (n in notes) {
            val tagArr = org.json.JSONArray()
            for (t in n.tags) tagArr.put(t)
            array.put(
                org.json.JSONObject()
                    .put("title", n.title)
                    .put("html", n.html)
                    .put("updatedAt", n.updatedAt)
                    .put("tags", tagArr)
                    .put("useCount", n.useCount)
                    .put("lastUsedAt", n.lastUsedAt)
            )
        }
        return array
    }
}
