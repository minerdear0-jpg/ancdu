package dev.ancdu

import android.content.Context
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class Kind { SCAN, ROOT, INDEX, CACHE }

/** Текущее дерево процесса: переживает пересоздание Activity. */
object Holder {
    @Volatile var h = 0L; private set
    @Volatile var kind = Kind.SCAN; private set
    @Volatile var root = ""; private set
    @Volatile var label = ""; private set
    @Volatile var viaRoot = false; private set

    /** Единственный поток для блокирующих и мутирующих операций над сессиями:
     *  delete, saveCache, free. FIFO гарантирует, что free не гоняется с ними. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ancdu-io").apply { isDaemon = true }
    }

    /** Поля меняются сразу; прежняя сессия освобождается на [io] (free может ждать Magisk). */
    @Synchronized
    fun set(handle: Long, kind: Kind, root: String, label: String, viaRoot: Boolean) {
        val old = h
        h = handle; this.kind = kind; this.root = root; this.label = label; this.viaRoot = viaRoot
        if (old != 0L && old != handle) io.execute { Native.free(old) }
    }

    fun progress(): LongArray = LongArray(6).also { if (h != 0L) Native.progress(h, it) }

    fun cacheFile(ctx: Context, root: String, viaRoot: Boolean): File =
        File(ctx.filesDir, "last-" + (if (viaRoot) "su" else "app") + root.replace('/', '_') + ".ancdu")
}
