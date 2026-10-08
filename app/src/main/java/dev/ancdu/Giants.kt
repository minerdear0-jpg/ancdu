package dev.ancdu

import java.io.ByteArrayOutputStream

/** Браузер открывается в режиме «гигантов»: плоский список всех крупных файлов дерева. */
const val EXTRA_GIANTS = "giants"

/**
 * Ключ выбора «гигантов»: ПОЛНАЯ цепочка имён от корня (байты; имена разных папок не уникальны).
 * Равенство и хеш — по содержимому каждого имени; массивы копируются на входе и на выходе.
 */
class ChainKey(names: List<ByteArray>) : SelKey {
    private val n: List<ByteArray> = names.map { it.copyOf() }
    /** Копии байтов имён от корня. */
    val names: List<ByteArray> get() = n.map { it.copyOf() }
    val size: Int get() = n.size
    /** Путь от корня для показа и TalkBack («DCIM/Camera/v.mp4»; невалидный UTF-8 — U+FFFD). */
    val rel: String get() = n.joinToString("/") { String(it, Charsets.UTF_8) }
    override fun equals(other: Any?): Boolean =
        other is ChainKey && other.n.size == n.size && n.indices.all { n[it].contentEquals(other.n[it]) }
    override fun hashCode(): Int = n.fold(n.size) { h, b -> 31 * h + b.contentHashCode() }
}

/** Объект группы «гигантов» для журнала: цепочка от корня, размер, каталог ли. */
class GiantObject(val chain: List<ByteArray>, val disk: Long, val dir: Boolean)

/** Чистый Kotlin: «гиганты» — порог, поиск цепочки в живом дереве, общая папка группы. */
object Giants {
    /** Файлы не меньше 100 МиБ (порог фиксирован). */
    const val MIN_BYTES = 100L shl 20
    /** Не больше стольких строк (ядро режет и возвращает общее число). */
    const val MAX = 2000

    /**
     * Узел по цепочке [names] от корня: каждый шаг — [child] (ребёнок узла с ровно этим именем), и
     * найденное проверяется [verify] (не удалён, родитель — тот, из которого шли, имя — то же). Шаг не
     * найден или не проверен — null. Пустая цепочка (корень) — null: корень не объект удаления.
     */
    fun resolve(names: List<ByteArray>, child: (Int, ByteArray) -> Int?, verify: (node: Int, parent: Int, name: ByteArray) -> Boolean): Int? {
        if (names.isEmpty()) return null
        var cur = 0
        for (nm in names) {
            val c = child(cur, nm) ?: return null
            if (!verify(c, cur, nm)) return null
            cur = c
        }
        return cur
    }

    /** Общая папка цепочек [chains] (их папок — без последнего имени); корень — пустой список. */
    fun commonFolder(chains: List<List<ByteArray>>): List<ByteArray> {
        if (chains.isEmpty()) return emptyList()
        var p = chains[0].dropLast(1)
        for (c in chains.drop(1)) {
            val folder = c.dropLast(1)
            var k = 0
            while (k < p.size && k < folder.size && p[k].contentEquals(folder[k])) k++
            p = p.take(k)
        }
        return p.map { it.copyOf() }
    }

    /** Путь [chain] от папки [folder] (её префикс) — имена через «/», байты как есть. */
    fun relTo(folder: List<ByteArray>, chain: List<ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        for ((i, nm) in chain.drop(folder.size).withIndex()) { if (i > 0) write('/'.code); write(nm) }
    }.toByteArray()

    /**
     * Журнал группы из разных папок: родитель — общая папка (корень — «(разные папки)» в листе), имена —
     * пути от неё. Один объект — путь от корня (одиночное удаление его самого: [LogActions.group]).
     */
    fun logObjects(objs: List<GiantObject>): Pair<List<ByteArray>, List<LogObject>> {
        if (objs.size == 1) { val o = objs[0]; return o.chain.dropLast(1) to listOf(LogObject(o.chain.last(), o.disk, o.dir)) }
        val folder = commonFolder(objs.map { it.chain })
        return folder to objs.map { LogObject(relTo(folder, it.chain), it.disk, it.dir) }
    }
}

/** Чистый Kotlin: тексты «гигантов». */
object GiantsText {
    /** «312 файлов ≥ 100 МиБ · 214,3 ГиБ». */
    fun summary(t: Txt, count: Long, sum: Long): String =
        t.q(R.plurals.giants_summary, count, Fmt.count(count, t.locale), Fmt.size(sum, t))

    /** Ссылка в шапке «самых крупных файлов»: «все 312 ›». */
    fun link(t: Txt, count: Long): String = t.s(R.string.giants_link, Fmt.count(count, t.locale))

    fun linkDesc(t: Txt, count: Long): String = t.s(R.string.giants_link_desc, Fmt.count(count, t.locale))

    /** Ссылка есть, только когда файлов больше, чем строк на главном экране. */
    fun linkShown(total: Long): Boolean = total > Biggest.K

    /** «Нет файлов больше 100 МиБ». */
    fun empty(t: Txt): String = t.s(R.string.giants_empty)

    /** TalkBack строки: «имя, размер, в папке X» и метка. */
    fun desc(t: Txt, name: String, size: String, folder: String, tag: Tag?, label: String? = null): String =
        t.s(R.string.giants_desc, name, size, folder) + (tag?.desc(t, label) ?: "")
}
