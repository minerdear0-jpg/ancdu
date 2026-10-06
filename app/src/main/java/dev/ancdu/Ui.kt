package dev.ancdu

import android.content.Context

/** Палитра макета. */
object C {
    const val BG = 0xFF101214.toInt()
    const val SURFACE = 0xFF1A1D21.toInt()
    const val LINE = 0xFF23272C.toInt()
    const val TEXT = 0xFFE8E6E1.toInt()
    const val MUTED = 0xFF9AA0A6.toInt()
    const val ACCENT = 0xFFF2A93B.toInt()
    const val FILE = 0xFF5B9BD5.toInt()
    const val CACHE = 0xFF8A6A3A.toInt()
    const val WARN = 0xFFFFB74D.toInt()
    const val DANGER = 0xFFC9372C.toInt()
    const val FREE_TXT = 0xFFFF8A80.toInt()
    const val OK_TXT = 0xFF8FD18F.toInt()
    const val OK_BG = 0xFF1F2A1F.toInt()
    const val AUDIO = 0xFF8FD18F.toInt()
    const val APPS = 0xFFB48EAD.toInt()
    const val OTHER = 0xFF9AA0A6.toInt()
    const val SYS = 0xFF5A5F66.toInt()
    const val CHIP = 0xFF2A2E33.toInt()
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
