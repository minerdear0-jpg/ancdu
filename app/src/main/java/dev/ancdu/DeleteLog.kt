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
     * Начало удаления объекта (пишется на io ДО удаления): [root]/[su] — дерево, [names] — путь от
     * корня (байты), [items]/[disk] — на момент подтверждения, [viaRoot] — через su, [fast] — /data/media.
     */
    class Start(override val id: Long, val time: Long, val root: String, val su: Boolean, val names: List<ByteArray>,
                val dir: Boolean, val items: Long, val disk: Long, val viaRoot: Boolean, val fast: Boolean) : LogRec() {
        override fun format() = listOf("S", id, time, b(su), LogCodec.escape(root.toByteArray(Charsets.UTF_8)), LogCodec.rel(names),
            if (dir) "d" else "f", items, disk, b(viaRoot), b(fast)).joinToString("\t")
    }

    /** Итог: [code] — Native.delete, [freed] — освобождено (-1 — неизвестно), [removed] — записей удалено (-1 — неизвестно). */
    class End(override val id: Long, val time: Long, val code: Int, val freed: Long, val removed: Long) : LogRec() {
        override fun format() = listOf("E", id, time, code, freed, removed).joinToString("\t")
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
                    f[7].toLong(), f[8].toLong(), bool(f[9]), bool(f[10]))
                "E" -> if (f.size < 6) null else End(f[1].toLong(), f[2].toLong(), f[3].toInt(), f[4].toLong(), f[5].toLong())
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
        end.code == 0 -> LogOutcome.DELETED
        else -> LogOutcome.PARTIAL
    }

    /** Освобождено, если известно. */
    val freed: Long? get() = freedLater ?: end?.freed?.takeIf { it >= 0 }

    /** Отказ — ничего не удалено (и не потеряно): в журнале не показывается. */
    val refusal: Boolean get() = end != null && end.code != 0 && end.removed == 0L
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
        InterruptedDelete(it.id, it.root, it.su, it.names, it.dir, it.disk, it.time)
    }
}

/**
 * Файл журнала [file] (filesDir/deletes.tsv) и его tmp — единственные файлы, которые он трогает.
 * Дописывается; больше [CAP] записей — старейшие уходят (новый файл через tmp + rename). Только Holder.io.
 */
class DeleteLogStore(val file: File) {
    private val tmp get() = File(file.path + ".tmp")

    fun lines(): List<String> = if (!file.exists()) emptyList() else file.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }

    fun append(rec: LogRec) {
        val line = rec.format()
        val old = lines()
        if (old.size + 1 > CAP) { rewrite(old.takeLast(CAP - 1) + line); return }
        // Прошлую запись могли оборвать (процесс убит посреди write): новая — с новой строки.
        val torn = file.length() > 0 && RandomAccessFile(file, "r").use { it.seek(it.length() - 1); it.read() != '\n'.code }
        FileOutputStream(file, true).use { it.write(((if (torn) "\n" else "") + line + "\n").toByteArray(Charsets.UTF_8)) }
    }

    /** Очистить: пустой tmp, затем rename — журнал пуст целиком или прежний. */
    fun clear() = rewrite(emptyList())

    private fun rewrite(lines: List<String>) {
        val t = tmp
        FileOutputStream(t).use { out ->
            out.write(lines.joinToString("") { it + "\n" }.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!t.renameTo(file)) { t.delete(); throw java.io.IOException("rename failed: ${t.name}") }
    }

    companion object {
        const val CAP = 500
    }
}

/**
 * Журнал удалений процесса: «что и когда удалено». Запись — на Holder.io, в том же порядке задач,
 * что и само удаление; сбой ввода-вывода журнала пишется в лог и пропускается — удаление он не
 * останавливает и не меняет. [notice] и слушатель — главный поток.
 */
object DeleteLog {
    const val FILE = "deletes.tsv"
    @Volatile private var store: DeleteLogStore? = null
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
        val s = DeleteLogStore(File(ctx.applicationContext.filesDir, FILE)).also { store = it }
        Holder.io.execute {
            val n = safe("read") { DeleteLogModel.notice(DeleteLogModel.entries(s.lines())) }
            main.post { notice = n; listener?.invoke() }
        }
    }

    private inline fun <T> safe(what: String, f: () -> T): T? = try { f() } catch (e: Exception) {
        Log.w("ancdu", "delete log $what failed", e); null
    }

    /** На io, до удаления объекта: запись «начало». Возвращает её id (0 — журнала нет или не записалось). */
    fun started(root: String, su: Boolean, names: List<ByteArray>?, item: DeleteItem, items: Long): Long {
        val s = store ?: return 0L
        if (names == null) return 0L
        val now = System.currentTimeMillis()
        val id = now * 1000 + (seq++ % 1000)
        return safe("start") {
            s.append(LogRec.Start(id, now, root, su, names, item.dir, items, item.disk, item.helper != null, item.media)); id
        } ?: 0L
    }

    /** На io, сразу после удаления объекта: запись «итог». */
    fun ended(id: Long, code: Int, freed: Long, removed: Long) {
        if (id == 0L) return
        val s = store ?: return
        safe("end") { s.append(LogRec.End(id, System.currentTimeMillis(), code, freed, removed)) }
    }

    /** Главный поток: освобождено стало известно позже (дерево обновилось). */
    fun freed(id: Long, bytes: Long) {
        if (id == 0L) return
        val s = store ?: return
        Holder.io.execute { safe("freed") { s.append(LogRec.Freed(id, bytes)) } }
    }

    /** Главный поток: уведомление просмотрено (тап) — все непросмотренные прерванные отмечаются. */
    fun markSeen() {
        notice = null
        listener?.invoke()
        val s = store ?: return
        Holder.io.execute {
            safe("seen") { for (e in DeleteLogModel.interrupted(DeleteLogModel.entries(s.lines()))) s.append(LogRec.Seen(e.start.id)) }
        }
    }

    /** Главный поток: записи для листа журнала (новые сверху) — чтение на io, ответ [done] на главном. */
    fun read(done: (List<LogEntry>) -> Unit) {
        val s = store
        Holder.io.execute {
            val r = if (s == null) emptyList() else safe("read") { DeleteLogModel.shown(DeleteLogModel.entries(s.lines())) } ?: emptyList()
            main.post { done(r) }
        }
    }

    /** Главный поток: очистить журнал и уведомление о прерванном; [done] — на главном (true — очищено). */
    fun clear(done: (Boolean) -> Unit) {
        val s = store
        Holder.io.execute {
            val ok = s != null && safe("clear") { s.clear(); true } == true
            main.post {
                if (ok) { notice = null; listener?.invoke() }
                done(ok)
            }
        }
    }

    /** Для тестов: записать «начало» без итога (прерванное удаление), затем перечитать журнал. */
    fun testInterrupt(ctx: Context, root: String, names: List<ByteArray>, dir: Boolean, disk: Long) {
        init(ctx)
        val s = store!!
        Holder.io.execute {
            val now = System.currentTimeMillis()
            safe("test") { s.append(LogRec.Start(now * 1000 + 999, now, root, false, names, dir, 1, disk, false, false)) }
        }
        init(ctx, force = true)
    }
}
