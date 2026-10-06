package dev.ancdu

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.RippleDrawable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView

/**
 * Карточка памяти на главном экране — единственная точка входа в дерево общего хранилища:
 * statfs и ярус 0 сверху, под разделителем — «Общее хранилище <объём> · <N> эл. ›», строка
 * свежести и дельта. Всё — главный поток.
 */
class StorageCard(private val a: MainActivity) {
    private lateinit var usedTxt: TextView
    private lateinit var freeTxt: TextView
    private lateinit var bar: SegBar
    private lateinit var legend: LinearLayout
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

    /** Сама карточка: один кликабельный элемент. */
    val view: LinearLayout = build()

    private fun build(): LinearLayout = a.vbox(12).apply {
        setPadding(a.dp(18), a.dp(18), a.dp(18), a.dp(10))
        val r = a.dp(16).toFloat()
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(C.SURFACE, r), rounded(C.TEXT, r))
        isClickable = true; isFocusable = true
        contentDescription = DESC
        setOnClickListener { onTap() }
        addView(a.label("Внутренняя память · /data", 15f, C.MUTED))
        usedTxt = a.label("—", 30f, mono = true, bold = true)
        addView(usedTxt)
        bar = SegBar(a)
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        freeTxt = a.label("", 13f, C.FREE_TXT, mono = true)
        addView(freeTxt)
        legend = a.vbox(2)
        addView(legend)
        // Разделитель и под ним 2dp амберная полоса, пока идёт скан.
        addView(a.vbox().apply {
            addView(View(a).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, a.dp(1)))
            scanLine = ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(C.ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.INVISIBLE
            }
            addView(scanLine, LinearLayout.LayoutParams(MATCH_PARENT, a.dp(2)))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(a.vbox().apply {
            addView(a.hbox(8).apply {
                minimumHeight = a.dp(48)
                storeTitle = a.label("Общее хранилище", 15f, C.TEXT, bold = true)
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
        })
        permBox = a.vbox(10).apply {
            visibility = View.GONE
            setPadding(0, a.dp(4), 0, a.dp(8))
            addView(a.label("Чтобы посчитать размеры каталогов во внутренней памяти, разрешите ancdu " +
                "«Доступ ко всем файлам». ancdu ничего не отправляет и удаляет только по вашему подтверждению.",
                13f, C.MUTED))
            addView(a.action("Открыть настройки", null, true) { Perms.askFiles(a) })
        }
        addView(permBox)
    }

    fun showStatfs() {
        val s = LongArray(3)
        if (Native.statfs("/data", s) != 0) return
        val used = s[0] - s[1]
        usedTxt.text = "${Fmt.size(used)} / ${Fmt.size(s[0])}"
        freeTxt.text = "свободно ${Fmt.size(s[2])} · занято ${Fmt.pct(used, s[0])}"
        bar.used = ListMath.bar(used, s[0])
    }

    /** Сегменты яруса 0 и легенда (топ-5). */
    fun showSegs(segs: List<Seg>) {
        bar.segs = segs
        legend.removeAllViews()
        for (seg in segs.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.take(5)) {
            val sp = SpannableString("■ ${seg.label}  ${Fmt.size(seg.bytes)}")
            sp.setSpan(ForegroundColorSpan(seg.color), 0, 1, 0)
            legend.addView(a.label(sp, 13f, C.TEXT, mono = true))
        }
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
            storeTitle.text = "Открыть дерево: разрешите доступ ко всем файлам"
            storeTitle.setTextColor(C.WARN)
            storeTotal.text = ""
            storeArrow.setTextColor(C.WARN)
            freshTxt.visibility = View.GONE
            deltaTxt.visibility = View.GONE
            scanLine.visibility = View.INVISIBLE
            view.contentDescription = "$DESC: разрешите доступ ко всем файлам"
            return
        }
        permBox.visibility = View.GONE
        view.contentDescription = DESC
        storeTitle.text = "Общее хранилище"
        storeTitle.setTextColor(C.TEXT)
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
            disk == null -> "${Fmt.count(items)} эл."
            else -> "${Fmt.size(disk)} · ${Fmt.count(items)} эл."
        }
        // Только скан общего хранилища (идёт или в очереди за обновлением другого корня).
        val running = BgScan.storageActive
        scanLine.visibility = if (running) View.VISIBLE else View.INVISIBLE
        val line = Freshness.line(running, if (BgScan.storageRunning) BgScan.p[1] else 0L, time, scanned, gate == Gate.POWER, approx,
            System.currentTimeMillis())
        freshTxt.visibility = View.VISIBLE
        freshTxt.text = line
        // «обновить ›» — своя цель касания: скан вручную.
        val tappable = !running && line.endsWith(Freshness.REFRESH)
        freshTxt.isClickable = tappable; freshTxt.isFocusable = tappable
        freshTxt.minHeight = if (tappable) a.dp(48) else 0
        freshTxt.setTextColor(if (tappable) C.ACCENT else C.MUTED)
        freshTxt.contentDescription = if (tappable) "Обновить дерево общего хранилища" else null
        // Дельта — только к показанному дереву этого самого скана.
        val prev = last?.prevDisk
        if (shown && last != null && prev != null && Holder.kind == Kind.SCAN && Holder.time == last.time) {
            deltaTxt.text = Freshness.delta(last.disk - prev)
            deltaTxt.visibility = View.VISIBLE
        } else {
            deltaTxt.visibility = View.GONE
        }
    }

    /** «обновить ›»: скан вручную — энергосбережение и нагрев не мешают явной просьбе. */
    private fun refreshNow() {
        if (BgScan.storageActive) return
        if (BgScan.start(a)) gate = Gate.RUNNING
        else BgScan.failure?.let { a.showAlert("Скан не запущен", it) }
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
        else a.showAlert("Скан не запущен", BgScan.failure ?: "Нет доступа к общему хранилищу.")
    }

    private fun attach() = a.startActivity(Intent(a, ScanActivity::class.java).putExtra(EXTRA_ATTACH, true))

    companion object {
        const val DESC = "Открыть дерево общего хранилища"
    }
}
