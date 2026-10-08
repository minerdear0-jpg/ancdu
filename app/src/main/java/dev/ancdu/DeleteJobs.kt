package dev.ancdu

import android.content.Context
import android.util.Log

/**
 * Дети папки [folder] дерева [handle] по байтам имени — только на io внутри удаления группы (или на
 * главном потоке вне удаления). Карта строится один раз к первому поиску (из живого дерева), каждый
 * найденный узел проверяется заново ([verified]: не удалён, папка не удалена, родитель — [folder],
 * имя — ровно то же). Не сошлось — полный проход по живым детям. ЕДИНСТВЕННАЯ проверка шага: ею
 * пользуются и группа одной папки, и цепочки «гигантов» ([ChainResolver]).
 */
class GroupResolver(val handle: Long, val folder: Int) {
    private var map: HashMap<NameKey, Int>? = null

    private fun live(): Pair<IntArray, Int> {
        val c = IntArray(Native.childCount(handle, folder))
        return c to maxOf(0, Native.children(handle, folder, SORT_NAME, false, c))
    }

    fun find(key: ByteArray): Int? {
        val m = map ?: HashMap<NameKey, Int>().also { m ->
            val (c, k) = live()
            for (i in 0 until k) m[NameKey(Native.name(handle, c[i]))] = c[i]
            map = m
        }
        m[NameKey(key)]?.let { if (verified(it, key)) return it }
        val (c, k) = live()
        for (i in 0 until k) if (verified(c[i], key)) return c[i]
        return null
    }

    fun verified(nd: Int, key: ByteArray): Boolean {
        if (nd <= 0) return false
        val f = LongArray(8).also { Native.nodeInfo(handle, intArrayOf(nd, folder), 2, it) }
        return f[3].toInt() and F_DELETED == 0 && f[7].toInt() and F_DELETED == 0 &&
            Native.parent(handle, nd) == folder && Native.name(handle, nd).contentEquals(key)
    }
}

/**
 * Узлы по цепочкам имён от корня в дереве [handle] («гиганты»): шаг за шагом через [GroupResolver]
 * каждой пройденной папки (карта детей — одна на папку), каждый шаг проверен им же. Узлы из старого
 * дерева сюда не попадают: только имена. Пустая цепочка (корень) — null.
 */
class ChainResolver(val handle: Long) {
    private val folders = HashMap<Int, GroupResolver>()

    private fun folder(nd: Int): GroupResolver = folders.getOrPut(nd) { GroupResolver(handle, nd) }

    /** Узел по цепочке [chain] или null (нет, удалён, подменён). */
    fun find(chain: List<ByteArray>): Int? =
        Giants.resolve(chain, { p, nm -> folder(p).find(nm) }, { nd, p, nm -> folder(p).verified(nd, nm) })
}

/**
 * На io, к началу объекта группы (одной папки или «гигантов»): узел — [find] в ЖИВОМ дереве [handle]
 * (по имени или цепочке, не по старому id), повторная проверка запрета ([DeletePolicy.itemBlock]:
 * [kind], [sessionRoot] — сессии на момент подтверждения; быстрый путь — NO_FAST), затем тот же путь
 * удаления, что у одного ([deleteItem]; «изменился после скана» — в ядре, на объект). [label] — имя
 * объекта в итогах (имя в папке или путь от корня). Нет в дереве — ENOENT (уже удалён); запрещён — не
 * отправляется.
 */
internal fun groupJob(app: Context, handle: Long, find: () -> Int?, label: String, fast: Boolean, kind: Kind,
                      viaRoot: Boolean, sessionRoot: String): Planned = try {
    val node = find()
    if (node == null) Planned.Skip(ItemResult(label, false, 0L, -GroupResult.ENOENT, 0L))
    else {
        val inf = LongArray(4).also { Native.nodeInfo(handle, intArrayOf(node), 1, it) }
        val flags = inf[3].toInt()
        val dir = flags and F_DIR != 0
        val path = Native.str(Native.path(handle, node))
        val block = DeletePolicy.itemBlock(path, Native.parent(handle, node) == 0, sessionRoot, flags, kind, fast) { Root.suExists() }
        if (block != null) {
            Log.i("ancdu", "group item not sent: $block")
            Planned.Skip(ItemResult(label, dir, inf[0], -1, 0L, attempted = false, block = block))
        } else Planned.Go(deleteItem(app, handle, node, fast, kind, viaRoot, label = label))
    }
} catch (e: Exception) {
    Log.w("ancdu", "group item skipped", e)
    Planned.Skip(ItemResult(label, false, 0L, -5, 0L, attempted = false))
}
