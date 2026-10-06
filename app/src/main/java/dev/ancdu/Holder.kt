package dev.ancdu

import android.content.Context
import android.os.Handler
import android.os.Looper
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

    private val main = Handler(Looper.getMainLooper())
    private val deleteListeners = ArrayList<(Int) -> Unit>()

    /** Идёт удаление (на любом дескрипторе). Только главный поток. Пока true — никаких чтений дерева. */
    var deleting = false; private set

    /** Только главный поток. Слушатели — живые экраны; получают код завершения удаления. */
    fun addDeleteListener(l: (Int) -> Unit) { deleteListeners += l }
    fun removeDeleteListener(l: (Int) -> Unit) { deleteListeners -= l }

    /**
     * Только главный поток. Удаляет узел [node] сессии [handle] на [io]. По завершении на главном
     * потоке снимает [deleting], уведомляет слушателей, затем вызывает [done].
     */
    fun delete(handle: Long, node: Int, helper: String?, done: (Int) -> Unit = {}) {
        check(Looper.myLooper() == Looper.getMainLooper()) { "Holder.delete не с главного потока" }
        check(!deleting) { "удаление уже идёт" }
        deleting = true
        io.execute {
            val r = Native.delete(handle, node, helper)
            main.post {
                deleting = false
                for (l in deleteListeners.toList()) l(r)
                done(r)
            }
        }
    }

    fun progress(): LongArray = LongArray(6).also { if (h != 0L) Native.progress(h, it) }

    fun cacheFile(ctx: Context, root: String, viaRoot: Boolean): File =
        File(ctx.filesDir, "last-" + (if (viaRoot) "su" else "app") + root.replace('/', '_') + ".ancdu")
}
