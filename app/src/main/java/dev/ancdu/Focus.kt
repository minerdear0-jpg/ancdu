package dev.ancdu

import java.io.ByteArrayOutputStream

/** Extra браузера: открыть с этим файлом в фокусе (его папка, строка видна, карточка открыта). */
const val EXTRA_FOCUS = "focus"
/** Браузер открывается сразу в сортировке Δ (строка «что выросло» главного экрана). */
const val EXTRA_DELTA = "delta"

/**
 * Путь узла для [EXTRA_FOCUS]: байты имён от корня дерева через \u0000 (имя не содержит ни \u0000,
 * ни «/»). Байты, не строки: невалидный UTF-8 декодируется неоднозначно. Чистый Kotlin.
 */
object Focus {
    /** Предел extra: длиннее путь в дереве не бывает (arena_path, 64 КиБ). */
    const val MAX_BYTES = 65536

    fun encode(names: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        for ((i, n) in names.withIndex()) { if (i > 0) write(0); write(n) }
    }.toByteArray()

    /** Имена от корня или null: нет extra, пусто, длиннее [MAX_BYTES], пустое имя, «.», «..» или «/» в имени. */
    fun parse(b: ByteArray?): List<ByteArray>? {
        if (b == null || b.isEmpty() || b.size > MAX_BYTES) return null
        val out = ArrayList<ByteArray>()
        var start = 0
        for (i in 0..b.size) {
            if (i < b.size && b[i] != 0.toByte()) continue
            val n = b.copyOfRange(start, i)
            if (n.isEmpty() || n.contentEquals(DOT) || n.contentEquals(DOTDOT) || n.contains('/'.code.toByte())) return null
            out += n
            start = i + 1
        }
        return out
    }

    private val DOT = byteArrayOf('.'.code.toByte())
    private val DOTDOT = byteArrayOf('.'.code.toByte(), '.'.code.toByte())
}
