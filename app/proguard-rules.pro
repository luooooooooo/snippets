# Keep note data class (used in JSON serialization via org.json reflection-free,
# but keep fields safe for potential future Gson/Moshi migration).
-keep class com.kvelzer.snippets.Note { *; }

# Keep JavascriptInterface methods called from the WebView editor.
-keepclassmembers class com.kvelzer.snippets.EditorActivity$Bridge {
    @android.webkit.JavascriptInterface *;
}

# CommonMark uses some reflection; keep its public API.
-keep class org.commonmark.** { *; }
-dontwarn org.commonmark.**

# WorkManager
-keep class * extends androidx.work.ListenableWorker { *; }
-dontwarn androidx.work.**

# SlidingPaneLayout
-dontwarn androidx.slidingpanelayout.**
