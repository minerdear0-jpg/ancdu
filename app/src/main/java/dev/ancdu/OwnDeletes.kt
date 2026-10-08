package dev.ancdu

/**
 * Строка «что выросло» главного экрана отвечает «что выросло само»: байты, которые пользователь
 * освободил удалением в ancdu после точки отсчёта (журнал deletes.tsv), возвращаются к Δ — и корня, и
 * каждой папки над удалённым ([byNode]). Только строка главного экрана; сортировка Δ браузера и «ушло»
 * показывают дерево как есть. Журнал очищен — поправки нет.
 */
object OwnDeletes {
    /** Освобождено [bytes] под путём [chain] от корня (объект; у группы — её папка или общая папка). */
    class Freed(val chain: List<ByteArray>, val bytes: Long)

    /**
     * Удаления дерева [root] (ключ корня сессии, как пишет журнал), начатые после [baseTime], с известным
     * освобождённым > 0: итог «освобождено» (у группы — сумма удалённых целиком, без жёстких ссылок) или
     * уточнённое позже. Прерванное или частичное без уточнения — неизвестно сколько: не в счёт.
     */
    fun freed(entries: List<LogEntry>, root: String, baseTime: Long): List<Freed> {
        val r = root.trimEnd('/')
        return entries.filter { it.start.time > baseTime && it.start.root.trimEnd('/') == r }
            .mapNotNull { e -> e.freed?.takeIf { it > 0 }?.let { Freed(e.start.names, it) } }
    }

    /**
     * Поправка по узлам дерева: каждому предку удалённого (от ближайшего существующего — [deepest] — до
     * корня, [parent] корня < 0) — его освобождённое. O(записей × глубина). С насыщением.
     */
    fun byNode(list: List<Freed>, deepest: (List<ByteArray>) -> Int, parent: (Int) -> Int): Map<Int, Long> {
        val out = HashMap<Int, Long>()
        for (f in list) {
            var nd = deepest(f.chain)
            var guard = 0
            while (nd >= 0 && guard++ < 4096) {
                out[nd] = sat(out[nd] ?: 0L, f.bytes)
                if (nd == 0) break
                nd = parent(nd)
            }
        }
        return out
    }

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
     * На Holder.io: поправка для дерева [h] ключа [root] против точки отсчёта [baseTime] — из журнала
     * (чтение файла на io), пути — в живом дереве по именам (ближайший существующий предок).
     */
    fun forTree(h: Long, root: String, baseTime: Long): Map<Int, Long> {
        val list = freed(DeleteLog.entriesNow(), root, baseTime)
        if (list.isEmpty()) return emptyMap()
        val folders = HashMap<Int, GroupResolver>()
        return byNode(list, { chain ->
            PathWalk.resolve(chain) { nd, nm -> folders.getOrPut(nd) { GroupResolver(h, nd) }.find(nm) }.node
        }) { nd -> if (nd == 0) -1 else Native.parent(h, nd) }
    }
}
