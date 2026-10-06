package dev.ancdu

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Строка таблицы files MediaStore: _id и _data (абсолютный путь). */
class MediaRow(val id: Long, val data: String?)

/** Доступ к строкам MediaStore. В приложении — ContentResolver (ResolverRows), в JVM-тестах — подделка. */
interface MediaRows {
    /** До [limit] строк по [where]/[args], по возрастанию _id. */
    fun page(where: String, args: Array<String>, limit: Int): List<MediaRow>
    /** Удаляет строки (и их файлы, если они есть) с этими _id; возвращает число удалённых строк. */
    fun delete(ids: LongArray): Int
}

/**
 * Массовое удаление строк MediaStore внутри узла дерева — только оптимизация: файлы и строки
 * уходят пачками через MediaProvider, мимо пофайлового FUSE. Источник истины для дерева —
 * Native.delete, который всегда вызывается после (кроме «Стоп» во время этого шага).
 *
 * Безопасность: строка удаляется, только если её _data — ровно путь узла или строго внутри
 * него ([inside], точное сравнение строк). Выборка LIKE лишь сужает запрос: в SQLite LIKE
 * регистронезависим для ASCII, поэтому каждая строка ещё раз проверяется в Kotlin.
 * Чистый Kotlin без Android API (JVM-тесты).
 */
object MediaBulk {
    const val CHUNK = 500

    /** Экранирование для LIKE … ESCAPE '\': «\», «%» и «_» получают «\» впереди. */
    fun escape(s: String): String = buildString(s.length + 8) {
        for (c in s) {
            if (c == '\\' || c == '%' || c == '_') append('\\')
            append(c)
        }
    }

    /** Шаблон LIKE для путей строго внутри [dir]: экранированный [dir] + «/%». */
    fun likePrefix(dir: String): String = escape(dir) + "/%"

    class Selection(val where: String, val args: Array<String>)

    /** Каталог — всё строго внутри и сама его строка; файл — только его строка. */
    fun selection(path: String, dir: Boolean): Selection =
        if (dir) Selection("(_data LIKE ? ESCAPE '\\' OR _data = ?)", arrayOf(likePrefix(path), path))
        else Selection("_data = ?", arrayOf(path))

    /** Страница после [afterId] (keyset: удалённые строки не сдвигают следующие страницы). */
    fun pageSelection(sel: Selection, afterId: Long): Selection =
        Selection("${sel.where} AND _id > ?", sel.args + afterId.toString())

    /** Строка [data] принадлежит узлу [path]: ровно он или (у каталога) строго внутри. */
    fun inside(data: String?, path: String, dir: Boolean): Boolean =
        data != null && (data == path || dir && data.length > path.length + 1 &&
            data.startsWith(path) && data[path.length] == '/')

    /** Путь узла как строка, если байты — корректный UTF-8 (иначе в _data его не найти точно). */
    fun exactPath(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
            .takeIf { it.isNotEmpty() && '\u0000' !in it }
    } catch (e: CharacterCodingException) {
        null
    }

    /** Путь общего хранилища /storage/emulated/<n>/X, точно представимый в _data, иначе null. */
    fun cleanable(pathBytes: ByteArray): String? =
        exactPath(pathBytes)?.takeIf { DeletePolicy.mediaPath(it) != null }

    /**
     * Политика A6: массовый шаг — только без root-сессии, без быстрого пути и для пути
     * общего хранилища ([cleanable]). Возвращает путь для [run] или null — шаг пропускается.
     */
    fun target(pathBytes: ByteArray, viaRoot: Boolean, fast: Boolean): String? =
        if (viaRoot || fast) null else cleanable(pathBytes)

    /** Итог [run]: [deleted] строк удалено, [stopped] — прерван «Стопом», [error] — сбой (шаг прерван). */
    class Outcome(val deleted: Long, val stopped: Boolean, val error: Throwable?)

    /**
     * Удаляет строки узла [path] пачками по [chunk]: страница _id по возрастанию → проверка
     * [inside] → delete по _id. [stopped] проверяется перед каждой страницей; [onDeleted]
     * получает число удалённых в каждой пачке. Собственная строка каталога удаляется последней
     * (не раньше детей) и не удаляется после «Стопа». Любое исключение ловится: шаг прерывается,
     * удалённое остаётся удалённым — дальше всё сделает Native.delete.
     */
    fun run(rows: MediaRows, path: String, dir: Boolean, chunk: Int = CHUNK,
            stopped: () -> Boolean, onDeleted: (Long) -> Unit): Outcome {
        var deleted = 0L
        try {
            require(chunk > 0) { "chunk $chunk" }
            require(path.startsWith("/") && path.length > 1 && !path.endsWith("/")) { "путь «$path»" }
            val sel = selection(path, dir)
            var after = Long.MIN_VALUE
            var self: Long? = null
            while (true) {
                if (stopped()) return Outcome(deleted, true, null)
                val q = pageSelection(sel, after)
                val page = rows.page(q.where, q.args, chunk)
                if (page.isEmpty()) break
                val last = page.maxOf { it.id }
                check(last > after) { "страница не продвинулась: $last ≤ $after" }
                after = last
                val ids = ArrayList<Long>(page.size)
                for (r in page) {
                    if (!inside(r.data, path, dir)) continue
                    if (dir && r.data == path) self = r.id else ids += r.id
                }
                if (ids.isNotEmpty()) {
                    val n = maxOf(rows.delete(ids.toLongArray()), 0).toLong()
                    deleted += n
                    onDeleted(n)
                }
                if (page.size < chunk) break
            }
            if (self != null) {
                if (stopped()) return Outcome(deleted, true, null)
                val n = maxOf(rows.delete(longArrayOf(self)), 0).toLong()
                deleted += n
                onDeleted(n)
            }
            return Outcome(deleted, false, null)
        } catch (e: Exception) {
            return Outcome(deleted, false, e)
        }
    }
}
