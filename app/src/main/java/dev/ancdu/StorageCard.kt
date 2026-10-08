package dev.ancdu

import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Фокусная панель главного экрана (со скобками), не больше пяти строк текста и полоса: подпись,
 * statfs раздела /data (занято и доля; места нет — свободно), полоса, «свободно X из Y»; под
 * волосяной линией — «Общее хранилище <объём> · ⚠ N ›» (единственная точка входа в дерево общего
 * хранилища) и одна строка статуса ([StatusLine]). Всё — главный поток.
 */
class StorageCard(private val a: MainActivity) {
    private lateinit var usedTxt: TextView
    private lateinit var pctTxt: TextView
    /** «свободно 142,4 из 224,5 ГиБ»; места нет — «мало места · занято X из Y». */
    lateinit var freeTxt: TextView
        private set
    private lateinit var bar: SegBar
    /**
     * Ярус 0: вторичная полоса категорий (скрыта без сегментов). Легенды на карточке нет (≤ 5 строк):
     * числа — в строке «Приложения и система», подписи и размеры — у TalkBack (описание полосы).
     */
    private lateinit var cats: LinearLayout
    private lateinit var catBar: CatBar
    /** Нижняя строка: «Общее хранилище» или амберный запрос доступа. */
    lateinit var storeTitle: TextView
        private set
    /** «35,0 ГиБ» или «35,0 ГиБ · обновляется». */
    lateinit var storeTotal: TextView
        private set
    /** «⚠ 3» — ошибки скана показанного дерева (только если их больше 0); тап — браузер с листом ошибок. */
    lateinit var errTxt: TextView
        private set
    private lateinit var storeArrow: TextView
    /** Единственная строка статуса: прервано > устарело > рост > ничего (тогда GONE). */
    lateinit var statusTxt: TwoFormText
        private set
    /** Для тестов: показанное состояние строки статуса. */
    var status: Status = Status.None
        private set
    /** Для тестов: последняя строка «что выросло» (null — нет). */
    var growth: HomeGrowth? = null
        private set
    /** Непросмотренное прерванное удаление (журнал удалений); тап по нему — [onInterrupted]. */
    var interrupted: InterruptedDelete? = null
    var onInterrupted: (InterruptedDelete) -> Unit = {}
    private lateinit var scanLine: ScanLine
    /** Скан хранилища шёл (или ждал) при прошлом [render]: конец — по итогу именно его. */
    private var scanWas = ScanState.NONE
    /** [BgScan.endMark] на начале этого скана хранилища. */
    private var endMark = 0L
    /** Оценка доли: items кэша хранилища, прочитанные один раз на старте скана. */
    private var estimate: Long? = null
    /** Без доступа ко всем файлам: объяснение и «Открыть настройки» (раскрывается тапом). */
    lateinit var permBox: LinearLayout
        private set
    /** Решение автоскана при последнем onResume (POWER — «обновить ›» вручную). */
    var gate = Gate.FRESH
    /** Занято на /data (statfs; -1 — неизвестно). */
    var dataUsed = -1L
        private set
    /** Объём общего хранилища, показанный в нижней строке (null — неизвестен). */
    var sharedDisk: Long? = null
        private set
    /** Места нет ([HomeMath.storageFull]): герой — свободное место, строки под карточкой свёрнуты. */
    var full = false
        private set
    /** Ошибок скана в показанном дереве (Native.errorNodes на io). */
    var errCount = 0
        private set
    /** Для какого дерева посчитан [errCount]: поколение сессии и счётчик удалений. */
    private var errKey: String? = null
    private var errSeq = 0

    /** Тексты в языке экрана. */
    private val t: Txt = a.tx

    /** Вся фокусная панель (фон со скобками): один кликабельный элемент — вход в дерево. */
    val panel: LinearLayout = build()

    /** То же, что [panel]: касание открывает дерево (без доступа — раскрывает объяснение). */
    val view: LinearLayout get() = panel

    private fun build(): LinearLayout = a.vbox().apply {
        setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(4))
        background = Brackets(a, C.PANEL, pressedFill = C.PANEL2)
        isClickable = true; isFocusable = true
        contentDescription = t.s(R.string.card_desc)
        setOnClickListener { onTap() }
        // «ВНУТРЕННЯЯ ПАМЯТЬ ·» — подпись прописными, «/data» — путь, mono, как есть.
        val head = t.s(R.string.internal_title)
        val cut = head.indexOf(" · ")
        // Flow: при крупном шрифте «/data» уходит на следующую строку, а не сжимается в столбик.
        addView(Flow(a, a.dp(6), a.dp(2)).apply {
            addView(a.caps(if (cut < 0) head else head.substring(0, cut + 2)))
            if (cut >= 0) addView(a.label(head.substring(cut + 3), 12f, C.MUTED, mono = true))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(a.hbox(12).apply {
            gravity = Gravity.BOTTOM
            usedTxt = FitText(a, 40f).apply {
                text = "—"; setTextColor(C.TEXT); typeface = Fonts.get(a, mono = true, bold = true)
            }
            addView(usedTxt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            // Доля — текст (вес 500), не акцент: амбер — только «изменилось / действуй».
            pctTxt = a.label("", 20f, C.TEXT, mono = true).apply {
                typeface = Typeface.create(Fonts.get(a, mono = true, bold = false), 500, false)
                setPadding(0, 0, 0, a.dp(6))
            }
            addView(pctTxt)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = a.dp(6) })
        bar = SegBar(a)
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = a.dp(14) })
        // Легенда сложена в одну строку: «свободно 142,4 из 224,5 ГиБ» (свободное — BLUE_HI).
        freeTxt = a.label("", 13f, C.MUTED, mono = true)
        addView(freeTxt, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = a.dp(10) })
        // Ярус 0 (только с доступом к истории использования): категории занятого.
        cats = a.vbox().apply {
            visibility = View.GONE
            catBar = CatBar(a).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }
            addView(catBar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        addView(cats, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = a.dp(14) })
        hairline(topDp = 14)
        // Под линией 2dp амберная полоса, пока идёт скан.
        scanLine = ScanLine(a)
        addView(scanLine, LinearLayout.LayoutParams(MATCH_PARENT, a.dp(2)))
        addView(a.vbox().apply {
            minimumHeight = a.dp(56)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, a.dp(4), 0, a.dp(6))
            addView(a.hbox(8).apply {
                minimumHeight = a.dp(44)
                // Название и итог — Flow: не помещаются в строку — итог переносится, ничто не сжимается.
                addView(Flow(a, a.dp(12), a.dp(2), endLast = true).apply {
                    storeTitle = a.caps(t.s(R.string.shared_title), C.TEXT)
                    addView(storeTitle)
                    storeTotal = a.label("", 13f, C.TEXT, mono = true)
                    addView(storeTotal)
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                errTxt = a.label("", 13f, C.AMBER_TEXT, mono = true, bold = true).apply {
                    gravity = Gravity.CENTER
                    minHeight = a.dp(44); minWidth = a.dp(44)
                    background = a.pressable(android.graphics.Color.TRANSPARENT)
                    isClickable = true; isFocusable = true
                    visibility = View.GONE
                    feedbackClick { open(errors = true) }
                }
                addView(errTxt)
                storeArrow = a.label("›", 18f, C.MUTED)
                addView(storeArrow)
            })
            statusTxt = TwoFormText(a).apply {
                textSize = 12f; setTextColor(C.MUTED); typeface = Fonts.get(a, mono = true, bold = false)
                gravity = Gravity.CENTER_VERTICAL
                minHeight = a.dp(48)
                visibility = View.GONE
                isClickable = true; isFocusable = true
                feedbackClick { statusTapped() }
            }
            addView(statusTxt, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        permBox = a.vbox(12).apply {
            visibility = View.GONE
            setPadding(0, a.dp(4), 0, a.dp(12))
            addView(a.label(t.s(R.string.files_access_explain), 14f, C.MUTED))
            addView(a.action(t.s(R.string.open_settings), null, true) { Perms.askFiles(a) })
        }
        addView(permBox)
    }

    /** Категории яруса 0 (без «свободно», ненулевые, по убыванию) под главной полосой; без легенды. */
    fun showSegs(segs: List<Seg>) {
        val used = segs.filter { it.label != R.string.seg_free && it.bytes > 0 }.sortedByDescending { it.bytes }
        cats.visibility = if (used.isEmpty()) View.GONE else View.VISIBLE
        // Сегменты посчитаны на рабочем потоке с ролями; цвета — здесь, в палитре этого экрана.
        val colors = IntArray(used.size) { used[it].role.color() }
        catBar.show(used, colors)
        a.layoutChanged()
    }

    /**
     * statfs /data: герой, доля, полоса и строка «свободно». Места нет (< 1 ГиБ или < 5%) — герой
     * показывает свободное (DANGER_TEXT, «⚠ свободно»), строка — «мало места · занято X из Y».
     */
    fun showStatfs() {
        val s = fakeStatfs?.copyOf() ?: LongArray(3).also { if (Native.statfs("/data", it) != 0) return }
        val total = s[0]
        val used = s[0] - s[1]
        val free = s[2]
        dataUsed = used
        full = HomeMath.storageFull(free, total)
        bar.used = ListMath.bar(used, total)
        if (full) {
            usedTxt.text = Fmt.size(free, t); usedTxt.setTextColor(C.DANGER_TEXT)
            pctTxt.text = t.s(R.string.hero_free); pctTxt.setTextColor(C.DANGER_TEXT)
            freeTxt.text = t.s(R.string.card_low, Fmt.sizeOf(used, total, t))
        } else {
            usedTxt.text = Fmt.size(used, t); usedTxt.setTextColor(C.TEXT)
            pctTxt.text = Fmt.pct(used, total); pctTxt.setTextColor(C.TEXT)
            freeTxt.text = freeLine(free, total)
        }
        renderErr()
        a.layoutChanged()
    }

    /** «свободно 142,4 из 224,5 ГиБ»: число свободного — BLUE_HI (данные), остальное приглушено. */
    private fun freeLine(free: Long, total: Long): CharSequence {
        val of = Fmt.sizeOf(free, total, t)
        val line = t.s(R.string.card_free, of)
        val f = Fmt.size(free, t)
        val num = if (of.startsWith(f)) f else f.substringBeforeLast(Fmt.NBSP)
        val at = line.indexOf(of)
        if (at < 0) return line
        return SpannableString(line).apply {
            setSpan(ForegroundColorSpan(C.BLUE_HI), at, at + num.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** Есть ли в Holder дерево общего хранилища (без root). */
    fun storageShown(): Boolean = Holder.h != 0L && Holder.root == Scans.STORAGE && !Holder.viaRoot

    /**
     * Полоса скана хранилища. Доля — от items последнего кэша хранилища (читается один раз, когда
     * скан пошёл); без него и в очереди — неопределённая. Конец — на 100%, если удался именно этот
     * скан, иначе (провал, «грязный» итог) — скрыть сразу.
     */
    private fun renderLine() {
        val was = scanWas
        val st = when { BgScan.storageRunning -> ScanState.RUNNING; BgScan.storageActive -> ScanState.QUEUED; else -> ScanState.NONE }
        scanWas = st
        if (st == ScanState.NONE) {
            if (was == ScanState.NONE) return
            if (ScanProgress.completes(BgScan.endFor(Scans.STORAGE, false, endMark))) scanLine.finish() else scanLine.hide()
            return
        }
        if (was == ScanState.NONE) endMark = BgScan.endMark
        if (st == ScanState.RUNNING && was != ScanState.RUNNING) estimate = Scans.meta(a, Scans.STORAGE, false)?.items
        scanLine.show(if (st == ScanState.RUNNING) ScanProgress.fraction(BgScan.p[1], estimate) else null)
    }

    /**
     * Нижняя строка и строка статуса. Объём и время — всегда из ОДНОГО источника (не statfs):
     * дерева в Holder (его собственное время — Holder.time), иначе итога скана этого процесса,
     * иначе записи кэша.
     */
    fun render() {
        if (!Perms.files()) {
            storeTitle.text = t.s(R.string.open_tree_need_access)
            storeTitle.setTextColor(C.AMBER_TEXT)
            // Это уже фраза, не подпись: обычный регистр, 14sp.
            storeTitle.isAllCaps = false; storeTitle.letterSpacing = 0f; storeTitle.textSize = 14f
            storeTotal.text = ""
            storeArrow.setTextColor(C.AMBER_TEXT)
            errTxt.visibility = View.GONE
            status = Status.None
            statusTxt.visibility = View.GONE
            sharedDisk = null
            scanLine.hide(); scanWas = ScanState.NONE
            view.contentDescription = t.s(R.string.card_desc_need_access, t.s(R.string.card_desc))
            a.layoutChanged()
            return
        }
        permBox.visibility = View.GONE
        view.contentDescription = t.s(R.string.card_desc)
        storeTitle.text = t.s(R.string.shared_title)
        storeTitle.setTextColor(C.TEXT)
        storeTitle.isAllCaps = true; storeTitle.letterSpacing = 0.08f; storeTitle.textSize = 12f
        storeArrow.setTextColor(C.MUTED)
        val last = Scans.lastStorage
        var disk: Long? = null
        var time: Long? = null
        var approx = false
        val shown = storageShown() && !Holder.deleting
        if (shown) {
            // Главный поток, опубликованный дескриптор — обычное чтение дерева.
            val inf = LongArray(4).also { Native.nodeInfo(Holder.h, intArrayOf(0), 1, it) }
            disk = inf[0]
            time = Freshness.treeTime(Holder.kind, Holder.time)
            approx = Holder.kind == Kind.INDEX
        } else if (last != null) {
            disk = last.disk; time = last.time
        } else {
            val meta = Scans.meta(a, Scans.STORAGE, false)
            if (meta != null) { disk = meta.disk; time = meta.time }
        }
        sharedDisk = disk
        // Только скан общего хранилища (идёт или в очереди за обновлением другого корня).
        val running = BgScan.storageActive
        storeTotal.text = listOfNotNull(disk?.let { Fmt.size(it, t) }, if (running) t.s(R.string.shared_updating) else null)
            .joinToString(" · ")
        renderLine()
        refreshErrors(shown)
        renderStatus(running, time, approx)
        a.layoutChanged()
    }

    /** Строка статуса: один факт по приоритету ([StatusLine.pick]); нет факта — строки нет. */
    private fun renderStatus(running: Boolean, time: Long?, approx: Boolean) {
        val now = System.currentTimeMillis()
        val i = interrupted?.let { withFreed(it) }
        val s = StatusLine.pick(i, running, time, approx, growth, now)
        status = s
        val full = StatusLine.text(t, s, now, wide = true)
        if (full == null) { statusTxt.visibility = View.GONE; return }
        statusTxt.set(full, StatusLine.text(t, s, now, wide = false) ?: full)
        statusTxt.setTextColor(when (s) {
            is Status.Grew -> GrowthText.homeRole(s.g.delta).color()
            else -> C.AMBER_TEXT
        })
        statusTxt.contentDescription = when (s) {
            is Status.Interrupted -> "${full.removeSuffix(" ›")}, ${t.s(R.string.interrupted_open_desc)}"
            is Status.Stale -> "${full.removeSuffix(" ›")}, ${t.s(R.string.refresh_desc)}"
            is Status.Grew -> "${full.removeSuffix(" ›")}, ${t.s(R.string.growth_open_desc)}"
            Status.None -> null
        }
        statusTxt.visibility = View.VISIBLE
    }

    /**
     * Прерванное удаление в показанном дереве общего хранилища, более новом, чем оно: сколько
     * освобождено — размер на момент удаления минус то, что осталось (узла нет — всё).
     */
    private fun withFreed(d: InterruptedDelete): InterruptedDelete {
        if (d.freed != null || d.done != null || d.su || d.root != Scans.STORAGE || !storageShown() || Holder.deleting ||
            Holder.kind == Kind.INDEX || Holder.time <= d.time || !d.allNamed) return d
        val hit = PathWalk.resolve(d.names) { nd, nm -> child(nd, nm) }
        fun disk(nd: Int) = LongArray(4).also { Native.nodeInfo(Holder.h, intArrayOf(nd), 1, it) }[0]
        // Группа: удалено — те её объекты, которых в папке больше нет (и сколько освобождено);
        // одиночное — остаток самого узла.
        if (d.group) {
            val alive = if (hit.exact) d.items.mapNotNull { nm -> child(hit.node, nm) } else emptyList()
            return d.withDone(d.count - alive.size).withFreed(maxOf(0L, d.disk - alive.sumOf { disk(it) }))
        }
        val left = if (hit.exact) disk(hit.node) else 0L
        return d.withFreed(maxOf(0L, d.disk - left))
    }

    /** Ребёнок [nd] показанного дерева с именем ровно [nm] (байты) или null. Главный поток. */
    private fun child(nd: Int, nm: ByteArray): Int? {
        val h = Holder.h
        val c = IntArray(Native.childCount(h, nd))
        val k = maxOf(0, Native.children(h, nd, SORT_NAME, false, c))
        for (i in 0 until k) if (Native.name(h, c[i]).contentEquals(nm)) return c[i]
        return null
    }

    /**
     * Число ошибок скана показанного дерева — на io (Native.errorNodes, O(n)), раз на дерево и после
     * каждого удаления; во время удаления дерево не читается. Нет дерева — нет и «⚠ N».
     */
    private fun refreshErrors(shown: Boolean) {
        if (!shown) { errKey = null; errCount = 0; renderErr(); return }
        val k = "${Holder.gen}:${Holder.deletes}"
        if (k == errKey) { renderErr(); return }
        errKey = k
        errCount = 0
        renderErr()
        val handle = Holder.h
        val my = ++errSeq
        Holder.io.execute {
            val n = runCatching { Native.errorNodes(handle, IntArray(0)) }.getOrDefault(0)
            a.runOnUiThread {
                if (my != errSeq || Holder.h != handle || a.isDestroyed) return@runOnUiThread
                errCount = n
                renderErr()
                a.layoutChanged()
            }
        }
    }

    /**
     * «⚠ N» — только если ошибок больше 0 и места хватает: при «места нет» акценты — свободное место
     * и строка статуса (не больше двух на экране); ошибки остаются в подвале браузера.
     */
    private fun renderErr() {
        val show = errCount > 0 && !full
        errTxt.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) return
        errTxt.text = ScanErrors.short(t, errCount)
        errTxt.contentDescription = ScanErrors.linkDesc(t, errCount)
    }

    /** Строка «что выросло» ([g] null — нет точки отсчёта или |Δ| < 1 МиБ). Главный поток. */
    fun showGrowth(g: HomeGrowth?) {
        growth = g
        if (Perms.files()) render()
    }

    /** Тап по строке статуса — её единственное действие. */
    private fun statusTapped() {
        when (val s = status) {
            is Status.Interrupted -> onInterrupted(s.d)
            is Status.Stale -> refreshNow()
            is Status.Grew -> a.openFocused(s.g.names, delta = true)
            Status.None -> {}
        }
    }

    /** «обновить ›»: скан вручную — энергосбережение и нагрев не мешают явной просьбе. */
    private fun refreshNow() {
        if (BgScan.storageActive) return
        if (BgScan.start(a)) gate = Gate.RUNNING
        else BgScan.failure?.let { a.showAlert(t.s(R.string.scan_not_started), NativeErr.text(t, it)) }
        render()
    }

    /** Тап по карточке. Без доступа — раскрыть объяснение; иначе открыть дерево. */
    private fun onTap() {
        if (!Perms.files()) {
            permBox.visibility = if (permBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return
        }
        open()
    }

    /**
     * Готовое живое дерево — сразу (ждущее подставляется); иначе кэш; иначе экран прогресса,
     * ПРИЦЕПЛЕННЫЙ к идущему фоновому скану — никогда не второй скан. [errors] — «⚠ N»: браузер
     * сразу открывает лист ошибок.
     */
    private fun open(errors: Boolean = false) {
        if (Holder.deleting || a.opening) return
        if (BgScan.pendingStorage()) Holder.promote()
        val shown = storageShown()
        val browser = Intent(a, BrowserActivity::class.java).apply { if (errors) putExtra(EXTRA_ERRORS, true) }
        if (shown && Holder.kind != Kind.INDEX) { a.startActivity(browser); return }
        val meta = Scans.meta(a, Scans.STORAGE, false)
        if (meta != null) {
            a.openCache(Holder.cacheFile(a, Scans.STORAGE, false).name, Scans.STORAGE, false, meta.time, errors = errors)
            return
        }
        if (BgScan.storageActive) { attach(); return }
        if (shown) { a.startActivity(browser); return }
        if (BgScan.start(a)) { gate = Gate.RUNNING; render(); attach() }
        else a.showAlert(t.s(R.string.scan_not_started),
            BgScan.failure?.let { NativeErr.text(t, it) } ?: t.s(R.string.no_storage_access))
    }

    private fun attach() = a.startActivity(Intent(a, ScanActivity::class.java).putExtra(EXTRA_ATTACH, true))

    /**
     * Для тестов (после layout): видимых строк текста в карточке. Горизонтальный ряд — столько строк,
     * сколько у самого высокого его ребёнка; ряд Flow — по рядам; вертикальный — сумма.
     */
    fun textLines(): Int = lines(panel)

    private fun lines(v: View): Int {
        if (v.visibility != View.VISIBLE) return 0
        return when {
            v is TextView -> if (v.text.isNullOrEmpty()) 0 else maxOf(1, v.lineCount)
            v is Flow -> kids(v).filter { lines(it) > 0 }.groupBy { it.top }.values.sumOf { row -> row.maxOf { lines(it) } }
            v is LinearLayout && v.orientation == LinearLayout.HORIZONTAL -> kids(v).maxOfOrNull { lines(it) } ?: 0
            v is ViewGroup -> kids(v).sumOf { lines(it) }
            else -> 0
        }
    }

    private fun kids(g: ViewGroup): List<View> = (0 until g.childCount).map { g.getChildAt(it) }

    companion object {
        /** Для тестов: statfs /data (total, free, avail) вместо настоящего; null — настоящий. */
        @Volatile var fakeStatfs: LongArray? = null
    }
}

/**
 * Одна строка с многоточием посередине: полная форма [set], а если она не помещается в ширину —
 * короткая (строка роста без «больше всего»). Главный поток.
 */
class TwoFormText(ctx: Context) : TextView(ctx) {
    private var full = ""
    private var short = ""
    private var fitW = -1

    init { setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE }

    fun set(full: String, short: String) {
        this.full = full; this.short = short; fitW = -1
        text = full
        requestLayout()
    }

    override fun onMeasure(ws: Int, hs: Int) {
        val w = MeasureSpec.getSize(ws) - compoundPaddingLeft - compoundPaddingRight
        if (MeasureSpec.getMode(ws) != MeasureSpec.UNSPECIFIED && w > 0 && w != fitW) {
            fitW = w
            val pick = if (paint.measureText(full) <= w) full else short
            if (pick != text.toString()) text = pick
        }
        super.onMeasure(ws, hs)
    }
}
