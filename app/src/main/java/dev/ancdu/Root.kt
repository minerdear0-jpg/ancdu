package dev.ancdu

import android.content.Context
import java.io.File

object Root {
    fun helper(ctx: Context): String = ctx.applicationInfo.nativeLibraryDir + "/libancdu_scan.so"

    /** Только наличие su; сам доступ запрашивает Magisk при первом root-скане. */
    fun suExists(): Boolean =
        (System.getenv("PATH") ?: "/system/bin:/system/xbin")
            .split(':').any { File(it, "su").let { f -> f.exists() && f.canExecute() } }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("root", Context.MODE_PRIVATE)
    fun memfdAllowed(ctx: Context): Boolean = prefs(ctx).getBoolean("memfd", true)
    fun rememberMemfd(ctx: Context, worked: Boolean) {
        prefs(ctx).edit().putBoolean("memfd", worked).apply()
    }
}
