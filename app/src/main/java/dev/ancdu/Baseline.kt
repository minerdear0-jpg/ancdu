package dev.ancdu

import android.content.Context
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Чистый Kotlin: точка отсчёта «что выросло» — два буфера на ключ дерева (тот же, что у кэша):
 * A — точка отсчёта, B — кандидат. При каждом сохранении скана этого ключа: нет A — A := скан;
 * иначе нет B — B := скан; иначе B старше недели — A := B, B := скан; иначе ничего. После первой
 * недели точке отсчёта всегда 7–14 дней.
 */
object BaselineRules {
    enum class Step { SET_A, SET_B, SHIFT, NONE }

    const val WEEK_MS = 7 * 86_400_000L

    /** [ageB] — возраст B (мс; отрицательный — часы ушли назад: ничего не трогать). */
    fun onSave(hasA: Boolean, hasB: Boolean, ageB: Long): Step = when {
        !hasA -> Step.SET_A
        !hasB -> Step.SET_B
        ageB >= WEEK_MS -> Step.SHIFT
        else -> Step.NONE
    }
}

/**
 * Файлы A и B ключа [cacheName] в каталоге [dir]: «<кэш>.base-a» и «<кэш>.base-b» — копии кэш-файла
 * (тот же формат и версия). Не пересериализуются: жёсткая ссылка на только что записанный кэш,
 * иначе копия; на место — rename (атомарно). Кэш пишется новым inode (tmp + rename), так что
 * ссылка A/B его следующим сохранением не меняется. Трогает только свои файлы (и их .tmp/.link).
 * Вызывать на Holder.io (FIFO с saveCache, delete, free и расчётом Δ).
 */
class BaselineFiles(dir: File, cacheName: String) {
    val a = File(dir, "$cacheName.base-a")
    val b = File(dir, "$cacheName.base-b")

    /** Время точки отсчёта (mtime A — время её скана); 0 — её нет. */
    fun time(): Long = if (a.isFile) a.lastModified() else 0L

    /** Кэш [cache] этого ключа только что сохранён ([now] — сейчас, мс): ротация по [BaselineRules]. */
    fun onSaved(cache: File, now: Long): BaselineRules.Step {
        val hasB = b.isFile
        val step = BaselineRules.onSave(a.isFile, hasB, if (hasB) now - b.lastModified() else 0L)
        when (step) {
            BaselineRules.Step.SET_A -> place(cache, a)
            BaselineRules.Step.SET_B -> place(cache, b)
            BaselineRules.Step.SHIFT -> if (b.renameTo(a)) place(cache, b)
            BaselineRules.Step.NONE -> {}
        }
        return step
    }

    /**
     * «Отметить сейчас»: A := дерево, которое [write] пишет по пути A (Native.saveCache: tmp + rename,
     * при ошибке прежняя A цела), B := нет. [time] — время дерева (mtime A); 0 — неизвестно, сейчас.
     * Код [write] (0 — готово).
     */
    fun markFrom(time: Long, write: (String) -> Int): Int {
        // Стоячий «A.tmp» (прерванное сохранение) мог бы оказаться ссылкой на живой кэш — убрать до записи.
        tmp(a).delete()
        link(a).delete()
        val r = write(a.path)
        if (r != 0) return r
        if (time > 0) a.setLastModified(time)
        b.delete()
        return 0
    }

    /**
     * A негодна (другая версия формата, повреждена): удалить только её; годный кандидат B становится
     * точкой отсчёта (rename B → A). true — B повышен (его стоит проверить тем же расчётом).
     */
    fun dropA(): Boolean {
        a.delete()
        return b.isFile && b.renameTo(a)
    }

    /** Забыть точку отсчёта (кэш забыт, другая версия формата, файл негоден): A, B и их временные файлы. */
    fun forget() {
        for (f in listOf(a, b, tmp(a), tmp(b), link(a), link(b))) f.delete()
    }

    /** Временный файл Native.saveCache (arena_save_file) для [f]. */
    private fun tmp(f: File) = File(f.path + ".tmp")

    /** Временный файл ссылки/копии [place] — свой суффикс: сохранение (O_TRUNC по «.tmp») его не коснётся. */
    private fun link(f: File) = File(f.path + ".link")

    /** [src] → [dst]: ссылка или копия во временный файл рядом, затем rename. */
    private fun place(src: File, dst: File): Boolean {
        val t = link(dst)
        t.delete()
        val ok = runCatching { Files.createLink(t.toPath(), src.toPath()) }.isSuccess || runCatching {
            Files.copy(src.toPath(), t.toPath(), StandardCopyOption.COPY_ATTRIBUTES)
            RandomAccessFile(t, "rw").use { it.fd.sync() }
        }.isSuccess
        if (ok && t.renameTo(dst)) return true
        t.delete()
        return false
    }
}

/** Где лежат точки отсчёта: рядом с кэшами (filesDir); тесты подменяют каталог песочницей. */
object Baseline {
    /** Для тестов: каталог точек отсчёта вместо filesDir (null — filesDir). */
    @Volatile var dirOverride: File? = null

    /**
     * Кэш не открылся с кодом [err]: точка отсчёта забывается вместе с ним только при другой версии
     * формата (-ENOEXEC) или негодном файле (-EINVAL), не при временных сбоях (-ENOMEM, -EMFILE…).
     */
    fun dropOnCacheError(err: Int): Boolean = err == -ENOEXEC || err == -EINVAL

    private const val ENOEXEC = 8
    private const val EINVAL = 22

    fun files(ctx: Context, root: String, viaRoot: Boolean): BaselineFiles =
        BaselineFiles(dirOverride ?: ctx.filesDir, Holder.cacheFile(ctx, root, viaRoot).name)
}
