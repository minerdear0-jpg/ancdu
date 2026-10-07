package dev.ancdu

import java.util.Locale
import java.util.PriorityQueue

/** Чистые правила превью в листе удаления: что показывать и где. */
object SheetPeek {
    /** Квадрат превью строки листа. */
    const val THUMB_DP = 40
    /** Место превью листа одного файла. */
    const val BOX_DP = 120

    /** Строке листа — квадрат превью: картинка, видео, значок APK. */
    fun thumb(kind: PeekKind): Boolean = kind == PeekKind.IMAGE || kind == PeekKind.VIDEO || kind == PeekKind.APK

    /** Знак вида в пустом квадрате (моно 10sp): превью нет или ещё не пришло. */
    fun glyph(kind: PeekKind): String? = when (kind) {
        PeekKind.IMAGE -> "IMG"
        PeekKind.VIDEO -> "VID"
        PeekKind.APK -> "APK"
        else -> null
    }

    /** «УДАЛИТЬ…» и «ВЫБРАТЬ» карточки: над листом их нет — действие у листа. */
    fun cardActions(fromSheet: Boolean): Boolean = !fromSheet

    /**
     * Место превью 120dp листа: один файл (не каталог, не группа) с видом превью; лист открыт не
     * из карточки (превью там уже видели).
     */
    fun selfBox(fromCard: Boolean, group: Boolean, dir: Boolean, kind: PeekKind): Boolean =
        !fromCard && !group && !dir && kind != PeekKind.NONE
}

/**
 * «Контактный лист» каталога в листе удаления: до [K] крупнейших картинок и видео поддерева. Обход
 * по убыванию размера (слияние уже отсортированных списков детей): каталог не меньше любого своего
 * потомка, поэтому файлы выходят строго по убыванию, и обход кончается, как только найдено [K].
 * [BUDGET] — сколько узлов можно посмотреть; кончился — что нашли.
 */
object ContactSheet {
    const val K = 4
    const val BUDGET = 1024
    /** Детей одного каталога, которые смотрит обход (крупнейшие). */
    const val KIDS_CAP = 256

    class Kid(val id: Int, val disk: Long, val flags: Int)

    private val EXT = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "bmp",
        "mp4", "m4v", "mkv", "mov", "webm", "3gp")

    /** Картинка или видео по расширению (без учёта регистра). */
    fun media(name: String): Boolean = Ellipsis.ext(name)?.lowercase(Locale.ROOT) in EXT

    /** Позиция в списке детей одного каталога. */
    private class Cursor(val list: List<Kid>, var i: Int) {
        val head: Kid get() = list[i]
    }

    /**
     * Крупнейшие медиафайлы под [root]: [kids] — дети узла по убыванию disk (без удалённых),
     * [name] — имя файла. Пропускаются ссылки, другая ФС, повторные жёсткие ссылки и пустые.
     */
    fun pick(root: Int, kids: (Int) -> List<Kid>, name: (Int) -> String, k: Int = K, budget: Int = BUDGET): List<Kid> {
        val out = ArrayList<Kid>(k)
        if (k <= 0) return out
        val heap = PriorityQueue<Cursor>(compareByDescending<Cursor> { it.head.disk }.thenBy { it.head.id })
        kids(root).takeIf { it.isNotEmpty() }?.let { heap += Cursor(it, 0) }
        var left = budget
        while (out.size < k && left > 0) {
            val c = heap.poll() ?: break
            val kid = c.head
            left--
            if (++c.i < c.list.size) heap += c
            when {
                kid.flags and (F_SYMLINK or F_OTHERFS) != 0 -> {}
                kid.flags and F_DIR != 0 -> kids(kid.id).takeIf { it.isNotEmpty() }?.let { heap += Cursor(it, 0) }
                kid.flags and F_HLDUP != 0 || kid.disk <= 0 -> {}
                media(name(kid.id)) -> out += kid
            }
        }
        return out
    }
}

/** Раскадровка видео в карточке: 5 кадров на 0/20/40/60/80% длительности. */
object Storyboard {
    const val FRAMES = 5
    /** Бюджет первого кадра и всей ленты. */
    const val FIRST_MS = 1500L
    const val STRIP_MS = 4000L
    /** Высота ленты кадров. */
    const val STRIP_DP = 56

    /** Моменты кадров в микросекундах (getFrameAtTime); длительность 0 или неизвестна — один кадр с начала. */
    fun times(durMs: Long?): LongArray =
        if (durMs == null || durMs <= 0) longArrayOf(0)
        else LongArray(FRAMES) { durMs / FRAMES * 1000 * it + durMs % FRAMES * 1000 * it / FRAMES }

    /** «0:42 · 1920×1080»; поворот 90/270 — размеры как на экране; неизвестные части пропускаются. */
    fun meta(durMs: Long?, w: Int, h: Int, rotation: Int): String? {
        val turned = rotation % 180 != 0
        val dims = if (w > 0 && h > 0) (if (turned) Peek.dims(h, w) else Peek.dims(w, h)) else null
        return Peek.meta(listOf(durMs?.takeIf { it > 0 }?.let { Peek.duration(it) }, dims)).ifEmpty { null }
    }

    /** Время плеера: «0:12 / 0:42». */
    fun clock(posMs: Long, durMs: Long): String = Peek.duration(posMs) + " / " + Peek.duration(durMs)
}
