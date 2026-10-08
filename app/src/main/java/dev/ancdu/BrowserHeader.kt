package dev.ancdu

import android.content.ClipData
import android.content.ClipboardManager
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Шапка браузера [a]: строка пути и заголовок, панель пути, сводка, плашка вида дерева и чип
 * «новее», переключатели сортировки и режима размера, полоса фонового скана под шапкой.
 * Главный поток, чтения дерева — с дескриптором экрана.
 */
class BrowserHeader(private val a: BrowserActivity) {
    /** Заголовок: имя текущей папки (на корне — PathText.rootTitle). */
    lateinit var title: TextView
        private set
    /** Строка пути над заголовком (всегда, и на корне): тап — панель пути, долгое — копировать. */
    lateinit var pathRow: PathRow
        private set
    /** Полный путь текущей папки (его копирует «Копировать путь»). */
    var currentPath = ""
        private set
    /** Открытая панель пути. */
    var pathPanel: PathPanel? = null
        private set
    /** Узлы пути от корня до текущей папки (строки панели пути). */
    var crumbNodes = IntArray(0)
        private set
    /** Сводка папки: размер и число элементов (в сортировке Δ — её Δ). */
    lateinit var summary: TextView
        private set
    /** Плашка вида дерева («скан · 69 312 эл. · 0,2 с»). */
    lateinit var badge: TextView
        private set
    lateinit var chips: Flow
        private set
    /**
     * Шапка целиком: её высота не зависит от сортировки, режима размера и чипа «новее» и одна и
     * та же на корне и во вложенных папках (строка пути есть всегда).
     */
    lateinit var header: LinearLayout
        private set
    /** Амберный чип «новее · обновить»: в Holder ждёт более новое дерево того же корня. */
    lateinit var newer: TextView
        private set
    /** Полоса 2dp под линией шапки: фоновое обновление показанного дерева. */
    lateinit var scanLine: ScanLine
        private set
    /** Состояние фонового скана показанного дерева при последнем [renderProgress]. */
    private var scanState = ScanState.NONE
    /** [BgScan.endMark] на начале скана показанного дерева: итог — именно этого скана. */
    private var endMark = 0L
    /** Когда плашка последний раз показала счёт (uptime). */
    private var badgeAt = 0L
    /** Оценка для доли полосы: items корня показанного дерева на старте скана. */
    private var estimate: Long? = null
    /** Обычный текст плашки — вид дерева (load). */
    private var sourceBadge = ""

    /** Шапка собрана ([build]): чип «новее» уже есть. */
    val built get() = ::newer.isInitialized

    /** onCreate экрана: шапка целиком (её кладёт в окно экран). */
    fun build(): LinearLayout {
        val top = a.vbox(8).also { header = it }.apply { setPadding(a.dp(8), a.dp(12), a.dp(16), a.dp(12)); setBackgroundColor(C.BG) }
        title = a.label("", 22f, C.TEXT, bold = true).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE
        }
        // Путь — одна строка над заголовком и на корне: высота шапки везде одна.
        pathRow = PathRow(a).apply {
            longClickLabel = a.txt.s(R.string.copy_path)
            feedbackClick { openPathPanel() }
            setOnLongClickListener { Feedback.cue(this, Cue.TAP); copyPath(); true }
        }
        top.addView(a.hbox(4).apply {
            addView(a.backButton { a.onBackPressed() })
            addView(a.vbox().apply {
                addView(pathRow, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(title)
            }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        })
        summary = a.label("", 13f, C.MUTED, mono = true).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
        }
        // До двух строк: рядом с чипом «новее» длинная плашка («root · скан · 12,3 с · неполный»)
        // переносится, а не обрезается. Две строки 12sp ниже 44dp строки чипа — шапка не прыгает.
        badge = a.label("", 12f, C.MUTED, mono = true).apply {
            maxLines = BADGE_LINES; ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            // В сортировке Δ — «Δ с 1 окт. 09:12»: тап открывает лист точки отсчёта (load включает касание).
            feedbackClick { a.deltaLevel.openBaseline() }
            isClickable = false; isFocusable = false
        }
        newer = a.caps(a.txt.s(R.string.newer_chip), C.INK).apply {
            gravity = Gravity.CENTER
            minHeight = a.dp(44)
            isClickable = true; isFocusable = true
            contentDescription = a.txt.s(R.string.newer_desc)
            feedbackClick { a.promotePending() }
            visibility = View.GONE
        }
        // «⇣ [РАЗМЕР|ИМЯ]» и справа [ДИСК|ВИДИМЫЙ]; не влезают в строку — переносятся.
        chips = Flow(a, a.dp(12), a.dp(8), endLast = true)
        top.addView(a.vbox().apply {
            setPadding(a.dp(8), 0, 0, 0)
            addView(summary, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // Строка плашки всегда высотой с чип «новее» (44dp): его появление не двигает список.
            addView(a.hbox(8).apply {
                minimumHeight = a.dp(44)
                addView(badge, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                addView(newer)
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
        top.addView(chips, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        styleNewer(false)
        return top
    }

    /** Плашка показывает ход скана (не вид дерева). */
    private var badgeActive = false

    /**
     * Возраст и вид дерева — факт, не призыв: приглушённо. Ход скана — амбер; плашка Δ (касаемая) —
     * амбер, пока на экране нет амберной ссылки ошибок (не больше двух акцентов сразу).
     */
    private fun tintBadge() {
        badge.setTextColor(if (badgeActive || a.deltaShown && !newerOutlined) C.AMBER_TEXT else C.MUTED)
    }

    /** Плашка видна и амберная (ход скана или касаемая Δ) — акцент экрана. */
    val badgeAmber: Boolean get() = ::badge.isInitialized && badge.visibility == View.VISIBLE &&
        badge.text.isNotEmpty() && (badgeActive || a.deltaShown && !newerOutlined)

    /** Чип «новее» сейчас в контуре (не амберная заливка). */
    var newerOutlined = false
        private set

    /**
     * Стиль чипа «новее»: обычно — амберная заливка; когда на экране уже есть амберная ссылка ошибок
     * (и ⚠ у строк), чип — в контуре FRAME с подписью TEXT: не больше двух акцентов сразу.
     */
    fun styleNewer(outlined: Boolean) {
        if (::newer.isInitialized && newerOutlined == outlined && newer.background != null) return
        newerOutlined = outlined
        newer.background = if (outlined) a.pressable(android.graphics.Color.TRANSPARENT, C.FRAME)
        else android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), a.box(C.PANEL2, C.AMBER_TEXT))
            addState(intArrayOf(android.R.attr.state_focused), a.box(C.PANEL2, C.AMBER_TEXT))
            addState(intArrayOf(), a.box(C.AMBER))
        }
        newer.setTextColor(if (outlined) android.content.res.ColorStateList.valueOf(C.TEXT)
        else android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_pressed),
            intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(C.AMBER_TEXT, C.AMBER_TEXT, C.INK)))
        newer.setPadding(a.dp(12), 0, a.dp(12), 0)
        if (::badge.isInitialized) tintBadge()
    }

    /** onCreate экрана: полоса скана (её кладёт под линию шапки экран). */
    fun makeScanLine() {
        scanLine = ScanLine(a)
    }

    /**
     * load(): обычный текст плашки — вид дерева [text]. Плашка Δ — касаемая (лист точки отсчёта),
     * 44dp; иначе — просто текст. Идёт скан — плашку держит [renderProgress].
     */
    fun showSource(text: String) {
        sourceBadge = text
        badge.isClickable = a.deltaShown; badge.isFocusable = a.deltaShown
        badge.minHeight = if (a.deltaShown) a.dp(44) else 0
        if (scanState == ScanState.NONE) setBadge(sourceBadge, active = false, polite = false)
    }

    /**
     * Полоса и плашка фонового обновления показанного дерева (тот же корень и режим su). Идёт —
     * доля files / items корня (не больше 0.97), плашка «обновление · N» раз в секунду; ждёт в
     * очереди — неопределённая полоса и «обновление · ждёт». Кончился — полоса на 100% и
     * скрывается, плашка молча снова показывает вид дерева; не удался или выброшен (по итогу
     * ЭТОГО скана, не по старому ждущему дереву) — полоса скрывается сразу. Голосом — только
     * начало и ожидание ([ScanProgress.polite]). Итог скана, если он только что кончился, иначе null.
     */
    fun renderProgress(): ScanEnd? {
        if (!::scanLine.isInitialized || a.h == 0L || a.isDestroyed) return null
        val st = BgScan.stateFor(Holder.root, Holder.viaRoot)
        val was = scanState
        scanState = st
        if (st == ScanState.NONE) {
            if (was == ScanState.NONE) return null
            // Итог не записан (su-цель снята с очереди) — нового дерева нет, как при провале.
            val end = BgScan.endFor(Holder.root, Holder.viaRoot, endMark) ?: ScanEnd.FAILED
            if (ScanProgress.completes(end)) scanLine.finish() else scanLine.hide()
            setBadge(sourceBadge, active = false, polite = false)
            return end
        }
        if (was == ScanState.NONE) endMark = BgScan.endMark
        val files = BgScan.p[1]
        // Во время удаления дерево не читается: без оценки — неопределённая полоса.
        if (st == ScanState.RUNNING && was != ScanState.RUNNING)
            estimate = if (a.busy) null else LongArray(4).also { Native.nodeInfo(a.h, intArrayOf(0), 1, it) }[2]
        scanLine.show(if (st == ScanState.RUNNING) ScanProgress.fraction(files, estimate) else null)
        val now = SystemClock.uptimeMillis()
        if (ScanProgress.badgeDue(st != was, now, badgeAt)) {
            setBadge(ScanProgress.badge(a.txt, st, files), active = true, polite = ScanProgress.polite(was, st))
            badgeAt = now
        }
        return null
    }

    /**
     * [polite] — TalkBack прочтёт (начало, ожидание); тики счёта, конец и покой — молча (живая
     * область снимается до смены текста). [active] — ход скана: одна строка и при 200%; вид
     * дерева — до [BADGE_LINES].
     */
    private fun setBadge(text: String, active: Boolean, polite: Boolean) {
        badge.accessibilityLiveRegion = if (polite) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
        badge.maxLines = if (active) 1 else BADGE_LINES
        badgeActive = active
        tintBadge()
        badge.text = text
        // Плашка Δ (касаемая) — с подсказкой «точка отсчёта»; ход скана поверх неё читается как есть.
        badge.contentDescription = if (a.deltaShown && text == sourceBadge) a.txt.s(R.string.badge_delta_desc, text) else null
    }

    /** Сегменты сортировки и режима размера (пересоздаются в renderChips). */
    private var sortSeg: LinearLayout? = null
    private var sizeSeg: LinearLayout? = null

    /** Для тестов: тексты сегментов сортировки, затем режима размера (как в ресурсах). */
    fun segmentTexts(): List<String> = listOfNotNull(sortSeg, sizeSeg).flatMap { g ->
        (0 until g.childCount).map { (g.getChildAt(it) as TextView).text.toString() }
    }

    /** Для тестов: все сегменты (касания 44dp). */
    fun segments(): List<View> = listOfNotNull(sortSeg, sizeSeg).flatMap { g -> (0 until g.childCount).map { g.getChildAt(it) } }

    /** Состояние, из которого собраны переключатели (сортировка, режим размера, есть ли Δ); null — не собраны. */
    private var chipsFor: Triple<Int, Boolean, Boolean>? = null

    /**
     * Сегмент Δ есть: для дерева посчитана Δ против точки отсчёта. Пока она считается (новое дерево того
     * же экрана), сегмент держится, если Δ выбрана или уже была предложена — без мигания.
     */
    private fun deltaOffered(): Boolean =
        a.h != 0L && (Growth.forTree(a.h, a.gen) != null ||
            (Growth.pending(a.h, a.gen) && (a.sort == SORT_DELTA || chipsFor?.third == true)))

    fun renderChips() {
        // Вход в папку не меняет ни сортировку, ни режим: пересборка шапки (новые view, шрифты,
        // заново measure/layout всей шапки) стоила ~5 мс на каждый load — половина бюджета кадра.
        val offered = deltaOffered()
        val want = Triple(a.sort, a.apparent, offered)
        if (chipsFor == want) return
        chipsFor = want
        chips.removeAllViews()
        // [РАЗМЕР | ИМЯ | Δ]: Δ — только когда есть точка отсчёта. «Гиганты» — без ИМЕНИ (имена не уникальны).
        val named = !a.giants
        val opts = listOf(a.txt.s(R.string.sort_size)) + (if (named) listOf(a.txt.s(R.string.sort_name)) else emptyList()) +
            if (offered) listOf(a.txt.s(R.string.sort_delta)) else emptyList()
        val sel = when { named && a.sort == SORT_NAME -> 1; a.sort == SORT_DELTA && offered -> opts.size - 1; else -> 0 }
        val sorts = a.segmented(opts, sel, amber = true) {
            a.setSort(when { it == 0 -> SORT_SIZE; named && it == 1 -> SORT_NAME; else -> SORT_DELTA })
        }
        val sizes = a.segmented(listOf(a.txt.s(R.string.size_disk), a.txt.s(R.string.size_apparent)),
            if (a.apparent) 1 else 0, amber = false) { a.setApparent(it == 1) }
        sortSeg = sorts; sizeSeg = sizes
        // Смысл пиктограммы — в описаниях сегментов сортировки.
        sorts.getChildAt(0).contentDescription = a.txt.s(R.string.sort_size_desc)
        if (named) sorts.getChildAt(1).contentDescription = a.txt.s(R.string.sort_name_desc)
        if (offered) sorts.getChildAt(opts.size - 1).contentDescription = a.txt.s(R.string.sort_delta_desc)
        sizes.contentDescription = a.txt.s(R.string.size_mode_desc, a.txt.s(if (a.apparent) R.string.size_apparent else R.string.size_disk_desc))
        // Пиктограмма и сегменты сортировки — один ребёнок Flow (не разрываются при переносе);
        // группы при крупном шрифте переносятся, не сжимаются.
        chips.addView(a.hbox(6).apply {
            addView(ImageView(a).apply {
                setImageResource(R.drawable.ic_sort)
                imageTintList = android.content.res.ColorStateList.valueOf(C.MUTED)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(a.dp(24), a.dp(24)))
            addView(sorts)
        })
        chips.addView(sizes)
    }

    /**
     * Строка пути — полный путь текущей папки, заголовок — её имя (на корне — PathText.rootTitle).
     * Главный поток, чтения дерева — с [h].
     */
    fun renderHeader() {
        val chain = ArrayList<Int>()
        var c = a.node
        while (c > 0) { chain += c; c = Native.parent(a.h, c) }
        chain += 0
        chain.reverse()
        crumbNodes = chain.toIntArray()
        title.text = if (a.giants) a.txt.s(R.string.big_title)
            else Bidi.visible(if (a.node == 0) PathText.rootTitle(a.rootPath(), a.txt.s(R.string.internal_storage)) else a.nameOf(a.node))
        currentPath = Native.str(Native.path(a.h, a.node))
        pathRow.path = Bidi.visible(currentPath)
        pathRow.contentDescription = a.txt.s(R.string.path_row_desc, currentPath)
    }

    /** Панель пути: полный путь, «Копировать путь», предки от корня (тап — переход к нему). */
    fun openPathPanel() {
        if (a.busy || a.h == 0L) return
        pathPanel?.dismiss()
        val root = a.rootPath()
        val rows = crumbNodes.map { nd -> nd to Bidi.visible(if (nd == 0) root else a.nameOf(nd)) }
        pathPanel = PathPanel(a, Bidi.visible(currentPath), rows, a.node,
            onCopy = { pathPanel?.dismiss(); copyPath() },
            onJump = { nd -> pathPanel?.dismiss(); a.jumpTo(nd) },
            onClose = { a.refreshPending() }).also { it.show() }
    }

    /** Полный путь текущей папки — в буфер обмена; в подвале «Путь скопирован» на 4 с. */
    fun copyPath() {
        if (a.h == 0L || currentPath.isEmpty()) return
        val cm = a.getSystemService(ClipboardManager::class.java) ?: return
        cm.setPrimaryClip(ClipData.newPlainText(a.txt.s(R.string.path_caps), currentPath))
        a.note(a.txt.s(R.string.path_copied))
    }

    /** Закрыть панель пути (её строки — узлы старого дерева). */
    fun dismissPanel() { pathPanel?.dismiss(); pathPanel = null }

    /** onDestroy экрана. */
    fun dispose() = dismissPanel()

    private companion object {
        /** Плашка вида дерева: до двух строк рядом с чипом «новее». */
        const val BADGE_LINES = 2
    }
}
