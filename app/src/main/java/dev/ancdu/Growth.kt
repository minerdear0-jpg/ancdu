package dev.ancdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.TimeZone

/** «Ушло» в каталоге: сколько его прямых детей базы не нашлось и их размеры в базе. */
class Gone(val count: Int, val disk: Long, val apparent: Long) {
    fun bytes(apparent: Boolean): Long = if (apparent) this.apparent else disk
}

/**
 * Δ дерева [h] поколения [gen] против точки отсчёта (время скана [baseTime], файл [baseBytes] байт):
 * по узлу — Δ на диске и видимого и состояние DELTA_*; по каталогу — «ушло». Массивы не меняются
 * после публикации (читаются с главного потока и с io). [ms] — время расчёта на io.
 */
class Delta(val h: Long, val gen: Long, private val disk: LongArray, private val app: LongArray,
            private val st: ByteArray, val gone: Map<Int, Gone>, val baseTime: Long, val baseBytes: Long,
            val ms: Long) {
    /** Δ узла известна: узел в дереве и не удалён (DELTA_DEAD — удалённое не показывается). */
    fun known(nd: Int): Boolean = nd in st.indices && st[nd].toInt() != DELTA_DEAD

    fun of(nd: Int, apparent: Boolean): Long = if (!known(nd)) 0L else if (apparent) app[nd] else disk[nd]

    fun isNew(nd: Int): Boolean = nd in st.indices && st[nd].toInt() == DELTA_NEW

    companion object {
        /** «Ушло» из плоского [node, count, disk, apparent]… (Native.delta). */
        fun goneMap(flat: LongArray): Map<Int, Gone> {
            val m = HashMap<Int, Gone>(flat.size / 2)
            for (i in 0 until flat.size / 4)
                m[flat[4 * i].toInt()] = Gone(flat[4 * i + 1].toInt(), flat[4 * i + 2], flat[4 * i + 3])
            return m
        }
    }
}

/** Чистый Kotlin: тексты «что выросло». */
object GrowthText {
    private const val DAY = 86_400_000L

    /** «+1,7 ГиБ», «−84,0 МиБ», «±0»: знак, затем размер как у [Fmt.size]. */
    fun signed(b: Long, t: Txt): String = when {
        b > 0 -> "+" + Fmt.size(b, t)
        b < 0 -> "−" + Fmt.size(if (b == Long.MIN_VALUE) Long.MAX_VALUE else -b, t)
        else -> "±0"
    }

    /** Цвет Δ: рост — AMBER_TEXT, сжатие — MUTED, без изменений — TEXT. */
    fun role(b: Long): Role = when { b > 0 -> Role.AMBER_TEXT; b < 0 -> Role.MUTED; else -> Role.TEXT }

    /** «1 окт.» / «Oct 1». */
    fun since(t: Txt, ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        Freshness.date(t, R.string.fmt_since, ms, tz)

    /** Плашка браузера в сортировке Δ: «Δ с 1 окт. 09:12» / «Δ vs Oct 1 09:12». */
    fun badge(t: Txt, ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        t.s(R.string.badge_delta, Freshness.date(t, R.string.fmt_since_time, ms, tz))

    /** Сводка папки: «6,4 ГиБ · +1,9 ГиБ с 1 окт.». */
    fun summary(t: Txt, size: Long, delta: Long, base: Long, tz: TimeZone = TimeZone.getDefault()): String =
        t.s(R.string.growth_summary, Fmt.size(size, t), signed(delta, t), since(t, base, tz))

    /** «сегодня», «1 день назад», «11 дней назад»; время в будущем — «сегодня». */
    fun daysAgo(t: Txt, now: Long, time: Long): String {
        val d = maxOf(0L, now - time) / DAY
        return if (d == 0L) t.s(R.string.today) else t.q(R.plurals.days_ago, d, Fmt.count(d, t.locale))
    }

    /** Строка «ушло: 3 объекта · −120 МиБ». */
    fun gone(t: Txt, count: Int, bytes: Long): String =
        t.q(R.plurals.gone_items, count.toLong(), Fmt.count(count.toLong(), t.locale), signed(-bytes, t))

    /** [gone] или null — ничего не ушло (строки нет). */
    fun goneOrNull(t: Txt, count: Int, bytes: Long): String? = if (count <= 0) null else gone(t, count, bytes)

    private const val MIB = 1L shl 20

    /** Строка главного экрана видна: |Δ корня| не меньше 1 МиБ. */
    fun homeShown(delta: Long): Boolean = delta >= MIB || delta <= -MIB

    /** Цвет строки главного экрана: рост — AMBER_TEXT (касаемая, как «обновить ›»), сжатие — MUTED. */
    fun homeRole(delta: Long): Role = if (delta < 0) Role.MUTED else Role.AMBER_TEXT

    /** «+2,1 ГиБ с 1 окт. · больше всего Telegram/Video ›»; без [mostly] — «+2,1 ГиБ с 1 окт. ›». */
    fun home(t: Txt, delta: Long, base: Long, mostly: String?, tz: TimeZone = TimeZone.getDefault()): String =
        if (mostly.isNullOrEmpty()) t.s(R.string.growth_home_plain, signed(delta, t), since(t, base, tz))
        else t.s(R.string.growth_home, signed(delta, t), since(t, base, tz), mostly)

    /** TalkBack строки в сортировке Δ: «имя, +1 МиБ с 1 окт., сейчас 3 МиБ[, каталог][, новое]». */
    fun rowDesc(t: Txt, name: String, delta: Long, size: Long, base: Long, isNew: Boolean, dir: Boolean,
                tz: TimeZone = TimeZone.getDefault()): String = buildString {
        append(name); append(", ")
        append(t.s(R.string.growth_since, signed(delta, t), since(t, base, tz)))
        append(", "); append(t.s(R.string.desc_now, Fmt.size(size, t)))
        if (dir) { append(", "); append(t.s(R.string.desc_dir)) }
        if (isNew) { append(", "); append(t.s(R.string.desc_new)) }
    }
}

/**
 * Чистый Kotlin: «больше всего …» — самый глубокий каталог на пути наибольшей Δ, у которого Δ ≥ 50%
 * Δ корня: от корня вниз, пока ребёнок с наибольшей Δ (по знаку корня) — каталог и держит не меньше
 * половины. Пусто — Δ корня 0 или половину держит файл / никто.
 */
object Mostly {
    class Kid(val node: Int, val delta: Long, val dir: Boolean)

    /** Узлы пути от корня (без него); [kids] — дети узла с их Δ. Не глубже [maxDepth]. */
    fun path(rootDelta: Long, kids: (Int) -> List<Kid>, maxDepth: Int = 256): List<Int> {
        if (rootDelta == 0L) return emptyList()
        val sign = if (rootDelta > 0) 1L else -1L
        val whole = if (rootDelta == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(rootDelta)
        val out = ArrayList<Int>()
        var cur = 0
        while (out.size < maxDepth) {
            val best = kids(cur).maxByOrNull { it.delta * sign } ?: break
            val part = best.delta * sign
            // part ≥ whole / 2 без переполнения: part ≥ whole − part.
            if (!best.dir || part <= 0 || part < whole - part) break
            out += best.node
            cur = best.node
        }
        return out
    }
}

/** Строка «что выросло» главного экрана: Δ корня, время точки отсчёта, путь «больше всего» (байты имён и текст). */
class HomeGrowth(val delta: Long, val baseTime: Long, val names: List<ByteArray>, val path: String)

/** Чистый Kotlin: порядок и полоса в сортировке Δ. */
object GrowthSort {
    /**
     * Первые [n] id в [ids] — по Δ убыв. (рост первым), при равном Δ — по размеру убыв., затем меньший
     * id первым. Ключи читаются один раз на элемент.
     */
    fun sort(ids: IntArray, n: Int, delta: (Int) -> Long, size: (Int) -> Long) {
        if (n < 2) return
        val d = LongArray(n) { delta(ids[it]) }
        val s = LongArray(n) { size(ids[it]) }
        val idx = (0 until n).sortedWith { x, y ->
            when {
                d[x] != d[y] -> d[y].compareTo(d[x])
                s[x] != s[y] -> s[y].compareTo(s[x])
                else -> ids[x].compareTo(ids[y])
            }
        }
        val out = IntArray(n) { ids[idx[it]] }
        out.copyInto(ids)
    }

    /** Наибольший |Δ| (Long.MIN_VALUE — как Long.MAX_VALUE). */
    fun maxAbs(v: LongArray): Long = v.maxOfOrNull { if (it == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(it) } ?: 0L

    /** Знаковая полоса: [delta] / [maxAbs] в [-1, 1]; 0 — без полосы. */
    fun bar(delta: Long, maxAbs: Long): Float =
        if (maxAbs <= 0L) 0f else (delta.toDouble() / maxAbs).toFloat().coerceIn(-1f, 1f)
}

/**
 * Δ показанного дерева (Holder) против точки отсчёта его ключа. Состояние — главный поток; расчёт —
 * на Holder.io (FIFO с delete и free: дескриптор жив, дерево не меняется). Новое дерево приходит
 * через Holder.set ([onTree]); после удаления — пересчёт ([recompute]): до него Δ папок над
 * удалённым устарели на одно короткое окно (удалённые узлы не показываются вовсе).
 */
object Growth {
    private val main = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    @Volatile private var app: Context? = null

    /** Δ показанного дерева или null (нет точки отсчёта, индекс, ещё считается). Главный поток. */
    var current: Delta? = null
        private set
    /** Поколение дерева, для которого расчёт уже запрошен (повтор — только [recompute]). */
    private var reqGen = -1L
    private var seq = 0
    /** Поколение, расчёт для которого стоит на io (-1 — нет). */
    private var flight = -1L
    /** Для тестов: сколько расчётов закончено. */
    val computed = java.util.concurrent.atomic.AtomicInteger()

    /** Любой экран (LangActivity.onCreate): контекст приложения для каталога точек отсчёта. */
    fun init(ctx: Context) { if (app == null) app = ctx.applicationContext }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    /** Δ дерева [h] поколения [gen], если она посчитана именно для него. */
    fun forTree(h: Long, gen: Long): Delta? = current?.takeIf { it.h == h && it.gen == gen }

    /** Для дерева [h] поколения [gen] Δ сейчас считается на io (ответ ещё придёт). */
    fun pending(h: Long, gen: Long): Boolean = flight == gen && Holder.h == h && Holder.gen == gen

    /**
     * Главный поток, из Holder.set (до слушателей сессии: расчёт встаёт на io раньше их чтений). Дерево
     * готово (ST_DONE/ST_FULL), не индекс — Δ против точки отсчёта его ключа на io; Δ прежнего дерева
     * сбрасывается сразу (без уведомления: экраны старого дерева и так пересоздаются).
     */
    fun onTree(h: Long, gen: Long, kind: Kind, root: String, viaRoot: Boolean, force: Boolean = false) {
        if (current?.gen != gen || current?.h != h) current = null
        if (h == 0L || kind == Kind.INDEX) { reqGen = gen; flight = -1L; publish(null); return }
        if (!force && reqGen == gen) return
        val ctx = app ?: return
        val p = LongArray(6).also { Native.progress(h, it) }
        if (p[0] != ST_DONE.toLong() && p[0] != ST_FULL.toLong()) return
        reqGen = gen
        flight = gen
        val my = ++seq
        val files = Baseline.files(ctx, root, viaRoot)
        Holder.io.execute {
            val d = runCatching { compute(h, gen, files) }.onFailure { Log.w("ancdu", "delta failed", it) }.getOrNull()
            computed.incrementAndGet()
            main.post {
                if (my != seq) return@post
                flight = -1L
                // Ответ для уже сменённого дерева выбрасывается. Экраны узнают о конце расчёта в любом
                // случае: и «точки отсчёта нет» (null → null) снимает ожидание сортировки Δ.
                if (Holder.h == h && Holder.gen == gen) current = d
                tellListeners()
            }
        }
    }

    /** Главный поток: пересчитать Δ показанного дерева (после удаления, «Отметить сейчас»). */
    fun recompute() = onTree(Holder.h, Holder.gen, Holder.kind, Holder.root, Holder.viaRoot, force = true)

    /**
     * Главный поток. «Отметить сейчас»: точка отсчёта := показанное дерево (на io, Native.saveCache по
     * пути A — tmp + rename), кандидата нет; затем пересчёт — Δ ±0 везде. Во время удаления — ничего.
     */
    fun markNow(ctx: Context) {
        val h = Holder.h
        if (h == 0L || Holder.deleting || Holder.kind == Kind.INDEX) return
        val files = Baseline.files(ctx, Holder.root, Holder.viaRoot)
        val time = Holder.time
        Holder.io.execute {
            val r = runCatching { files.markFrom(time) { Native.saveCache(h, it) } }.getOrDefault(-1)
            if (r != 0) Log.w("ancdu", "mark baseline failed: $r")
        }
        recompute()
    }

    private fun publish(d: Delta?) {
        if (current === d) return
        current = d
        tellListeners()
    }

    private fun tellListeners() { for (l in listeners.toList()) l() }

    /**
     * На Holder.io: Δ дерева [h] против A ключа; null — точки отсчёта нет или она негодна. Другая
     * версия формата или повреждённый файл — точка отсчёта молча забывается (как кэш).
     */
    fun compute(h: Long, gen: Long, files: BaselineFiles): Delta? {
        if (!files.a.isFile) return null
        val n = Native.nodeCount(h)
        if (n <= 0) return null
        val dd = LongArray(n)
        val da = LongArray(n)
        val st = ByteArray(n)
        val err = IntArray(1)
        val t0 = System.nanoTime()
        var flat: LongArray?
        // Не больше двух попыток: A, затем повышенный на её место кандидат B.
        while (true) {
            flat = Native.delta(h, files.a.path, dd, da, st, err)
            if (flat != null) break
            Log.i("ancdu", "baseline rejected: ${err[0]}")
            // Другая версия формата или негодный файл — удаляется только A; годный кандидат B занимает
            // её место (и проверяется тем же расчётом). Чужой корень (-EXDEV) так не возникает (ключ —
            // из корня) и не удаляется: точка отсчёта просто не подходит этому дереву.
            if (err[0] != -NativeErr.ENOEXEC && err[0] != -EINVAL) return null
            if (!files.dropA()) return null
        }
        val ms = (System.nanoTime() - t0) / 1_000_000
        Log.i("ancdu", "delta: $n nodes in $ms ms")
        return Delta(h, gen, dd, da, st, Delta.goneMap(flat), files.time(), files.a.length(), ms)
    }

    /**
     * На Holder.io: строка главного экрана по Δ [d] дерева [h] — null, если |Δ| < 1 МиБ. «Больше всего»
     * считается по Δ НА ДИСКЕ (как объём карточки), и браузер по тапу открывается в режиме «диск»
     * (новый экран, apparent = false) — та же Δ, что в строке.
     * Только чтения дерева: дети каталогов на пути «больше всего» и их имена.
     */
    fun home(h: Long, d: Delta): HomeGrowth? {
        val root = d.of(0, false)
        if (!GrowthText.homeShown(root)) return null
        val path = Mostly.path(root, { nd ->
            val c = IntArray(Native.childCount(h, nd))
            val k = maxOf(0, Native.children(h, nd, SORT_SIZE, false, c))
            val inf = LongArray(4 * maxOf(k, 1)).also { if (k > 0) Native.nodeInfo(h, c, k, it) }
            (0 until k).filter { d.known(c[it]) }.map { Mostly.Kid(c[it], d.of(c[it], false), inf[4 * it + 3].toInt() and F_DIR != 0) }
        })
        val names = path.map { Native.name(h, it) }
        return HomeGrowth(root, d.baseTime, names, names.joinToString("/") { Bidi.visible(Native.str(it)) })
    }

    private const val EINVAL = 22
}
