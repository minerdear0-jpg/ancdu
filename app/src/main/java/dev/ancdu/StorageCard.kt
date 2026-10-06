package dev.ancdu

import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Фокусная панель главного экрана (со скобками): statfs раздела /data — число «занято», доля,
 * «из N», полоса и легенда; под волосяной линией — строка «Общее хранилище <объём> · <N> эл. ›»
 * со строкой свежести и дельтой: единственная точка входа в дерево общего хранилища. Всё — главный поток.
 */
class StorageCard(private val a: MainActivity) {
    private lateinit var usedTxt: TextView
    private lateinit var pctTxt: TextView
    private lateinit var ofTxt: TextView
    private lateinit var usedVal: TextView
    private lateinit var freeVal: TextView
    private lateinit var bar: SegBar
    /** Нижняя строка: «Общее хранилище» или амберный запрос доступа. */
    lateinit var storeTitle: TextView
        private set
    lateinit var storeTotal: TextView
        private set
    private lateinit var storeArrow: TextView
    /** Приглушённая строка свежести («скан HH:MM · только что», «обновить ›», …). */
    lateinit var freshTxt: TextView
        private set
    private lateinit var deltaTxt: TextView
    private lateinit var scanLine: ProgressBar
    /** Без доступа ко всем файлам: объяснение и «Открыть настройки» (раскрывается тапом). */
    lateinit var permBox: LinearLayout
        private set
    /** Решение автоскана при последнем onResume (POWER — «обновить ›» вручную). */
    var gate = Gate.FRESH

    /** Тексты в языке экрана. */
    private val t: Txt = a.tx

    /** Строка входа в дерево: один кликабельный элемент (≥56dp). */
    lateinit var view: LinearLayout
        private set

    /** Вся фокусная панель (фон со скобками). */
    val panel: LinearLayout = build()

    private fun build(): LinearLayout = a.vbox().apply {
        setPadding(a.dp(16), a.dp(16), a.dp(16), a.dp(4))
        background = Brackets(a, C.PANEL)
        addView(a.caps(t.s(R.string.internal_title)))
        addView(a.hbox(12).apply {
            gravity = Gravity.BOTTOM
            usedTxt = FitText(a, 40f).apply {
                text = "—"; setTextColor(C.TEXT); typeface = Fonts.get(a, mono = true, bold = true)
            }
            addView(usedTxt, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            pctTxt = a.label("", 20f, C.AMBER, mono = true, bold = true).apply { setPadding(0, 0, 0, a.dp(6)) }
            addView(pctTxt)
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = a.dp(6) })
        ofTxt = a.label("", 14f, C.MUTED, mono = true)
        addView(ofTxt)
        bar = SegBar(a)
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = a.dp(14) })
        addView(Flow(a, a.dp(14), a.dp(4)).apply {
            usedVal = a.label("", 13f, C.TEXT, mono = true)
            freeVal = a.label("", 13f, C.BLUE_HI, mono = true)
            addView(legendItem(C.AMBER, R.string.legend_used, usedVal))
            addView(legendItem(C.BLUE, R.string.seg_free, freeVal))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = a.dp(10) })
        hairline(topDp = 14)
        // Под линией 2dp амберная полоса, пока идёт скан.
        scanLine = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = ColorStateList.valueOf(C.AMBER)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.INVISIBLE
        }
        addView(scanLine, LinearLayout.LayoutParams(MATCH_PARENT, a.dp(2)))
        view = a.vbox().apply {
            minimumHeight = a.dp(56)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, a.dp(4), 0, a.dp(6))
            background = a.pressable(C.PANEL)
            isClickable = true; isFocusable = true
            contentDescription = t.s(R.string.card_desc)
            setOnClickListener { onTap() }
            addView(a.hbox(8).apply {
                minimumHeight = a.dp(40)
                storeTitle = a.caps(t.s(R.string.shared_title), C.TEXT)
                addView(storeTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                storeTotal = a.label("", 13f, C.TEXT, mono = true)
                addView(storeTotal)
                storeArrow = a.label("›", 18f, C.MUTED)
                addView(storeArrow)
            })
            freshTxt = a.label("", 12f, C.MUTED, mono = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { refreshNow() }
                isClickable = false
            }
            addView(freshTxt)
            deltaTxt = a.label("", 12f, C.MUTED, mono = true).apply { visibility = View.GONE }
            addView(deltaTxt)
        }
        addView(view, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        permBox = a.vbox(12).apply {
            visibility = View.GONE
            setPadding(0, a.dp(4), 0, a.dp(12))
            addView(a.label(t.s(R.string.files_access_explain), 14f, C.MUTED))
            addView(a.action(t.s(R.string.open_settings), null, true) { Perms.askFiles(a) })
        }
        addView(permBox)
    }

    /** «■ ПОДПИСЬ значение»: квадрат цвета полосы, подпись прописными, значение — данные. */
    private fun legendItem(color: Int, labelRes: Int, value: TextView): LinearLayout = a.hbox(6).apply {
        addView(a.label("■", 12f, color).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        addView(a.caps(t.s(labelRes)))
        addView(value)
    }

    fun showStatfs() {
        val s = LongArray(3)
        if (Native.statfs("/data", s) != 0) return
        val used = s[0] - s[1]
        usedTxt.text = Fmt.size(used, t)
        pctTxt.text = Fmt.pct(used, s[0])
        ofTxt.text = t.s(R.string.hero_of, Fmt.size(s[0], t))
        usedVal.text = Fmt.size(used, t)
        freeVal.text = Fmt.size(s[2], t)
        bar.used = ListMath.bar(used, s[0])
    }

    /** Есть ли в Holder дерево общего хранилища (без root). */
    private fun storageShown(): Boolean = Holder.h != 0L && Holder.root == Scans.STORAGE && !Holder.viaRoot

    /**
     * Нижняя строка. Объём, элементы и время — всегда из ОДНОГО источника (не statfs): дерева в
     * Holder (его собственное время — Holder.time), иначе итога скана этого процесса, иначе
     * записи кэша. Новое время рядом со старыми итогами не появляется.
     */
    fun render() {
        if (!Perms.files()) {
            storeTitle.text = t.s(R.string.open_tree_need_access)
            storeTitle.setTextColor(C.AMBER)
            // Это уже фраза, не подпись: обычный регистр, 14sp.
            storeTitle.isAllCaps = false; storeTitle.letterSpacing = 0f; storeTitle.textSize = 14f
            storeTotal.text = ""
            storeArrow.setTextColor(C.AMBER)
            freshTxt.visibility = View.GONE
            deltaTxt.visibility = View.GONE
            scanLine.visibility = View.INVISIBLE
            view.contentDescription = t.s(R.string.card_desc_need_access, t.s(R.string.card_desc))
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
        var items: Long? = null
        var time: Long? = null
        var scanned = false
        var approx = false
        val shown = storageShown() && !Holder.deleting
        if (shown) {
            // Главный поток, опубликованный дескриптор — обычное чтение дерева.
            val inf = LongArray(4).also { Native.nodeInfo(Holder.h, intArrayOf(0), 1, it) }
            disk = inf[0]; items = inf[2]
            time = Freshness.treeTime(Holder.kind, Holder.time)
            scanned = Holder.kind == Kind.SCAN
            approx = Holder.kind == Kind.INDEX
        } else if (last != null) {
            disk = last.disk; items = last.items; time = last.time; scanned = true
        } else {
            val meta = Scans.meta(a, Scans.STORAGE, false)
            if (meta != null) { disk = meta.disk; items = meta.items ?: meta.files; time = meta.time }
        }
        storeTotal.text = when {
            items == null -> ""
            disk == null -> t.items(items)
            else -> "${Fmt.size(disk, t)} · ${t.items(items)}"
        }
        // Только скан общего хранилища (идёт или в очереди за обновлением другого корня).
        val running = BgScan.storageActive
        scanLine.visibility = if (running) View.VISIBLE else View.INVISIBLE
        val line = Freshness.line(t, running, if (BgScan.storageRunning) BgScan.p[1] else 0L, time, scanned, gate == Gate.POWER, approx,
            System.currentTimeMillis())
        freshTxt.visibility = View.VISIBLE
        freshTxt.text = line
        // «обновить ›» — своя цель касания: скан вручную.
        val tappable = !running && line.endsWith(Freshness.refresh(t))
        freshTxt.isClickable = tappable; freshTxt.isFocusable = tappable
        freshTxt.minHeight = if (tappable) a.dp(48) else 0
        freshTxt.setTextColor(if (tappable) C.AMBER else C.MUTED)
        freshTxt.contentDescription = if (tappable) t.s(R.string.refresh_desc) else null
        // Дельта — только к показанному дереву этого самого скана.
        val prev = last?.prevDisk
        if (shown && last != null && prev != null && Holder.kind == Kind.SCAN && Holder.time == last.time) {
            deltaTxt.text = Freshness.delta(t, last.disk - prev)
            deltaTxt.visibility = View.VISIBLE
        } else {
            deltaTxt.visibility = View.GONE
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
     * ПРИЦЕПЛЕННЫЙ к идущему фоновому скану — никогда не второй скан.
     */
    private fun open() {
        if (Holder.deleting || a.opening) return
        if (BgScan.pendingStorage()) Holder.promote()
        val shown = storageShown()
        if (shown && Holder.kind != Kind.INDEX) { a.startActivity(Intent(a, BrowserActivity::class.java)); return }
        val meta = Scans.meta(a, Scans.STORAGE, false)
        if (meta != null) {
            a.openCache(Holder.cacheFile(a, Scans.STORAGE, false).name, Scans.STORAGE, false, meta.time)
            return
        }
        if (BgScan.storageActive) { attach(); return }
        if (shown) { a.startActivity(Intent(a, BrowserActivity::class.java)); return }
        if (BgScan.start(a)) { gate = Gate.RUNNING; render(); attach() }
        else a.showAlert(t.s(R.string.scan_not_started),
            BgScan.failure?.let { NativeErr.text(t, it) } ?: t.s(R.string.no_storage_access))
    }

    private fun attach() = a.startActivity(Intent(a, ScanActivity::class.java).putExtra(EXTRA_ATTACH, true))
}
