package dev.ancdu

/**
 * Строка «крупнейших файлов»: имя, размер на диске, папка (уже для показа), путь байтами имён от
 * корня (для [EXTRA_FOCUS]) и метка безопасности с меткой приложения.
 */
class BigFile(val name: String, val disk: Long, val parent: String, val names: List<ByteArray>,
              val tag: Tag?, val tagLabel: String?,
              /** Новый с точки отсчёта «что выросло» (значок NEW). */
              val isNew: Boolean = false)

/** Чистый Kotlin: тексты «крупнейших файлов» главного экрана. */
object Biggest {
    /** Сколько строк показывает главный экран. */
    const val K = 5

    /**
     * Папка [parent] относительно корня дерева [root], с «/» в конце: «Download/», «DCIM/Camera/»;
     * сам корень — «». Не под корнем (так не бывает) — полный путь с «/».
     */
    fun relParent(root: String, parent: String): String {
        val r = root.trimEnd('/')
        val p = parent.trimEnd('/')
        if (p == r) return ""
        val rel = if (p.startsWith("$r/")) p.substring(r.length + 1) else p
        return if (rel.isEmpty()) "" else "$rel/"
    }

    /** Подпись папки: относительный путь, у файлов в самом корне — его заголовок («Внутренняя память»). */
    fun parentText(t: Txt, root: String, parent: String): String =
        relParent(root, parent).ifEmpty { PathText.rootTitle(root, t.s(R.string.internal_storage)) }

    /** TalkBack: «<имя>, <размер>, в <папка>» и метка («, загрузки»). */
    fun desc(t: Txt, name: String, size: String, parent: String, tag: Tag?, label: String? = null,
             isNew: Boolean = false): String =
        t.s(R.string.big_desc, name, size, parent) + (tag?.desc(t, label) ?: "") +
            (if (isNew) ", " + t.s(R.string.desc_new) else "")
}
