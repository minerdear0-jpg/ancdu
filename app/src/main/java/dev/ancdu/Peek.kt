package dev.ancdu

import java.util.Locale
import java.util.TimeZone

/** Вид превью карточки: решается по расширению ДО чтения файла (место под превью — сразу). */
enum class PeekKind { IMAGE, VIDEO, TEXT, APK, NONE }

/** Чистая часть карточки быстрого просмотра ([QuickLook]): что показывать и какими строками. */
object Peek {
    /** Сколько байт читает текстовое превью и в скольких первых ищется NUL. */
    const val READ = 2048
    const val SNIFF = 512
    const val TEXT_LINES = 13
    /** Превью не пришло за это время — «превью недоступно». */
    const val TIMEOUT_MS = 1500L

    private val TEXT_EXT = setOf("txt", "log", "md", "json", "xml", "csv", "conf", "ini", "kt", "java", "c", "h",
        "py", "sh", "yml", "yaml")

    /**
     * [ext] — расширение ([Ellipsis.ext]), [mime] — MimeTypeMap по нему. Без расширения — TEXT:
     * решает проверка содержимого ([sniff]), не прошла — «превью недоступно» на том же месте.
     */
    fun kind(ext: String?, mime: String?): PeekKind {
        val e = ext?.lowercase(Locale.ROOT) ?: return PeekKind.TEXT
        return when {
            e == "apk" -> PeekKind.APK
            mime?.startsWith("image/") == true -> PeekKind.IMAGE
            mime?.startsWith("video/") == true -> PeekKind.VIDEO
            e in TEXT_EXT || mime?.startsWith("text/") == true -> PeekKind.TEXT
            else -> PeekKind.NONE
        }
    }

    /**
     * Вид превью узла дерева: путь только для root ([rootOnly]), ссылка, каталог, другая ФС — NONE
     * (места под превью нет, ничего не читается); иначе — по расширению ([kind]).
     */
    fun kindFor(ext: String?, mime: String?, rootOnly: Boolean, flags: Int): PeekKind =
        if (rootOnly || !treeAllows(flags)) PeekKind.NONE else kind(ext, mime)

    /**
     * Первые [n] байт [b] как текст или null (двоичный): в первых [SNIFF] байтах нет NUL, после
     * декодирования UTF-8 с заменой U+FFFD меньше 10%. Недочитанный хвост многобайтного символа
     * отбрасывается. Управляющие C0 (кроме \t и \n) и направления текста вырезаются.
     */
    fun sniff(b: ByteArray, n: Int): String? {
        for (i in 0 until minOf(n, SNIFF)) if (b[i] == 0.toByte()) return null
        val s = String(b, 0, completeEnd(b, n), Charsets.UTF_8)
        if (s.isNotEmpty() && s.count { it == '�' } * 10 >= s.length) return null
        return Bidi.strip(s.filterNot { it < ' ' && it != '\t' && it != '\n' })
    }

    /** Конец [n] без начатого, но не законченного многобайтного символа UTF-8. */
    private fun completeEnd(b: ByteArray, n: Int): Int {
        var i = n - 1
        while (i >= 0 && i >= n - 4 && (b[i].toInt() and 0xC0) == 0x80) i--
        if (i < 0 || i < n - 4) return n
        val lead = b[i].toInt() and 0xFF
        val len = when {
            lead and 0xE0 == 0xC0 -> 2
            lead and 0xF0 == 0xE0 -> 3
            lead and 0xF8 == 0xF0 -> 4
            else -> 1
        }
        return if (i + len > n) i else n
    }

    /** Строка вида: «IMAGE · JPEG», «TEXT · UTF-8», «APK», «FILE · .bin». [text] — содержимое прошло [sniff]. */
    fun typeLine(t: Txt, kind: PeekKind, ext: String?, mime: String?, text: Boolean): String {
        // Расширение — из имени файла (чужие данные): одной строкой, bidi видимыми.
        fun sub() = Bidi.label((mime?.substringAfter('/')?.removePrefix("x-")?.substringBefore('+') ?: ext.orEmpty())
            .uppercase(Locale.ROOT))
        return when (kind) {
            PeekKind.IMAGE -> t.s(R.string.ql_image, sub())
            PeekKind.VIDEO -> t.s(R.string.ql_video, sub())
            PeekKind.TEXT -> if (ext != null || text) t.s(R.string.ql_text) else t.s(R.string.ql_file)
            PeekKind.APK -> t.s(R.string.ql_apk)
            PeekKind.NONE -> if (ext != null) t.s(R.string.ql_file_ext, Bidi.label(ext)) else t.s(R.string.ql_file)
        }
    }

    /**
     * Узел дерева можно смотреть: не каталог, не символическая ссылка, не другая ФС. Иначе места
     * под превью нет — решается по флагам дерева до чтения.
     */
    fun treeAllows(flags: Int): Boolean = flags and (F_DIR or F_SYMLINK or F_OTHERFS) == 0

    /** Вид узла по lstat (без перехода по ссылке). */
    enum class NodeType { REGULAR, LINK, OTHER, MISSING }

    /**
     * Путь, который можно читать, или null. Читается только обычный файл: FIFO, устройство,
     * сокет блокируют чтение навсегда. Ссылка разрешается ([resolve] — полностью, как
     * canonicalPath), и цель проверяется ещё раз [stat]: снова ссылка (подмена) — null.
     */
    fun regularTarget(path: String, stat: (String) -> NodeType, resolve: (String) -> String?): String? =
        when (stat(path)) {
            NodeType.REGULAR -> path
            NodeType.LINK -> resolve(path)?.takeIf { stat(it) == NodeType.REGULAR }
            else -> null
        }

    /** Длительность видео: «0:42», «12:05», «1:02:03». */
    fun duration(ms: Long): String {
        val s = maxOf(ms, 0L) / 1000
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
            else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    fun dims(w: Int, h: Int): String = "$w×$h"

    /** Время изменения с годом («12.06.2024 18:30»); 0 — неизвестно, «—». */
    fun mtime(t: Txt, ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        if (ms <= 0) "—" else Freshness.date(t, R.string.fmt_date_year, ms, tz)

    /** Строка сведений: размер · подробности · время (пустые части пропускаются). */
    fun meta(parts: List<String?>): String = parts.filterNot { it.isNullOrEmpty() }.joinToString(" · ")

    /** «показаны первые 2 КиБ из 18,4 МиБ» — если файл длиннее прочитанного, иначе null. */
    fun textNote(t: Txt, size: Long): String? = if (size > READ) t.s(R.string.ql_text_more, Fmt.size(size, t)) else null

    /**
     * Путь читает только root: дерево от root ([viaRoot]) вне /storage и вне данных самого
     * приложения [pkg]. Такая карточка — только сведения из дерева, содержимое не читается.
     */
    fun rootOnly(path: String, viaRoot: Boolean, pkg: String): Boolean {
        if (!viaRoot || path.startsWith("/storage/")) return false
        val own = Regex("/data/(data|user/[0-9]+|user_de/[0-9]+)/" + Regex.escape(pkg) + "(/.*)?")
        return !own.matches(path)
    }
}
