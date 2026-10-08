package dev.ancdu

/** Причина ошибки узла (повторная попытка открыть его при открытии листа ошибок). */
enum class ErrReason(val res: Int, val fileRes: Int = res) {
    NO_ACCESS(R.string.err_no_access),
    GONE(R.string.err_gone, R.string.err_gone_file),
    SYMLINK(R.string.err_symlink),
    NOT_DIR(R.string.err_not_dir),
    /** Прочий errno: «ошибка чтения (<имя>)». */
    OTHER(R.string.err_other),
    /** Открылась теперь: удаление прервано или папка менялась во время скана. */
    INCOMPLETE(R.string.err_incomplete, R.string.err_incomplete_file),
    /** Root-дерево: повторить можно только в пространстве имён su — не повторяем. */
    NOT_READ(R.string.err_not_read),
}

/**
 * Чистый Kotlin: тексты и правила листа ошибок скана. Узлы с ошибкой даёт Native.errorNodes (F_ERR:
 * каталог не открылся или не дочитался при скане, частичное удаление).
 */
object ScanErrors {
    /** Строк в листе не больше; дальше — «…ещё N». */
    const val CAP = 200

    // errno Linux: одни и те же на arm64 и x86_64.
    const val EPERM = 1
    const val ENOENT = 2
    const val EACCES = 13
    const val ENOTDIR = 20
    const val ELOOP = 40

    /** Причина по errno повторного open (0 — открылась). */
    fun reason(errno: Int): ErrReason = when (errno) {
        0 -> ErrReason.INCOMPLETE
        EACCES, EPERM -> ErrReason.NO_ACCESS
        ENOENT -> ErrReason.GONE
        ELOOP -> ErrReason.SYMLINK
        ENOTDIR -> ErrReason.NOT_DIR
        else -> ErrReason.OTHER
    }

    /**
     * Текст причины; для [ErrReason.OTHER] — имя errno [name] (нет — «errno N»). Узел-файл ([dir] false,
     * F_ERR после частичного удаления) — слова про файл («файла уже нет»).
     */
    fun text(t: Txt, r: ErrReason, name: String?, errno: Int = 0, dir: Boolean = true): String {
        val id = if (dir) r.res else r.fileRes
        return if (r == ErrReason.OTHER) t.s(id, name ?: "errno $errno") else t.s(id)
    }

    private fun digits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

    /**
     * [path] — <хранилище>/Android/data или Android/obb либо узел под ними: с Android 11 эти папки
     * закрыты для всех приложений, даже с доступом ко всем файлам. Хранилище —
     * /storage/emulated/<n>, том /storage/<id> (не emulated и не self) и /sdcard. Сегменты целиком;
     * регистр не важен (FUSE общего хранилища его не различает).
     */
    fun androidPrivate(path: String): Boolean {
        val s = path.split('/').filter { it.isNotEmpty() }
        val l = when {
            s.size > 2 && s[0] == "storage" && s[1] == "emulated" && digits(s[2]) -> 3
            s.size > 1 && s[0] == "storage" && s[1] != "emulated" && s[1] != "self" -> 2
            s.isNotEmpty() && s[0] == "sdcard" -> 1
            else -> return false
        }
        return s.size > l + 1 && s[l].equals("Android", ignoreCase = true) &&
            (s[l + 1].equals("data", ignoreCase = true) || s[l + 1].equals("obb", ignoreCase = true))
    }

    /**
     * Root-скан «/data/media» (кнопка «СКАНИРОВАТЬ ОТ ROOT») покрывает [path]: внутренняя память —
     * /storage/emulated/<n> или /sdcard. Съёмные тома /storage/<id> — нет (только заметка).
     */
    fun rootScanCovers(path: String): Boolean {
        val s = path.split('/').filter { it.isNotEmpty() }
        return s.size > 2 && s[0] == "storage" && s[1] == "emulated" && digits(s[2]) ||
            s.isNotEmpty() && s[0] == "sdcard"
    }

    /** Показать заметку «Android 11+ закрывает эту папку…»: закрытая папка и дерево не от root (FUSE). */
    fun androidNote(path: String, viaRoot: Boolean): Boolean = !viaRoot && androidPrivate(path)

    /**
     * Причина в строке: под заметкой о закрытой папке — «нет доступа», даже если она открылась теперь
     * (FUSE может отдать её пустой); прочие причины — как есть.
     */
    fun shown(r: ErrReason, note: Boolean): ErrReason =
        if (note && r == ErrReason.INCOMPLETE) ErrReason.NO_ACCESS else r

    /** «…ещё N» под строками листа или null, если показаны все. */
    fun more(t: Txt, total: Int, shown: Int): String? =
        if (total > shown) t.s(R.string.more_children, Fmt.count((total - shown).toLong(), t.locale)) else null

    /** Путь от корня дерева [root]; сам корень — [rootTitle]; не под корнем — полный путь. */
    fun relative(path: String, root: String, rootTitle: String): String {
        val r = root.trimEnd('/')
        return when {
            path == root || path == r -> rootTitle
            path.startsWith("$r/") -> path.substring(r.length + 1)
            else -> path
        }
    }

    /** Ссылка подвала «⚠ 2 ошибки ›». */
    fun link(t: Txt, n: Int): String = "⚠ " + t.q(R.plurals.errors, n.toLong(), Fmt.count(n.toLong(), t.locale)) + " ›"

    /** Её описание для TalkBack: «Ошибки сканирования: 2. Открыть список». */
    fun linkDesc(t: Txt, n: Int): String = t.s(R.string.scan_errors_desc, Fmt.count(n.toLong(), t.locale))
}
