package dev.ancdu

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
    /** Суммы disk и apparent ВСЕХ файлов не меньше порога (ядро считает и сверх [Giants.MAX]). */
    private var sums = LongArray(2)
    /** Сумма размеров всех «гигантов» в показанном режиме размера. */
    val sum: Long get() = sums[if (a.apparent) 1 else 0]
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
        // Порог и предел — по размеру на диске (и в режиме «видимый»): что считать «гигантом», решает диск.
        val tot = LongArray(3)
        val k = maxOf(0, Native.giants(a.h, minBytes, Giants.MAX, ids, tot))
        total = tot[0]
        sums = longArrayOf(tot[1], tot[2])
        a.kids = ids.copyOf(k)
        subs = arrayOfNulls(k); paths = arrayOfNulls(k)
        return k
    }

    /**
     * После nodeInfo: в режиме «видимый» — порядок по видимому размеру (ядро отдаёт по диску). Набор
     * строк тот же: порог и предел — по диску.
     */
    fun order() {
        val n = a.n
        if (a.apparent && n > 1) {
            val pos = (0 until n).sortedWith(compareByDescending<Int> { a.info[4 * it + 1] }.thenBy { a.kids[it] })
            val k2 = IntArray(a.kids.size)
            val i2 = LongArray(a.info.size)
            for ((j, p) in pos.withIndex()) { k2[j] = a.kids[p]; System.arraycopy(a.info, 4 * p, i2, 4 * j, 4) }
            a.kids = k2; a.info = i2
        }
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
