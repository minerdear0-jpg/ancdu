package dev.ancdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Чистый Kotlin: байты имён в поле TSV. Валидный UTF-8 — как есть; «\», TAB, LF, CR — «\\», «\t»,
 * «\n», «\r»; остальные управляющие и каждый байт невалидного UTF-8 — «\xNN». Обратимо.
 */
object LogCodec {
    private const val HEX = "0123456789ABCDEF"

    fun escape(b: ByteArray): String {
        val sb = StringBuilder(b.size + 8)
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xFF
            val n = if (c >= 0x80) seqLen(b, i) else 1
            when {
                c >= 0x80 && n == 0 -> { hex(sb, c); i++ }
                c >= 0x80 -> { sb.append(String(b, i, n, Charsets.UTF_8)); i += n }
                c == '\\'.code -> { sb.append("\\\\"); i++ }
                c == '\t'.code -> { sb.append("\\t"); i++ }
                c == '\n'.code -> { sb.append("\\n"); i++ }
                c == '\r'.code -> { sb.append("\\r"); i++ }
                c < 0x20 || c == 0x7F -> { hex(sb, c); i++ }
                else -> { sb.append(c.toChar()); i++ }
            }
        }
        return sb.toString()
    }

    private fun hex(sb: StringBuilder, c: Int) {
        sb.append("\\x").append(HEX[c shr 4]).append(HEX[c and 15])
    }

    /** Длина валидной многобайтной последовательности UTF-8 с [i] или 0 (без overlong, суррогатов, > U+10FFFF). */
    private fun seqLen(b: ByteArray, i: Int): Int {
        val c = b[i].toInt() and 0xFF
        val (n, min) = when {
            c in 0xC2..0xDF -> 2 to 0x80
            c in 0xE0..0xEF -> 3 to 0x800
            c in 0xF0..0xF4 -> 4 to 0x10000
            else -> return 0
        }
        if (i + n > b.size) return 0
        var cp = c and (0xFF shr (n + 1))
        for (k in 1 until n) {
            val x = b[i + k].toInt() and 0xFF
            if (x and 0xC0 != 0x80) return 0
            cp = (cp shl 6) or (x and 0x3F)
        }
        if (cp < min || cp > 0x10FFFF || cp in 0xD800..0xDFFF) return 0
        return n
    }

    /** Обратное [escape]; негодная последовательность «\» — IllegalArgumentException. */
    fun unescape(s: String): ByteArray {
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch != '\\') {
                val end = s.indexOf('\\', i).let { if (it < 0) s.length else it }
                out.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
                continue
            }
            require(i + 1 < s.length) { "dangling escape" }
            when (s[i + 1]) {
                '\\' -> { out.write('\\'.code); i += 2 }
                't' -> { out.write('\t'.code); i += 2 }
                'n' -> { out.write('\n'.code); i += 2 }
                'r' -> { out.write('\r'.code); i += 2 }
                'x' -> {
                    require(i + 4 <= s.length) { "short \\x" }
                    val hi = HEX.indexOf(s[i + 2]); val lo = HEX.indexOf(s[i + 3])
                    require(hi >= 0 && lo >= 0) { "bad \\x" }
                    out.write(hi * 16 + lo); i += 4
                }
                else -> throw IllegalArgumentException("bad escape")
            }
        }
        return out.toByteArray()
    }

    /** Путь от корня: имена через «/» (имя не содержит «/»). */
    fun rel(names: List<ByteArray>): String = names.joinToString("/") { escape(it) }

    fun names(rel: String): List<ByteArray> = if (rel.isEmpty()) emptyList() else rel.split('/').map { unescape(it) }
}

/** Запись журнала удалений: одна строка TSV, первое поле — вид. */
sealed class LogRec {
    abstract val id: Long
    abstract fun format(): String

    /**
     * Начало ОДНОГО действия удаления (пишется на io ДО удаления): [root]/[su] — дерево, [names] —
     * путь от корня (байты): объекта или, у группы, её папки; [items]/[disk] — на момент
     * подтверждения (у группы — суммы), [viaRoot] — через su, [fast] — /data/media. [group] — явно:
     * группа (несколько объектов одной папки; группа из одного пишется как одиночное удаление этого
     * объекта). [count] — объектов; [itemNames] — у группы до [LogActions.NAMES] имён, крупнейшие
     * первыми: для истории и будущего подробного вида (сейчас лист их не показывает).
     * Поле 11: «g<N>» у группы, «gm<N>» у группы из разных папок ([mixed]: [names] — их общая папка,
     * [itemNames] — пути от неё через «/»), «<N>» у одиночного (запись раунда 3 без «g» — группа, если N > 1).
     */
    class Start(override val id: Long, val time: Long, val root: String, val su: Boolean, val names: List<ByteArray>,
                val dir: Boolean, val items: Long, val disk: Long, val viaRoot: Boolean, val fast: Boolean,
                val count: Int = 1, val itemNames: List<ByteArray> = emptyList(), val group: Boolean = false,
                val mixed: Boolean = false) : LogRec() {
        override fun format() = (listOf("S", id, time, b(su), LogCodec.escape(root.toByteArray(Charsets.UTF_8)), LogCodec.rel(names),
            if (dir) "d" else "f", items, disk, b(viaRoot), b(fast), (if (group) (if (mixed) "gm" else "g") else "") + count) +
            (if (group) itemNames.map { LogCodec.escape(it) } else emptyList())).joinToString("\t")
    }

    /**
     * Итог действия: [code] — Native.delete (у группы — GroupResult.code), [freed] — освобождено
     * (-1 — неизвестно; у группы — сумма удалённых целиком), [removed] — записей удалено (-1 —
     * неизвестно); [deleted]/[partial]/[failed] — объектов удалено целиком / частично / не удалено.
     */
    class End(override val id: Long, val time: Long, val code: Int, val freed: Long, val removed: Long,
              val deleted: Int = if (code == 0) 1 else 0, val partial: Int = if (code != 0 && removed > 0) 1 else 0,
              val failed: Int = if (code != 0 && removed <= 0) 1 else 0) : LogRec() {
        override fun format() = listOf("E", id, time, code, freed, removed, deleted, partial, failed).joinToString("\t")
    }

    /** Освобождено — стало известно позже (дерево обновилось после частичного удаления). */
    class Freed(override val id: Long, val bytes: Long) : LogRec() {
        override fun format() = listOf("F", id, bytes).joinToString("\t")
    }

    /** Уведомление о прерванном удалении просмотрено (тап по строке статуса). */
    class Seen(override val id: Long) : LogRec() {
        override fun format() = listOf("N", id).joinToString("\t")
    }

    companion object {
        private fun b(v: Boolean) = if (v) "1" else "0"
        private fun bool(s: String) = when (s) { "1" -> true; "0" -> false; else -> throw IllegalArgumentException(s) }

        /** Строка → запись или null (мусор, оборванная строка, неизвестный вид). */
        fun parse(line: String): LogRec? = try {
            val f = line.split('\t')
            when (f[0]) {
                "S" -> if (f.size < 11) null else Start(f[1].toLong(), f[2].toLong(), String(LogCodec.unescape(f[4]), Charsets.UTF_8),
                    bool(f[3]), LogCodec.names(f[5]), when (f[6]) { "d" -> true; "f" -> false; else -> throw IllegalArgumentException() },
                    f[7].toLong(), f[8].toLong(), bool(f[9]), bool(f[10]),
                    if (f.size > 11) f[11].removePrefix("g").removePrefix("m").toInt().also { require(it >= 1) } else 1,
                    f.drop(12).map { LogCodec.unescape(it) },
                    group = f.size > 11 && (f[11].startsWith("g") || f[11].toInt() > 1),
                    mixed = f.size > 11 && f[11].startsWith("gm"))
                "E" -> when {
                    f.size >= 9 -> End(f[1].toLong(), f[2].toLong(), f[3].toInt(), f[4].toLong(), f[5].toLong(),
                        f[6].toInt(), f[7].toInt(), f[8].toInt())
                    f.size >= 6 -> End(f[1].toLong(), f[2].toLong(), f[3].toInt(), f[4].toLong(), f[5].toLong())
                    else -> null
                }
                "F" -> if (f.size < 3) null else Freed(f[1].toLong(), f[2].toLong())
                "N" -> if (f.size < 2) null else Seen(f[1].toLong())
                else -> null
            }
        } catch (e: IllegalArgumentException) { null }
    }
}

enum class LogOutcome { DELETED, PARTIAL, INTERRUPTED }

/** Одно удаление: начало, итог (null — прервано), уточнённое освобождённое, просмотрено ли уведомление. */
class LogEntry(val start: LogRec.Start, val end: LogRec.End?, private val freedLater: Long?, val seen: Boolean) {
    val outcome: LogOutcome get() = when {
        end == null -> LogOutcome.INTERRUPTED
        end.code == 0 && end.partial == 0 && end.failed == 0 -> LogOutcome.DELETED
        else -> LogOutcome.PARTIAL
    }

    /** Освобождено, если известно. */
    val freed: Long? get() = freedLater ?: end?.freed?.takeIf { it >= 0 }

    /** Отказ — ничего не удалено (и не потеряно): в журнале не показывается. */
    val refusal: Boolean get() = end != null && end.code != 0 && end.removed == 0L && end.deleted == 0
}

/** Чистый Kotlin: записи журнала → удаления, прерванные, уведомление. */
object DeleteLogModel {
    fun entries(lines: List<String>): List<LogEntry> {
        val starts = LinkedHashMap<Long, LogRec.Start>()
        val ends = HashMap<Long, LogRec.End>()
        val freed = HashMap<Long, Long>()
        val seen = HashSet<Long>()
        for (l in lines) when (val r = LogRec.parse(l)) {
            is LogRec.Start -> starts[r.id] = r
            is LogRec.End -> ends[r.id] = r
            is LogRec.Freed -> freed[r.id] = r.bytes
            is LogRec.Seen -> seen += r.id
            null -> {}
        }
        return starts.values.map { LogEntry(it, ends[it.id], freed[it.id], it.id in seen) }
    }

    /** Для листа журнала: без отказов, новые сверху. */
    fun shown(entries: List<LogEntry>): List<LogEntry> = entries.filterNot { it.refusal }.reversed()

    /** Начатые и не законченные, уведомление о которых ещё не просмотрено. */
    fun interrupted(entries: List<LogEntry>): List<LogEntry> = entries.filter { it.end == null && !it.seen }

    /** Самое новое непросмотренное прерванное удаление — строка статуса главного экрана. */
    fun notice(entries: List<LogEntry>): InterruptedDelete? = interrupted(entries).lastOrNull()?.start?.let {
        InterruptedDelete(it.id, it.root, it.su, it.names, it.dir, it.disk, it.time, count = it.count, items = it.itemNames,
            group = it.group, mixed = it.mixed)
    }
}

/**
 * Файл журнала [file] (filesDir/deletes.tsv) и его tmp — единственные файлы, которые он трогает.
 * Дописывается за O(1): число записей читается один раз (при первой записи) и дальше ведётся в
 * памяти. Больше [CAP] — переписывается до [KEEP] последних (tmp + fsync + rename): примерно раз
 * на сто действий, не на каждое. Только Holder.io.
 */
class DeleteLogStore(val file: File) {
    private val tmp get() = File(file.path + ".tmp")
    /** Записей в файле (null — ещё не считано). */
    private var count: Int? = null
    /** Для тестов: сколько записей дописано этим объектом и сколько раз файл переписан. */
    var appends = 0
        private set
    var rewrites = 0
        private set
    /** Для тестов: неудавшихся переписываний (диск полон) и подмена «переписать не вышло». */
    var rewriteFailures = 0
        private set
    @Volatile var failRewrite = false
    /** После неудачного переписывания — не раньше этого числа записей (не на каждой записи). */
    private var retryAt = 0

    fun lines(): List<String> = if (!file.exists()) emptyList() else file.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }

    /** Записей сейчас (один раз читает файл, дальше — из памяти). */
    fun size(): Int = count ?: lines().size.also { count = it }

    fun append(rec: LogRec) {
        val line = rec.format()
        val n = size()
        appends++
        if (n + 1 > CAP && n + 1 >= retryAt) {
            try { rewrite(lines().takeLast(KEEP - 1) + line); return } catch (e: java.io.IOException) {
                // Переписать не вышло (диск полон): запись всё равно дописывается, повтор — через [BACKOFF].
                rewriteFailures++
                tmp.delete()
                retryAt = n + 1 + BACKOFF
                if (count == null) count = n
            }
        }
        // Прошлую запись могли оборвать (процесс убит посреди write): новая — с новой строки.
        val torn = file.length() > 0 && RandomAccessFile(file, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
        FileOutputStream(file, true).use { it.write(((if (torn) "\n" else "") + line + "\n").toByteArray(Charsets.UTF_8)) }
        count = n + 1
    }

    /** Очистить: пустой tmp, затем rename — журнал пуст целиком или прежний. */
    fun clear() = rewrite(emptyList())

    private fun rewrite(lines: List<String>) {
        val t = tmp
        if (failRewrite) throw java.io.IOException("test: rewrite fails")
        FileOutputStream(t).use { out ->
            out.write(lines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!t.renameTo(file)) { t.delete(); count = null; throw java.io.IOException("rename failed: ${t.name}") }
        count = lines.size
        rewrites++
        retryAt = 0
    }

    companion object {
        const val CAP = 500
        /** После переписывания остаётся столько: запас в сто записей до следующего. */
        const val KEEP = 400
        /** После неудачного переписывания следующая попытка — через столько записей. */
        const val BACKOFF = 100
    }
}

/** Одно действие удаления для журнала: объект или группа объектов одной папки ([LogRec.Start]). */
class LogAction(val root: String, val su: Boolean, val names: List<ByteArray>, val dir: Boolean, val items: Long,
                val disk: Long, val viaRoot: Boolean, val fast: Boolean, val count: Int = 1,
                val itemNames: List<ByteArray> = emptyList(), val group: Boolean = false,
                /** Группа из разных папок («гиганты»): [names] — их общая папка, [itemNames] — пути от неё. */
                val mixed: Boolean = false)

/** Объект группы для журнала: имя (байты), размер, каталог ли. */
data class LogObject(val name: ByteArray, val disk: Long, val dir: Boolean = false)

/** Чистый Kotlin: действие и итог для журнала — одна пара S/E на действие, а не на объект. */
object LogActions {
    /** Имён объектов группы в записи «начало» не больше стольких (крупнейшие первыми). */
    const val NAMES = 20

    /**
     * Группа [objects] в папке [folder]: суммы, число и до [NAMES] имён. Объект один (остальные
     * отпали после обновления) — это одиночное удаление ЕГО: путь папка + имя, его флаг каталога.
     */
    fun group(root: String, su: Boolean, folder: List<ByteArray>, objects: List<LogObject>, items: Long,
              viaRoot: Boolean, fast: Boolean, mixed: Boolean = false): LogAction {
        objects.singleOrNull()?.let { o -> return LogAction(root, su, folder + o.name, o.dir, items, o.disk, viaRoot, fast) }
        return LogAction(root, su, folder, true, items, DeletePolicy.sum(objects.map { it.disk }), viaRoot, fast,
            count = objects.size, itemNames = objects.sortedByDescending { it.disk }.take(NAMES).map { it.name }, group = true,
            mixed = mixed)
    }

    fun start(id: Long, time: Long, a: LogAction) = LogRec.Start(id, time, a.root, a.su, a.names, a.dir, a.items, a.disk,
        a.viaRoot, a.fast, a.count, a.itemNames, a.group, a.mixed)

    /**
     * Итог действия [id] из итогов объектов [results] ([total] — объектов в действии; не дошедшие
     * до итога — «не удалено»). Одиночное частичное: освобождено неизвестно (-1, уточнит [LogRec.Freed]);
     * у группы — сумма удалённых целиком.
     */
    fun end(id: Long, time: Long, code: Int, results: List<ItemResult>, total: Int): LogRec.End {
        val deleted = results.count { it.attempted && it.r == 0 }
        val partial = results.count { it.attempted && it.r != 0 && it.done > 0 }
        // Итогов меньше, чем объектов: удаление прервалось исключением — сколько удалено, неизвестно
        // (-1): строка журнала покажет отклонение, а не спрячется как отказ.
        val removed = if (results.size < total) -1L else results.sumOf { maxOf(0L, it.done) }
        // Жёсткая ссылка места не освобождает (данные живут под другим именем): 0, у группы — не в сумме.
        val freed = if (total <= 1) (if (deleted == 1) results.single().let { if (it.hardlink) 0L else it.disk } else -1L)
            else DeletePolicy.sum(results.filter { it.attempted && it.r == 0 && !it.hardlink }.map { it.disk })
        return LogRec.End(id, time, code, freed, removed, deleted, partial, maxOf(0, total - deleted - partial))
    }
}

/**
 * Журнал удалений процесса: «что и когда удалено». Запись — на Holder.io, в том же порядке задач,
 * что и само удаление; сбой ввода-вывода журнала пишется в лог и пропускается — удаление он не
 * останавливает и не меняет. [notice] и слушатель — главный поток.
 */
object DeleteLog {
    const val FILE = "deletes.tsv"
    /** filesDir/deletes.tsv — после [init]. */
    @Volatile private var base: DeleteLogStore? = null
    /**
     * Только для тестов: журнал в этом файле (песочница в cacheDir) вместо filesDir/deletes.tsv.
     * null — настоящий журнал.
     */
    @Volatile var fileOverride: File? = null

    /** Действующий журнал (null — [init] ещё не было и подмены нет). */
    private fun store(): DeleteLogStore? {
        val o = fileOverride ?: return base
        // Один объект на файл: его счётчик записей ведётся в памяти.
        return overrideStore?.takeIf { it.file == o } ?: DeleteLogStore(o).also { overrideStore = it }
    }
    @Volatile private var overrideStore: DeleteLogStore? = null
    private var inited = false
    private var seq = 0
    private val main = Handler(Looper.getMainLooper())

    /** Непросмотренное прерванное удаление (null — нет). Главный поток. */
    var notice: InterruptedDelete? = null
        private set
    /** Сменилось [notice] (главный экран перерисовывает строку статуса). Главный поток. */
    var listener: (() -> Unit)? = null

    /**
     * Старт приложения (главный экран, главный поток): на io — прочитать журнал; начало без итога —
     * удаление прервано (процесс погиб). Один раз на процесс; [force] — для тестов, перечитать.
     */
    fun init(ctx: Context, force: Boolean = false) {
        if (inited && !force) return
        inited = true
        base = DeleteLogStore(File(ctx.applicationContext.filesDir, FILE))
        val s = store()!!
        Holder.io.execute {
            val n = safe("read") { DeleteLogModel.notice(DeleteLogModel.entries(s.lines())) }
            main.post { notice = n; listener?.invoke() }
        }
    }

    private inline fun <T> safe(what: String, f: () -> T): T? = try { f() } catch (e: Throwable) {
        Log.w("ancdu", "delete log $what failed", e); null
    }

    /**
     * На io, до первого объекта действия: ОДНА запись «начало» на действие (у группы — папка, число,
     * сумма и до 20 имён). Возвращает её id (0 — журнала нет или не записалось).
     */
    fun begin(action: LogAction?): Long {
        val s = store() ?: return 0L
        if (action == null) return 0L
        val now = System.currentTimeMillis()
        val id = now * 1000 + (seq++ % 1000)
        return safe("start") { s.append(LogActions.start(id, now, action)); id } ?: 0L
    }

    /** На io, после последнего объекта действия: ОДНА запись «итог» ([total] — объектов в действии). */
    fun end(id: Long, code: Int, results: List<ItemResult>, total: Int) {
        if (id == 0L) return
        val s = store() ?: return
        safe("end") { s.append(LogActions.end(id, System.currentTimeMillis(), code, results, total)) }
    }

    /** Главный поток: освобождено стало известно позже (дерево обновилось). */
    fun freed(id: Long, bytes: Long) {
        if (id == 0L) return
        val s = store() ?: return
        Holder.io.execute { safe("freed") { s.append(LogRec.Freed(id, bytes)) } }
    }

    /** Главный поток: уведомление просмотрено (тап) — все непросмотренные прерванные отмечаются. */
    fun markSeen() {
        notice = null
        listener?.invoke()
        val s = store() ?: return
        Holder.io.execute {
            safe("seen") { for (e in DeleteLogModel.interrupted(DeleteLogModel.entries(s.lines()))) s.append(LogRec.Seen(e.start.id)) }
        }
    }

    /** На Holder.io: все записи журнала сейчас (пусто — журнала нет или он не читается). */
    fun entriesNow(): List<LogEntry> {
        val s = store() ?: return emptyList()
        return safe("read") { DeleteLogModel.entries(s.lines()) } ?: emptyList()
    }

    /** Главный поток: записи для листа журнала (новые сверху) — чтение на io, ответ [done] на главном. */
    fun read(done: (List<LogEntry>) -> Unit) {
        val s = store()
        Holder.io.execute {
            val r = if (s == null) emptyList() else safe("read") { DeleteLogModel.shown(DeleteLogModel.entries(s.lines())) } ?: emptyList()
            main.post { done(r) }
        }
    }

    /** Главный поток: очистить журнал и уведомление о прерванном; [done] — на главном (true — очищено). */
    /** Сколько раз журнал очищен в этом процессе (главный поток): ключ памяти строк главного экрана. */
    var clears = 0
        private set

    fun clear(done: (Boolean) -> Unit) {
        val s = store()
        Holder.io.execute {
            val ok = s != null && safe("clear") { s.clear(); true } == true
            main.post {
                if (ok) { notice = null; clears++; listener?.invoke() }
                done(ok)
            }
        }
    }

    /** Для тестов: забыть состояние процесса (уведомление) — перед тестом со своим журналом. */
    fun testReset() {
        notice = null
        listener?.invoke()
    }

    /** Для тестов: записать «начало» без итога (прерванное удаление), затем перечитать журнал. */
    fun testInterrupt(ctx: Context, root: String, names: List<ByteArray>, dir: Boolean, disk: Long, count: Int = 1,
                      items: List<ByteArray> = emptyList(), group: Boolean = false) {
        init(ctx)
        val s = store()!!
        Holder.io.execute {
            val now = System.currentTimeMillis()
            safe("test") { s.append(LogRec.Start(now * 1000 + 999, now, root, false, names, dir, 1, disk, false, false, count, items, group)) }
        }
        init(ctx, force = true)
    }
}
