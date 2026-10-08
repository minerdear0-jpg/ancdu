package dev.ancdu

/**
 * Имя ребёнка — байты (не строка: разные невалидные UTF-8 имена декодируются в одну строку с
 * U+FFFD). Равенство и хеш — по содержимому, массив копируется.
 */
/** Ключ выбора: имя в одной папке ([NameKey]) или цепочка имён от корня ([ChainKey], «гиганты»). */
interface SelKey

class NameKey(bytes: ByteArray) : SelKey {
    /** Своя копия: чужой массив, изменённый после, ключ не портит. */
    private val b = bytes.copyOf()
    /** Копия байтов имени. */
    val bytes: ByteArray get() = b.copyOf()
    override fun equals(other: Any?): Boolean = other is NameKey && b.contentEquals(other.b)
    override fun hashCode(): Int = b.contentHashCode()
}

/**
 * Режим выбора. Чистый Kotlin. Ключ — имя ребёнка папки (байты, [NameKey]) или, в «гигантах», цепочка
 * имён от корня ([ChainKey]); значение — его узел в текущем дереве: узлы годятся только для этого
 * дерева, после подстановки нового — [rebind] по ключам. Снят последний — режим выходит сам.
 */
class Selection {
    private val map = LinkedHashMap<SelKey, Int>()
    private val nodeSet = HashSet<Int>()

    var active = false
        private set
    val count: Int get() = map.size
    val keys: List<SelKey> get() = map.keys.toList()
    val nodes: IntArray get() = map.values.toIntArray()

    fun contains(node: Int): Boolean = node in nodeSet
    fun nodeOf(key: SelKey): Int? = map[key]

    /** Войти в режим с одним выбранным (прежний выбор забывается). */
    fun start(key: SelKey, node: Int) {
        clear()
        active = true
        put(key, node)
    }

    /** Переключить; true — теперь выбран. Снят последний — режим выходит. */
    fun toggle(key: SelKey, node: Int): Boolean {
        if (map.containsKey(key)) {
            map.remove(key)?.let { nodeSet.remove(it) }
            if (map.isEmpty()) active = false
            return false
        }
        active = true
        put(key, node)
        return true
    }

    /** «ВСЕ»: каждый из [rows], кроме запрещённых ([blocked] по узлу). */
    fun selectAll(rows: List<Pair<SelKey, Int>>, blocked: (Int) -> Boolean) {
        for ((k, nd) in rows) if (!blocked(nd)) put(k, nd)
        if (map.isNotEmpty()) active = true
    }

    /** Выбрано всё выбираемое из [rows] (пустое множество выбираемых — не «всё»). */
    fun isAll(rows: List<Pair<SelKey, Int>>, blocked: (Int) -> Boolean): Boolean {
        var any = false
        for ((k, nd) in rows) {
            if (blocked(nd)) continue
            any = true
            if (!map.containsKey(k)) return false
        }
        return any
    }

    /** Выйти из режима; сколько было выбрано. */
    fun leave(): Int {
        val n = map.size
        clear()
        return n
    }

    /** Новое дерево: узлы заново по именам ([lookup] — узел или null); сколько пропало. Пусто — выход. */
    fun rebind(lookup: (SelKey) -> Int?): Int {
        val old = map.keys.toList()
        map.clear(); nodeSet.clear()
        var gone = 0
        for (k in old) { val nd = lookup(k); if (nd == null) gone++ else put(k, nd) }
        if (map.isEmpty()) active = false
        return gone
    }

    private fun put(key: SelKey, node: Int) {
        map.put(key, node)?.let { nodeSet.remove(it) }
        nodeSet += node
    }

    private fun clear() {
        map.clear(); nodeSet.clear(); active = false
    }
}
