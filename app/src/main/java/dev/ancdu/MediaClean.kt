package dev.ancdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Фоновая очистка устаревших строк MediaStore после быстрого удаления через root (/data/media
 * мимо FUSE: MediaProvider удаления не видел). Отдельный однопоточный исполнитель, НЕ Holder.io:
 * сессий не трогает и не задерживает saveCache и удаления. Очередь FIFO; работает и в фоне,
 * пока жив процесс; после смерти процесса не повторяется (принято).
 *
 * Удаление строки через MediaProvider удаляет и файл, если он есть. Поэтому очистка идёт только
 * пока путь отсутствует: проверка перед началом и перед каждой пачкой — если на этом месте снова
 * что-то появилось, очистка прекращается (новые файлы пользователя не трогаются).
 *
 * [running]/[cleaned] и слушатели — только главный поток.
 */
object MediaClean {
    private val exec: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ancdu-mediaclean").apply { isDaemon = true }
    }
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    private var queued = 0

    /** В очереди или идёт хотя бы одна очистка. Только главный поток. */
    val running: Boolean get() = queued > 0
    /** Строк удалено идущей (или последней) очисткой. Только главный поток. */
    var cleaned = 0L; private set

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() { for (l in listeners.toList()) l() }

    private fun gone(path: String): Boolean = !Files.exists(Paths.get(path), LinkOption.NOFOLLOW_LINKS)

    /**
     * Любой поток. Ставит в очередь очистку строк [path] (путь /storage/emulated/<n>/…, точный —
     * MediaBulk.cleanable) — каталога [dir] целиком или одного файла.
     */
    fun enqueue(ctx: Context, path: String, dir: Boolean) {
        val cr = ctx.applicationContext.contentResolver
        main.post { queued++; changed() }
        exec.execute {
            main.post { cleaned = 0L; changed() }
            try {
                if (!gone(path)) {
                    Log.w("ancdu", "mediaclean skipped: path exists again")
                } else {
                    val out = MediaBulk.run(ResolverRows(cr), path, dir, stopped = { !gone(path) }) { n ->
                        main.post { cleaned += n; changed() }
                    }
                    out.error?.let { Log.w("ancdu", "mediaclean failed after ${out.deleted} rows", it) }
                    Log.i("ancdu", "mediaclean rows=${out.deleted} stopped=${out.stopped}")
                }
            } finally {
                main.post { queued--; changed() }
            }
        }
    }
}
