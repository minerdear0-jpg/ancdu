package dev.ancdu

/** Причина запрета удаления; текст — ресурс [res]. */
enum class Block(val res: Int) {
    SYSTEM(R.string.block_system),
    OTHER_FS(R.string.block_other_fs),
    ALL_APP_DATA(R.string.block_all_app_data),
    USER_STORAGE(R.string.block_user_storage),
    SYSTEM_DIR(R.string.block_system_dir),
    ANDROID_DIR(R.string.block_android_dir),
    /**
     * Каталог устаревшего дерева (кэш, индекс): экран сначала сам обновляет дерево; эту причину
     * лист показывает, только если обновить не вышло (скан не удался, su отказал).
     */
    REFRESH_FAILED(R.string.block_refresh_failed),
    NO_FAST(R.string.block_no_fast),
}

/**
 * Ярус листа удаления: пауза до «Удалить» ([pauseMs]) и удаление через su ([root] — ARM_ROOT,
 * предупреждение root, COMMIT_ROOT).
 */
enum class DeleteTier(val pauseMs: Long, val root: Boolean) {
    NONE(0L, false),
    PAUSE(DeletePolicy.PAUSE_MS, false),
    ROOT(DeletePolicy.ROOT_PAUSE_MS, true),
}

/** Что удалять нельзя никогда. Чистый Kotlin: решение по пути, флагам узла и виду дерева. */
object DeletePolicy {
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
    fun dataBlockReason(path: String): Block? {
        val p = normalize(path)
        if (p != "/data" && !p.startsWith("/data/")) return null
        // «.»/«..» в пути дерево не даёт; если встретились — список не доказывает ничего.
        if (p.split('/').any { it == "." || it == ".." }) return Block.SYSTEM_DIR
        return if (DATA_ALLOWED.matches(p)) null else Block.SYSTEM_DIR
    }

    /**
     * Причина запрета, если [path] — ровно корень данных приложений, хранилища пользователя
     * или его папка Android (её содержимое, например Android/data/<pkg>, удалять можно).
     */
    fun exactBlockReason(path: String): Block? {
        val p = normalize(path)
        return when {
            APP_DATA_ROOTS.matches(p) -> Block.ALL_APP_DATA
            STORAGE_ROOTS.matches(p) -> Block.USER_STORAGE
            ANDROID_ROOTS.matches(p) -> Block.ANDROID_DIR
            else -> null
        }
    }

    /**
     * Причина запрета или null — можно удалять. [scanRoot] — сам корень скана; [parentIsRoot] — прямой
     * потомок корня скана ([sessionRoot] — путь корня).
     */
    fun blockReason(path: String, scanRoot: Boolean, parentIsRoot: Boolean, sessionRoot: String,
                    flags: Int, kind: Kind): Block? = when {
        flags and F_OTHERFS != 0 -> Block.OTHER_FS
        scanRoot -> Block.SYSTEM
        parentIsRoot && sessionRoot.trimEnd('/').isEmpty() -> Block.SYSTEM
        isSystemPath(path) -> Block.SYSTEM
        else -> exactBlockReason(path) ?: dataBlockReason(path) ?: when {
            flags and F_DIR == 0 -> null
            // Каталог в кэше — содержимое на диске могло измениться после скана; индекс видит не все файлы.
            kind == Kind.CACHE || kind == Kind.INDEX -> Block.REFRESH_FAILED
            else -> null
        }
    }

    /**
     * Повторная проверка объекта группы к его началу (на io, после поиска в живом дереве): [blockReason]
     * узла ([parentIsRoot] — его родитель — корень дерева), затем, если выбран [fast], — разрешён ли
     * сопоставленный путь /data/media и есть ли su ([su] — только тогда и спрашивается). null — отправить.
     */
    fun itemBlock(path: String, parentIsRoot: Boolean, sessionRoot: String, flags: Int, kind: Kind, fast: Boolean,
                  su: () -> Boolean): Block? =
        blockReason(path, false, parentIsRoot, sessionRoot, flags, kind)
            ?: if (fast && (fastBlockReason(path) != null || !su())) Block.NO_FAST else null

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
    fun fastBlockReason(path: String): Block? {
        val m = mediaPath(path) ?: return Block.NO_FAST
        return if (isSystemPath(m)) Block.SYSTEM else exactBlockReason(m) ?: dataBlockReason(m)
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

    /**
     * Через root хелпер не открыл путь — ничего не удалено, но это не отказ su (Root.denied не
     * нужен): -EACCES — нет доступа к родителю или удаление не началось (выход 8), -ENOTDIR —
     * родитель не каталог (выход 10). Без root эти коды даёт сам rm_tree — возможно частично.
     */
    fun rootPathRefused(r: Int, viaRoot: Boolean): Boolean = viaRoot && (r == -EACCES || r == -ENOTDIR)

    private const val EACCES = 13
    private const val ENOTDIR = 20

    private const val GIB = 1L shl 30

    /**
     * Серьёзное удаление — кнопка «Удалить» включается не сразу: от root, данные другого
     * приложения ([owned]) или от 1 ГиБ.
     */
    fun needsPause(viaRoot: Boolean, owned: Boolean, disk: Long): Boolean =
        viaRoot || owned || disk >= GIB

    const val PAUSE_MS = 1500L
    const val ROOT_PAUSE_MS = 2500L

    /**
     * Пауза до «Удалить» (мс): от root — [ROOT_PAUSE_MS], прочее серьёзное — [PAUSE_MS], иначе 0.
     * [owned] — путь принадлежит другому приложению (данные, Android/data/<pkg>).
     */
    fun pauseMs(viaRoot: Boolean, owned: Boolean, disk: Long): Long = when {
        viaRoot -> ROOT_PAUSE_MS
        needsPause(viaRoot = false, owned = owned, disk = disk) -> PAUSE_MS
        else -> 0L
    }

    /**
     * Ярус листа: [viaRoot] — root-сессия, [fast] — отмечено «быстро через root» (удаление тоже
     * через su). Пауза — как у [pauseMs] от viaRoot || fast.
     */
    fun tier(viaRoot: Boolean, fast: Boolean, owned: Boolean, disk: Long): DeleteTier =
        when (pauseMs(viaRoot || fast, owned, disk)) {
            ROOT_PAUSE_MS -> DeleteTier.ROOT
            PAUSE_MS -> DeleteTier.PAUSE
            else -> DeleteTier.NONE
        }

    /** Сумма размеров без переполнения; отрицательные (нет данных) не вычитаются. */
    fun sum(sizes: List<Long>): Long {
        var s = 0L
        for (v in sizes) { if (v <= 0) continue; s = if (s > Long.MAX_VALUE - v) Long.MAX_VALUE else s + v }
        return s
    }

    /**
     * Ярус листа группы: строжайшее правило — одного чужого ([owned]) хватает на паузу, размер —
     * СУММА [disks] (20 × 60 МиБ ≥ 1 ГиБ — пауза). root — как у [tier].
     */
    fun groupTier(viaRoot: Boolean, fast: Boolean, owned: List<Boolean>, disks: List<Long>): DeleteTier =
        tier(viaRoot, fast, owned.any { it }, sum(disks))

    /** Числа обратного отсчёта паузы [ms] по порядку показа (2500 → 3, 2, 1). */
    fun countdown(ms: Long): List<Long> = ((ms + 999) / 1000 downTo 1L).toList()
}
