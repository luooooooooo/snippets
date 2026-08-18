package com.kvelzer.snippets

import android.app.Application
import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

class SnippetsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        AppCompatDelegate.setDefaultNightMode(getThemeMode(this))
    }

    companion object {
        private const val PREFS = "settings"
        private const val KEY_THEME = "theme_mode"
        private const val KEY_COLOR = "color_theme_index"

        // 配色方案对应的主题样式，顺序需与 R.array.palette_names 一致：
        // 蓝 / 绿 / 紫 / 青 / 橙 / 粉（索引 0 = 蓝，即默认）。
        private val COLOR_THEME_RESOURCES = intArrayOf(
            R.style.Theme_Snippets,
            R.style.Theme_Snippets_Green,
            R.style.Theme_Snippets_Purple,
            R.style.Theme_Snippets_Teal,
            R.style.Theme_Snippets_Orange,
            R.style.Theme_Snippets_Pink,
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

        /** 在 Activity.onCreate 中、setContentView 之前调用，按已保存的配色切换主题。 */
        fun applyColorTheme(context: Context) {
            val index = getColorTheme(context).coerceIn(0, COLOR_THEME_RESOURCES.lastIndex)
            context.setTheme(COLOR_THEME_RESOURCES[index])
        }
    }
}
