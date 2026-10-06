package dev.ancdu

/** Что удалять нельзя никогда. Чистый Kotlin: решение по пути, флагам узла и виду дерева. */
object DeletePolicy {
    const val SYSTEM = "системный путь — удаление отключено"
    const val OTHER_FS = "другая файловая система — удаление отключено"
    const val INDEX_DIR = "в индексе видны не все файлы — сделайте скан"
    const val ALL_APP_DATA = "удаляет данные всех приложений — удаление отключено"
    const val USER_STORAGE = "всё хранилище пользователя — удаление отключено"

    private val PROTECTED = listOf("/data/system", "/data/adb", "/data/app", "/data/misc",
        "/system", "/vendor", "/apex", "/proc", "/sys", "/dev")

    private fun under(p: String, dir: String) = p == dir || p.startsWith(if (dir == "/") dir else "$dir/")

    /** [path] — защищённый путь, лежит внутри него или выше него (удаление задело бы защищённый). */
    fun isSystemPath(path: String): Boolean {
        if (path.isEmpty()) return false
        val p = path.trimEnd('/').ifEmpty { "/" }
        return PROTECTED.any { under(p, it) || under(it, p) }
    }

    // Только сами эти пути (<n> — номер пользователя); содержимое удалять можно.
    private val APP_DATA_ROOTS = Regex("/data/(data|user|user_de)|/data/(user|user_de)/[0-9]+")
    private val STORAGE_ROOTS = Regex("/data/media(/[0-9]+)?|/storage/emulated(/[0-9]+)?")

    /** Причина запрета, если [path] — ровно корень данных приложений или хранилища пользователя. */
    fun exactBlockReason(path: String): String? {
        val p = path.replace(Regex("/+"), "/").trimEnd('/')
        return when {
            APP_DATA_ROOTS.matches(p) -> ALL_APP_DATA
            STORAGE_ROOTS.matches(p) -> USER_STORAGE
            else -> null
        }
    }

    /**
     * Причина запрета или null — можно удалять. [scanRoot] — сам корень скана; [parentIsRoot] — прямой
     * потомок корня скана ([sessionRoot] — путь корня).
     */
    fun blockReason(path: String, scanRoot: Boolean, parentIsRoot: Boolean, sessionRoot: String,
                    flags: Int, kind: Kind): String? = when {
        flags and F_OTHERFS != 0 -> OTHER_FS
        scanRoot -> SYSTEM
        parentIsRoot && sessionRoot.trimEnd('/').isEmpty() -> SYSTEM
        isSystemPath(path) -> SYSTEM
        else -> exactBlockReason(path)
            ?: if (kind == Kind.INDEX && flags and F_DIR != 0) INDEX_DIR else null
    }

    private const val GIB = 1L shl 30

    /** Серьёзное удаление: кнопка «Удалить» включается не сразу. */
    fun needsPause(viaRoot: Boolean, owned: Boolean, disk: Long): Boolean =
        (viaRoot && owned) || disk >= GIB
}
