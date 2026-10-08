package dev.ancdu

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Состояние браузера, которое переживает смерть процесса: папка — по ИМЕНАМ (байты, цепочка
 * [Focus.encode]), корень и режим su дерева, время сохранения, сортировка, режим размера,
 * прокрутка, «гиганты» и курсор «вы были здесь». Дескриптор и id узлов после смерти процесса
 * ничего не значат — их здесь нет. Чистый Kotlin.
 */
object BrowserState {
    /** Старше — не восстанавливать (обычный старт). */
    const val MAX_AGE_MS = 24 * 3_600_000L
    private const val VERSION: Byte = 1

    /**
     * Метка процесса: состояние того же процесса — обычное пересоздание (дескриптор жив, ключ h/gen);
     * чужого — после смерти процесса (восстановление по именам).
     */
    @Volatile var process: Long = newToken()
        private set

    private fun newToken(): Long = java.security.SecureRandom().nextLong()

    /** Для тестов: «новый процесс» — прежние состояния становятся состояниями умершего. */
    fun testNewProcess() { process = newToken() }

    /** Ключ состояния в Bundle браузера. */
    const val KEY = "state"

    /**
     * Для тестов: вызывается один раз в начале onCreate с сохранённым Bundle — симуляция смерти процесса
     * (Holder пуст, новая метка процесса; Bundle можно состарить).
     */
    @Volatile var testProcessDeath: ((android.os.Bundle) -> Unit)? = null

    /** Курсор «вы были здесь»: объект по цепочке имён от корня, его строка тогда, [quiet] — пропал после удаления (без заметки). */
    class Cursor(val chain: List<ByteArray>, val index: Int, val quiet: Boolean)

    class Saved(
        val process: Long,
        val root: String,
        val su: Boolean,
        val savedAt: Long,
        val giants: Boolean,
        val sort: Int,
        val apparent: Boolean,
        val scroll: Int,
        val chain: List<ByteArray>,
        val cursor: Cursor?,
    )

    fun encode(s: Saved): ByteArray = ByteArrayOutputStream().also { bo ->
        DataOutputStream(bo).apply {
            writeByte(VERSION.toInt())
            writeLong(s.process)
            bytes(s.root.toByteArray(Charsets.UTF_8))
            writeBoolean(s.su)
            writeLong(s.savedAt)
            writeBoolean(s.giants)
            writeInt(s.sort)
            writeBoolean(s.apparent)
            writeInt(s.scroll)
            bytes(Focus.encode(s.chain))
            val c = s.cursor
            writeBoolean(c != null)
            if (c != null) { bytes(Focus.encode(c.chain)); writeInt(c.index); writeBoolean(c.quiet) }
            flush()
        }
    }.toByteArray()

    private fun DataOutputStream.bytes(b: ByteArray) { writeInt(b.size); write(b) }

    /** Состояние или null: нет, другая версия, обрезано, лишний хвост, негодная цепочка имён. */
    fun decode(b: ByteArray?): Saved? {
        if (b == null || b.isEmpty() || b[0] != VERSION) return null
        return try {
            val inp = DataInputStream(ByteArrayInputStream(b, 1, b.size - 1))
            val process = inp.readLong()
            val root = String(inp.bytes(), Charsets.UTF_8)
            val su = inp.readBoolean()
            val at = inp.readLong()
            val giants = inp.readBoolean()
            val sort = inp.readInt()
            val apparent = inp.readBoolean()
            val scroll = inp.readInt()
            val chain = chain(inp.bytes()) ?: return null
            val cursor = if (inp.readBoolean()) {
                val cc = chain(inp.bytes())?.takeIf { it.isNotEmpty() } ?: return null
                Cursor(cc, inp.readInt(), inp.readBoolean())
            } else null
            if (inp.read() != -1) return null
            Saved(process, root, su, at, giants, sort, apparent, scroll, chain, cursor)
        } catch (e: java.io.IOException) {
            null
        }
    }

    private fun DataInputStream.bytes(): ByteArray {
        val n = readInt()
        if (n < 0 || n > Focus.MAX_BYTES) throw java.io.IOException("bad length $n")
        return ByteArray(n).also { readFully(it) }
    }

    /** Пусто — корень; иначе [Focus.parse] (null — негодная цепочка). */
    private fun chain(b: ByteArray): List<ByteArray>? = if (b.isEmpty()) emptyList() else Focus.parse(b)

    /** Сортировка из состояния: только известная браузеру (размер, имя, Δ), иначе — по размеру. */
    fun sortOf(s: Int): Int = if (s == SORT_SIZE || s == SORT_NAME || s == SORT_DELTA) s else SORT_SIZE

    /** Курсор в Bundle отдельно от состояния: пишется и во время удаления (дерево не читается). */
    fun putCursor(out: android.os.Bundle, c: Cursor?) {
        if (c == null) return
        out.putByteArray(K_CUR, Focus.encode(c.chain)); out.putInt(K_CUR_I, c.index); out.putBoolean(K_CUR_Q, c.quiet)
    }

    fun getCursor(st: android.os.Bundle): Cursor? {
        val chain = Focus.parse(st.getByteArray(K_CUR)) ?: return null
        return Cursor(chain, st.getInt(K_CUR_I, 0), st.getBoolean(K_CUR_Q, false))
    }

    private const val K_CUR = "cur"
    private const val K_CUR_I = "cur_i"
    private const val K_CUR_Q = "cur_q"

    /** Состояние не старше суток (и не «из будущего»: часы перевели назад). */
    fun fresh(savedAt: Long, now: Long): Boolean = now - savedAt in 0..MAX_AGE_MS

    /** Живое дерево Holder: его корень и режим su. */
    class Live(val root: String, val su: Boolean)

    enum class Restore {
        /** Живое дерево того же корня и режима su — по именам в нём. */
        LIVE,
        /** Дерева нет — открыть кэш этого корня (как карточка главного), затем по именам. */
        CACHE,
        /** Обычный старт. */
        NONE,
    }

    /**
     * Что делать с состоянием [s] умершего процесса. [live] — дерево Holder (null — нет), [cacheRoot] —
     * корень записи кэша для (s.root, s.su) (null — кэша нет), [rootExists] — том корня на месте,
     * [deleting] — идёт удаление, [giants] — экран открыт «гигантами».
     */
    fun decide(s: Saved, now: Long, live: Live?, cacheRoot: String?, rootExists: Boolean, deleting: Boolean,
               giants: Boolean): Restore = when {
        deleting || !fresh(s.savedAt, now) || s.giants != giants || !rootExists -> Restore.NONE
        live != null -> if (live.root == s.root && live.su == s.su) Restore.LIVE else Restore.NONE
        cacheRoot == s.root -> Restore.CACHE
        else -> Restore.NONE
    }
}

/** Курсор «вы были здесь»: строка, соседняя строка, заметка и вспышка. Чистый Kotlin. */
object CursorPlace {
    /** Строка курсора: найденная ([found] ≥ 0) или на месте пропавшего ([index] в пределах [n]); -1 — строк нет. */
    fun row(found: Int, index: Int, n: Int): Int = when {
        found >= 0 -> found
        n <= 0 -> -1
        else -> index.coerceIn(0, n - 1)
    }

    /** Заметка «уже нет на диске»: объект пропал, и не своим удалением. */
    fun noteGone(found: Int, quiet: Boolean): Boolean = found < 0 && !quiet

    /** Курсор [chain] — ребёнок папки [folder] (по байтам имён). */
    fun inFolder(chain: List<ByteArray>, folder: List<ByteArray>): Boolean =
        chain.size == folder.size + 1 && folder.indices.all { chain[it].contentEquals(folder[it]) }

    /**
     * Амберные акценты экрана, кроме вспышки: ссылка «⚠ N» (с ⚠ у строк — один факт), чип «новее» с
     * заливкой, амберная плашка Δ. Выбранный сегмент сортировки — состояние переключателя, не акцент.
     */
    fun accents(errors: Boolean, newerFilled: Boolean, badgeAmber: Boolean): Int =
        (if (errors) 1 else 0) + (if (newerFilled) 1 else 0) + (if (badgeAmber) 1 else 0)

    /** Вспышка 1,5 с: анимации разрешены и с ней акцентов не больше двух. */
    fun flash(motion: Boolean, accents: Int): Boolean = motion && accents < 2

    const val FLASH_MS = 1500L
}

/**
 * Сколько держать заметку с действием: 4 с; дольше, если система советует ([recommended] —
 * AccessibilityManager.getRecommendedTimeoutMillis) или говорит TalkBack ([spoken]: не меньше
 * [SPOKEN_MS] — успеть дослушать и дотянуться). Чистый Kotlin.
 */
object NoticeTime {
    const val BASE_MS = 4000L
    const val SPOKEN_MS = 10_000L
    fun ms(recommended: Int, spoken: Boolean = false): Long =
        maxOf(BASE_MS, recommended.toLong(), if (spoken) SPOKEN_MS else 0L)
}
