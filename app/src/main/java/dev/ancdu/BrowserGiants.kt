package dev.ancdu

import android.content.Context
import android.util.Log

/**
 * Режим «гигантов» браузера [a]: тот же браузер, но уровень — плоский список всех файлов дерева
 * не меньше [Giants.MIN_BYTES] (Native.giants, по убыванию размера), строки в две строки (папка —
 * второй строкой), выбор — по ПОЛНЫМ цепочкам имён от корня ([ChainKey]). Главный поток; чтения
 * дерева — с дескриптором экрана.
 */
class GiantsLevel(private val a: BrowserActivity) {
    /** Экран в режиме «гигантов» (задаётся при создании, не меняется). */
    var on = false
    /** Сколько файлов не меньше порога во всём дереве (может быть больше показанных: предел [Giants.MAX]). */
    var total = 0L
        private set
    /** Сумма размеров показанных строк (в показанном режиме размера). */
    var sum = 0L
        private set
    /** Папки строк относительно корня («DCIM/Camera/»; файл в корне — заголовок корня) — лениво. */
    private var subs = arrayOfNulls<String>(0)
    /** Пути строк — лениво (метки, превью). */
    private var paths = arrayOfNulls<String>(0)

    /**
     * load() в режиме «гигантов»: узлы списка в [BrowserActivity.kids] — ровно записанные ядром
     * (не больше [Giants.MAX]), по убыванию размера в показанном режиме. Возвращает их число.
     */
    fun read(): Int {
        val ids = IntArray(Giants.MAX)
        val tot = LongArray(1)
        val k = maxOf(0, Native.giants(a.h, minBytes, Giants.MAX, ids, tot))
        total = tot[0]
        a.kids = ids.copyOf(k)
        subs = arrayOfNulls(k); paths = arrayOfNulls(k)
        return k
    }

    /** После nodeInfo: в режиме «видимый» — порядок по видимому размеру (ядро отдаёт по диску); сумма. */
    fun order() {
        val n = a.n
        if (a.apparent && n > 1) {
            val pos = (0 until n).sortedWith(compareByDescending<Int> { a.info[4 * it + 1] }.thenBy { a.kids[it] })
            val k2 = IntArray(a.kids.size)
            val i2 = LongArray(a.info.size)
            for ((j, p) in pos.withIndex()) { k2[j] = a.kids[p]; System.arraycopy(a.info, 4 * p, i2, 4 * j, 4) }
            a.kids = k2; a.info = i2
        }
        sum = DeletePolicy.sum((0 until n).map { a.value(it) })
    }

    /** Путь строки [i] (полный). */
    fun pathAt(i: Int): String = paths[i] ?: Native.str(Native.path(a.h, a.kids[i])).also { paths[i] = it }

    /** Папка строки [i] от корня — вторая строка и TalkBack. */
    fun subAt(i: Int): String = subs[i] ?: run {
        val nd = a.kids[i]
        Biggest.parentText(a.txt, a.rootPath(), Native.str(Native.path(a.h, maxOf(Native.parent(a.h, nd), 0))))
    }.also { subs[i] = it }

    /** Сводка шапки: «312 файлов ≥ 100 МиБ · 214,3 ГиБ». */
    fun summary(): String = GiantsText.summary(a.txt, total, sum)

    /** Узел по цепочке [chain] в живом дереве экрана (каждый шаг проверен) или null. Главный поток. */
    fun resolve(chain: List<ByteArray>): Int? = ChainResolver(a.h).find(chain)

    companion object {
        /** Только для тестов: порог вместо [Giants.MIN_BYTES] (маленькие настоящие файлы песочницы). */
        @Volatile var testMinBytes: Long? = null
        val minBytes: Long get() = testMinBytes ?: Giants.MIN_BYTES
    }
}

/**
 * Узлы по цепочкам имён от корня в дереве [handle] — на io внутри удаления группы «гигантов» (или
 * на главном потоке вне удаления). Дети каждой пройденной папки читаются один раз в карту по
 * именам; КАЖДЫЙ шаг проверяется заново ([verified]: не удалён, родитель — тот, из которого шли,
 * имя — ровно то же); не сошлось — полный проход по живым детям этой папки. Узлы из старого
 * дерева сюда не попадают: только имена.
 */
class ChainResolver(val handle: Long) {
    private val maps = HashMap<Int, HashMap<NameKey, Int>>()

    private fun live(folder: Int): Pair<IntArray, Int> {
        val c = IntArray(Native.childCount(handle, folder))
        return c to maxOf(0, Native.children(handle, folder, SORT_NAME, false, c))
    }

    private fun child(folder: Int, key: ByteArray): Int? {
        val m = maps.getOrPut(folder) {
            HashMap<NameKey, Int>().also { m -> val (c, k) = live(folder); for (i in 0 until k) m[NameKey(Native.name(handle, c[i]))] = c[i] }
        }
        m[NameKey(key)]?.let { if (verified(it, folder, key)) return it }
        val (c, k) = live(folder)
        for (i in 0 until k) if (verified(c[i], folder, key)) return c[i]
        return null
    }

    /** Узел по цепочке [chain] или null (нет, удалён, подменён; пустая цепочка — корень — тоже null). */
    fun find(chain: List<ByteArray>): Int? = Giants.resolve(chain, ::child, ::verified)

    private fun verified(nd: Int, parent: Int, key: ByteArray): Boolean {
        if (nd <= 0) return false
        val f = LongArray(8).also { Native.nodeInfo(handle, intArrayOf(nd, parent), 2, it) }
        return f[3].toInt() and F_DELETED == 0 && f[7].toInt() and F_DELETED == 0 &&
            Native.parent(handle, nd) == parent && Native.name(handle, nd).contentEquals(key)
    }
}

/**
 * На io, к началу объекта группы «гигантов»: узел по ПОЛНОЙ цепочке [chain] в ЖИВОМ дереве (каждый
 * шаг проверен, [ChainResolver]), повторная проверка запрета ([kind], [sessionRoot] — сессии на момент
 * подтверждения; быстрый путь — NO_FAST, как у группы папки), затем тот же путь удаления, что у
 * одного ([deleteItem]; проверка «изменился после скана» — в ядре, на объект). Нет в дереве —
 * ENOENT (уже удалён); запрещён — не отправляется. Узлы не из этой цепочки не трогаются.
 */
internal fun giantJob(app: Context, res: ChainResolver, chain: List<ByteArray>, name: String, fast: Boolean, kind: Kind,
                      viaRoot: Boolean, sessionRoot: String): Planned = try {
    val handle = res.handle
    val node = res.find(chain)
    if (node == null) Planned.Skip(ItemResult(name, false, 0L, -GroupResult.ENOENT, 0L))
    else {
        val inf = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(node), 1, it) }
        val flags = inf[3].toInt()
        val dir = flags and F_DIR != 0
        val path = Native.str(Native.path(handle, node))
        val block = DeletePolicy.blockReason(path, false, Native.parent(handle, node) == 0, sessionRoot, flags, kind)
            ?: if (fast && (DeletePolicy.fastBlockReason(path) != null || !Root.suExists())) Block.NO_FAST else null
        if (block != null) {
            Log.i("ancdu", "giants item not sent: $block")
            Planned.Skip(ItemResult(name, dir, inf[0], -1, 0L, attempted = false, block = block))
        } else Planned.Go(deleteItem(app, handle, node, fast, kind, viaRoot, label = name))
    }
} catch (e: Exception) {
    Log.w("ancdu", "giants item skipped", e)
    Planned.Skip(ItemResult(name, false, 0L, -5, 0L, attempted = false))
}
