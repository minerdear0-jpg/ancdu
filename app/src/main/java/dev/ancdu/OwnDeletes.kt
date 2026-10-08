package dev.ancdu

/**
 * Строка «что выросло» главного экрана отвечает «что выросло само»: байты, которые пользователь
 * освободил удалением в ancdu после точки отсчёта (журнал deletes.tsv), возвращаются к Δ — и корня, и
 * каждой папки над удалённым ([byNode]). Возвращается не больше, чем ДЕЙСТВИТЕЛЬНО ушло из точки
 * отсчёта («ушло» Δ): файл, созданный после неё; путь, созданный заново; дерево старше удаления;
 * старая запись жёсткой ссылки — ничего не добавляют. Только строка главного экрана; сортировка Δ
 * браузера и «ушло» показывают дерево как есть. Журнал очищен — поправки нет.
 */
object OwnDeletes {
    /**
     * Освобождено [bytes] под путём [chain] от корня (объект; у группы — её папка; [mixed] — группа из
     * разных папок: общая папка, объекты — глубже).
     */
    class Freed(val chain: List<ByteArray>, val bytes: Long, val mixed: Boolean = false)

    /**
     * Удаления дерева [root] (ключ корня сессии, как пишет журнал), начатые после [baseTime], с известным
     * освобождённым > 0: итог «освобождено» (у группы — сумма удалённых целиком, без жёстких ссылок) или
     * уточнённое позже. Прерванное или частичное без уточнения — неизвестно сколько: не в счёт.
     */
    fun freed(entries: List<LogEntry>, root: String, baseTime: Long): List<Freed> {
        val r = root.trimEnd('/')
        return entries.filter { it.start.time > baseTime && it.start.root.trimEnd('/') == r }
            .mapNotNull { e -> e.freed?.takeIf { it > 0 }?.let { Freed(e.start.names, it, e.start.mixed) } }
    }

    /**
     * Поправка по узлам дерева. Освобождённое записи — у ближайшего существующего узла её цепочки X
     * ([deepest]); у X возвращается не больше, чем ушло из точки отсчёта: [gone] самого X (объект или
     * группа одной папки — удалённое лежит в «ушло» своей папки) или, если у X есть запись группы из
     * разных папок, — «ушло» во всём поддереве X (один проход по [gone] вверх, O(|gone| × глубина)).
     * Предел — НЕ −Δ папки: удалил 5 и вырос на 2 в той же папке — это +2. Затем — каждому предку X до
     * корня ([parent] корня < 0). С насыщением.
     */
    fun byNode(list: List<Freed>, deepest: (List<ByteArray>) -> Int, gone: Map<Int, Long>, parent: (Int) -> Int): Map<Int, Long> {
        val at = HashMap<Int, Long>()
        val mixedAt = HashSet<Int>()
        for (f in list) {
            val x = deepest(f.chain)
            if (x < 0) continue
            at[x] = sat(at[x] ?: 0L, f.bytes)
            if (f.mixed) mixedAt += x
        }
        if (at.isEmpty()) return emptyMap()
        val under = if (mixedAt.isEmpty()) emptyMap() else goneUnder(mixedAt, gone, parent)
        val out = HashMap<Int, Long>()
        for ((x, b) in at) {
            val cap = if (x in mixedAt) under[x] ?: 0L else gone[x] ?: 0L
            val add = minOf(b, maxOf(0L, cap))
            if (add <= 0) continue
            var nd = x
            var guard = 0
            while (nd >= 0 && guard++ < MAX_DEPTH) {
                out[nd] = sat(out[nd] ?: 0L, add)
                if (nd == 0) break
                nd = parent(nd)
            }
        }
        return out
    }

    /** «Ушло» в поддереве каждого из [targets]: каждый узел [gone] — вверх до корня. */
    private fun goneUnder(targets: Set<Int>, gone: Map<Int, Long>, parent: (Int) -> Int): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        for ((g, b) in gone) {
            var nd = g
            var guard = 0
            while (nd >= 0 && guard++ < MAX_DEPTH) {
                if (nd in targets) out[nd] = sat(out[nd] ?: 0L, b)
                if (nd == 0) break
                nd = parent(nd)
            }
        }
        return out
    }

    private const val MAX_DEPTH = 4096

    /** Δ без собственных удалений: [delta] + [own] (с насыщением). */
    fun corrected(delta: Long, own: Long): Long = sat(delta, own)

    /** Дети для «больше всего» — с поправкой их Δ. */
    fun kids(kids: List<Mostly.Kid>, own: Map<Int, Long>): List<Mostly.Kid> =
        if (own.isEmpty()) kids else kids.map { k -> own[k.node]?.let { Mostly.Kid(k.node, corrected(k.delta, it), k.dir) } ?: k }

    private fun sat(a: Long, b: Long): Long {
        val r = a + b
        return if (b > 0 && r < a) Long.MAX_VALUE else if (b < 0 && r > a) Long.MIN_VALUE else r
    }

    /**
     * На Holder.io: поправка для дерева [h] ключа [root] по его Δ [d] (точка отсчёта и «ушло») — из журнала
     * (чтение файла на io), пути — в живом дереве по именам (ближайший существующий предок).
     */
    fun forTree(h: Long, root: String, d: Delta): Map<Int, Long> {
        val list = freed(DeleteLog.entriesNow(), root, d.baseTime)
        if (list.isEmpty()) return emptyMap()
        val folders = HashMap<Int, GroupResolver>()
        return byNode(list, { chain ->
            PathWalk.resolve(chain) { nd, nm -> folders.getOrPut(nd) { GroupResolver(h, nd) }.find(nm) }.node
        }, d.gone.mapValues { it.value.disk }) { nd -> if (nd == 0) -1 else Native.parent(h, nd) }
    }
}
