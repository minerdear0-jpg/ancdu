package dev.ancdu

/** Чистый Kotlin: тот же путь по именам в другом дереве (после «новее · обновить»). */
object PathWalk {
    /** [node] — найденный узел; [exact] — найден весь путь, иначе это ближайший существующий предок. */
    data class Hit(val node: Int, val exact: Boolean)

    /**
     * [names] — сырые байты имён от корня (без самого корня): сравнивать только байты — разные
     * невалидные UTF-8 имена декодируются в одну и ту же строку с U+FFFD.
     * [child] — ребёнок узла с ровно этим именем или null.
     */
    fun resolve(names: List<ByteArray>, child: (Int, ByteArray) -> Int?): Hit {
        var cur = 0
        for (nm in names) cur = child(cur, nm) ?: return Hit(cur, false)
        return Hit(cur, true)
    }
}
