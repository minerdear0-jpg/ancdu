package dev.ancdu

import android.content.ContentResolver
import android.content.Context
import android.media.MediaScannerConnection
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
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
 * Безопасность: строки удаляются только режимом «только строки» (ResolverRows keepFiles,
 * deletedata=false) — MediaProvider не вызывает unlink, файлы на диске не трогаются ни при
 * каком состоянии пути. Параметр скрытый, поэтому раз за процесс (на потоке MediaClean) его
 * проверяет канарейка ([CleanCanary]); не прошла — вместо удаления строк scanFile (не удаляет
 * файлов никогда). Проверка «путь отсутствует» — только оптимизация.
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
    /** Итог канарейки за процесс; null — ещё не проверяли. Только поток MediaClean. */
    private var rowsOnly: Boolean? = null

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
        val app = ctx.applicationContext
        val cr = app.contentResolver
        main.post { queued++; changed() }
        exec.execute {
            main.post { cleaned = 0L; changed() }
            try {
                val ok = rowsOnly ?: CleanCanary.rowsOnlyWorks(Canary(app, cr)).also {
                    rowsOnly = it
                    Log.i("ancdu", "mediaclean canary rowsOnly=$it")
                }
                when (CleanCanary.mode(ok, pathExists = !gone(path))) {
                    CleanCanary.Mode.SCAN -> scan(app, path)
                    CleanCanary.Mode.ROWS_ONLY -> {
                        val out = MediaBulk.run(ResolverRows(cr, keepFiles = true), path, dir,
                            stopped = { !gone(path) }) { n -> main.post { cleaned += n; changed() } }
                        out.error?.let { Log.w("ancdu", "mediaclean failed after ${out.deleted} rows", it) }
                        Log.i("ancdu", "mediaclean rows=${out.deleted} stopped=${out.stopped}")
                        if (CleanCanary.needsScanAfter(out)) scan(app, path)
                    }
                }
            } catch (e: Exception) {
                Log.w("ancdu", "mediaclean failed", e)
            } finally {
                main.post { queued--; changed() }
            }
        }
    }

    private fun scan(ctx: Context, path: String) {
        Log.i("ancdu", "mediaclean via scanFile")
        MediaScannerConnection.scanFile(ctx, arrayOf(path), null, null)
    }

    /**
     * Канарейка в /storage/emulated/<n>/Android/media/dev.ancdu/ (getExternalMediaDirs): каталог
     * принадлежит приложению (уходит с ним при удалении), не требует прав и, в отличие от
     * Android/data и Android/obb, индексируется MediaStore — файл, созданный через FUSE,
     * получает строку. Каждая проверка — в новом mkdtemp-каталоге; убирается только он.
     */
    internal class Canary(private val ctx: Context, private val cr: ContentResolver) : CanaryEnv {
        private val rows = ResolverRows(cr)
        private var base: File? = null

        override fun makeDir(): String? {
            @Suppress("DEPRECATION")
            val b = ctx.externalMediaDirs.firstOrNull { it != null && it.path.startsWith("/storage/emulated/") }
                ?: return null
            if (!b.isDirectory && !b.mkdirs()) return null
            base = b
            return Files.createTempDirectory(b.toPath(), "canary-").toFile().absolutePath
        }

        override fun writeFile(dir: String): String =
            File(dir, FILE).apply { writeText("ancdu") }.absolutePath

        private fun find(path: String): Long? {
            val q = MediaBulk.pageSelection(MediaBulk.selection(path, dir = false), Long.MIN_VALUE)
            return rows.page(q.where, q.args, 10).firstOrNull { MediaBulk.inside(it.data, path, false) }?.id
        }

        override fun awaitRow(path: String): Long? {
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (true) {
                find(path)?.let { return it }
                if (SystemClock.elapsedRealtime() > deadline) return null
                Thread.sleep(200)
            }
        }

        override fun deleteRowOnly(id: Long, path: String): Int {
            val sel = MediaBulk.selection(path, dir = false)
            return ResolverRows(cr, keepFiles = true).delete(longArrayOf(id), sel.where, sel.args)
        }

        override fun fileExists(path: String) = Files.exists(Paths.get(path), LinkOption.NOFOLLOW_LINKS)
        override fun rowExists(path: String) = find(path) != null

        override fun cleanup(dir: String) {
            val b = base ?: return
            val d = File(dir)
            // Только свой mkdtemp-каталог внутри Android/media/<пакет>.
            if (d.parentFile?.absolutePath != b.absolutePath || !d.name.startsWith("canary-")) return
            File(d, FILE).delete()
            d.delete()
        }

        private companion object { const val FILE = "canary.txt" }
    }
}
