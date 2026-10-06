package dev.ancdu

/** Что удалять нельзя никогда. Чистый Kotlin: решение по пути, флагам узла и виду дерева. */
object DeletePolicy {
    const val SYSTEM = "системный путь — удаление отключено"
    const val OTHER_FS = "другая файловая система — удаление отключено"
    const val ALL_APP_DATA = "удаляет данные всех приложений — удаление отключено"
    const val USER_STORAGE = "всё хранилище пользователя — удаление отключено"
    const val SYSTEM_DIR = "системный каталог — удаление отключено"
    const val ANDROID_DIR = "служебная папка Android — удаление отключено"
    /**
     * Каталог устаревшего дерева (кэш, индекс): экран сначала сам обновляет дерево; эту причину
     * лист показывает, только если обновить не вышло (скан не удался, su отказал).
     */
    const val REFRESH_FAILED = "дерево не обновилось — каталог не удалить"

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
    private val ANDROID_ROOTS = Regex("/data/media/[0-9]+/Android|/storage/emulated/[0-9]+/Android")

    // Под /data удалять можно только это: данные приложения /data/data/<pkg>,
    // /data/user(_de)/<n>/<pkg>, содержимое /data/media/<n>/ и /data/local/tmp/.
    private val DATA_ALLOWED = Regex(
        "/data/data/[^/]+(/.*)?|/data/(user|user_de)/[0-9]+/[^/]+(/.*)?|" +
            "/data/media/[0-9]+/[^/]+(/.*)?|/data/local/tmp/[^/]+(/.*)?")

    private fun normalize(path: String) = path.replace(Regex("/+"), "/").trimEnd('/')

    /**
     * Причина запрета для пути под /data вне списка разрешённого, иначе null. Применяется в
     * любом режиме: скан общего хранилища без root таких путей не даёт, а root-сессия или кэш
     * root-скана — ровно то, от чего защищаемся.
     */
    fun dataBlockReason(path: String): String? {
        val p = normalize(path)
        if (p != "/data" && !p.startsWith("/data/")) return null
        // «.»/«..» в пути дерево не даёт; если встретились — список не доказывает ничего.
        if (p.split('/').any { it == "." || it == ".." }) return SYSTEM_DIR
        return if (DATA_ALLOWED.matches(p)) null else SYSTEM_DIR
    }

    /**
     * Причина запрета, если [path] — ровно корень данных приложений, хранилища пользователя
     * или его папка Android (её содержимое, например Android/data/<pkg>, удалять можно).
     */
    fun exactBlockReason(path: String): String? {
        val p = normalize(path)
        return when {
            APP_DATA_ROOTS.matches(p) -> ALL_APP_DATA
            STORAGE_ROOTS.matches(p) -> USER_STORAGE
            ANDROID_ROOTS.matches(p) -> ANDROID_DIR
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
        else -> exactBlockReason(path) ?: dataBlockReason(path) ?: when {
            flags and F_DIR == 0 -> null
            // Каталог в кэше — содержимое на диске могло измениться после скана; индекс видит не все файлы.
            kind == Kind.CACHE || kind == Kind.INDEX -> REFRESH_FAILED
            else -> null
        }
    }

    const val NO_FAST = "путь нельзя сопоставить с /data/media"

    /**
     * Быстрый путь root в обход FUSE: /storage/emulated/<n>/X → /data/media/<n>/X, иначе null.
     * <n> — только ASCII-цифры; X непуст, без пустых компонентов, «.» и «..» (и без «/» в конце).
     * Те же правила, что у media_path в ядре (session_root.c).
     */
    fun mediaPath(path: String): String? {
        val pre = "/storage/emulated/"
        if (!path.startsWith(pre)) return null
        val tail = path.substring(pre.length)
        val slash = tail.indexOf('/')
        if (slash <= 0) return null
        val user = tail.substring(0, slash)
        if (!user.all { it in '0'..'9' }) return null
        val rest = tail.substring(slash + 1)
        if (rest.split('/').any { it.isEmpty() || it == "." || it == ".." }) return null
        return "/data/media/$user/$rest"
    }

    /**
     * Причина запрета быстрого удаления через /data/media или null. Исходный путь проверяет
     * [blockReason]; здесь — что сопоставленный путь сам разрешён: строго внутри
     * /data/media/<n>/, не /data/media/<n> и не /data/media/<n>/Android.
     */
    fun fastBlockReason(path: String): String? {
        val m = mediaPath(path) ?: return NO_FAST
        return if (isSystemPath(m)) SYSTEM else exactBlockReason(m) ?: dataBlockReason(m)
    }

    /**
     * Галочка «быстро через root» включена по умолчанию от 1000 элементов — и только если root
     * уже выдан ([RootState.GRANTED]); неизвестно или отказ (-EPERM) — выключена.
     */
    fun fastByDefault(items: Long, root: RootState): Boolean = root == RootState.GRANTED && items >= 1000

    private const val EPERM = 1

    /**
     * Неудачное удаление с кодом [r] < 0 ничего не удалило: через root -EPERM значит, что хелпер
     * до удаления не дошёл (su отказал или не запустился, хелпер не найден). -EIO и прочие
     * коды (и любой код без root) — удаление могло пройти частично.
     */
    fun nothingDeleted(r: Int, viaRoot: Boolean): Boolean = viaRoot && r == -EPERM

    private const val GIB = 1L shl 30

    /** Серьёзное удаление: кнопка «Удалить» включается не сразу. */
    fun needsPause(viaRoot: Boolean, owned: Boolean, disk: Long): Boolean =
        (viaRoot && owned) || disk >= GIB
}
