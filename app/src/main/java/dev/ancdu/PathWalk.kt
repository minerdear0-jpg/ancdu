package dev.ancdu

/** Чистый Kotlin: тот же путь по именам в другом дереве (после «новее · обновить»). */
object PathWalk {
    /** [node] — найденный узел; [exact] — найден весь путь, иначе это ближайший существующий предок. */
    data class Hit(val node: Int, val exact: Boolean)

    /** [names] — имена от корня (без самого корня); [child] — ребёнок узла с этим именем или null. */
    fun resolve(names: List<String>, child: (Int, String) -> Int?): Hit {
        var cur = 0
        for (nm in names) cur = child(cur, nm) ?: return Hit(cur, false)
        return Hit(cur, true)
    }
}
