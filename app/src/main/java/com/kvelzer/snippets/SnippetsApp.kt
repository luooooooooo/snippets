package com.kvelzer.snippets

import android.app.Application
import android.content.Context
import android.os.Build
import androidx.appcompat.app.AppCompatDelegate

class SnippetsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(getThemeMode(this))
        // Re-schedule auto-backup if it was enabled before a process restart.
        if (AutoBackupWorker.isEnabled(this)) {
            AutoBackupWorker.schedule(this, enabled = true)
        }
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_COLOR = "color_theme_index"
        private const val KEY_TAG_SORT = "tag_sort_mode"
        private const val KEY_LIST_SORT = "list_sort_mode"

        // 配色方案对应的主题样式，顺序需与 R.array.palette_names 一致：
        // 蓝 / 绿 / 紫 / 青 / 橙 / 粉 / 动态（索引 6，仅 Android 12+ 生效）。
        private val COLOR_THEME_RESOURCES = intArrayOf(
            R.style.Theme_Snippets,
            R.style.Theme_Snippets_Green,
            R.style.Theme_Snippets_Purple,
            R.style.Theme_Snippets_Teal,
            R.style.Theme_Snippets_Orange,
            R.style.Theme_Snippets_Pink,
            R.style.Theme_Snippets_Dynamic,
        )

        fun getThemeMode(context: Context): Int =
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .getInt(KEY_THEME, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

        /** Persists and applies immediately (activities recreate on their own). */
        fun setThemeMode(context: Context, mode: Int) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE)
                .edit().putInt(KEY_THEME, mode).apply()
            AppCompatDelegate.setDefaultNightMode(mode)
        }

        fun getColorTheme(context: Context): Int =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_COLOR, 0)

        fun setColorTheme(context: Context, index: Int) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_COLOR, index).apply()
        }

        /** 标签栏排序方式（0=名称A→Z,1=名称Z→A,2=使用最多,3=最近添加）。默认 0。 */
        fun getTagSort(context: Context): Int =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_TAG_SORT, 0)

        fun setTagSort(context: Context, mode: Int) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_TAG_SORT, mode).apply()
        }

        /** 列表排序方式（0=手动,1=最近使用,2=最常使用,3=名称A→Z,4=名称Z→A）。默认 0。 */
        fun getListSort(context: Context): Int =
            context.getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_LIST_SORT, 0)

        fun setListSort(context: Context, mode: Int) {
            context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_LIST_SORT, mode).apply()
        }

        /** 在 Activity.onCreate 中、setContentView 之前调用，按已保存的配色切换主题。 */
        fun applyColorTheme(context: Context) {
            var index = getColorTheme(context).coerceIn(0, COLOR_THEME_RESOURCES.lastIndex)
            // 动态颜色仅 Android 12+ 支持；低版本回退到默认蓝色。
            if (index == 6 && Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                index = 0
            }
            context.setTheme(COLOR_THEME_RESOURCES[index])
        }
    }
}
