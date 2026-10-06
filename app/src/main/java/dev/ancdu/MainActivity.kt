package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    private lateinit var usedTxt: TextView
    private lateinit var freeTxt: TextView
    private lateinit var bar: SegBar
    private lateinit var legend: LinearLayout
    private lateinit var appsBox: LinearLayout
    private lateinit var lastBox: LinearLayout
    private lateinit var body: LinearLayout
    /** Карточка — единственная точка входа в дерево общего хранилища. */
    lateinit var card: LinearLayout
        private set
    /** Нижняя строка карточки: «Общее хранилище  <объём> · <N> эл.  ›» или амберный запрос доступа. */
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
    /** Кнопка-«пилюля» su в шапке; null — su нет. */
    var pill: TextView? = null
        private set
    private var rootHint: TextView? = null
    private var rootCaches: LinearLayout? = null
    private val titles = ArrayList<String>()
    private val rootTitles = ArrayList<String>()
    /** Только наличие исполняемого su; сам su не запускается до тапа «su» или root-скана. */
    private var su = false
    /** Решение автоскана при последнем onResume (POWER — «обновить ›» вручную). */
    private var gate = Gate.FRESH
    /** Поколение загрузки яруса 0: устаревшие результаты (после onPause) отбрасываются. */
    private var gen = 0
    /** Идёт открытие кэша на Holder.io: повторные тапы игнорируются. */
    private var opening = false
    private var dialog: AlertDialog? = null
    private val ui = Handler(Looper.getMainLooper())
    private var dots = 0

    private val onBg: () -> Unit = { renderStore() }
    private val onRoot: () -> Unit = { renderPill(); renderRootHint() }
    /** «su…»: бегущие точки, пока идёт запрос root. */
    private val asking = object : Runnable {
        override fun run() {
            if (Root.state != RootState.ASKING) return
            dots = (dots + 1) % 3
            pill?.text = "su" + ".".repeat(dots + 1)
            ui.postDelayed(this, 400)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkBars()
        su = Root.suExists()
        Root.load(this)
        body = vbox(20).apply { setPadding(dp(16), dp(20), dp(16), dp(20)) }
        body.addView(header())
        body.addView(card())
        appsBox = vbox(4)
        body.addView(appsBox)
        lastBox = vbox(8)
        body.addView(lastBox)
        rootBlock()?.let { body.addView(it) }
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(body) })
        showStatfs()
    }

    override fun onResume() {
        super.onResume()
        BgScan.mainResumed = true
        BgScan.addListener(onBg)
        Root.addListener(onRoot)
        // Скан закончился, пока был открыт браузер: здесь его уже никто не держит — подставляем.
        if (Holder.pending != 0L && Holder.pendingRoot == Scans.STORAGE && Holder.browsers == 0 && !Holder.deleting)
            Holder.promote()
        gate = BgScan.maybeStart(this)
        renderLast()
        renderStore()
        renderPill()
        renderRootHint()
        val my = ++gen
        val app = applicationContext
        Thread {
            val t = try { Quotas.load(app) } catch (e: Exception) { null }
            runOnUiThread { if (t != null && !isDestroyed && my == gen) renderTier0(t) }
        }.start()
    }

    override fun onPause() {
        gen++
        // Фоновый скан не отменяется: он допишет кэш и подставит или предложит дерево сам.
        BgScan.mainResumed = false
        BgScan.removeListener(onBg)
        Root.removeListener(onRoot)
        super.onPause()
    }

    override fun onDestroy() {
        ui.removeCallbacks(asking)
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun header() = hbox(8).apply {
        addView(label("ancdu", 26f, mono = true, bold = true))
        addView(label("0.1", 12f, C.MUTED, mono = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // Только когда su есть: без него пилюли нет совсем.
        if (su) {
            pill = label("su", 12f, C.MUTED, mono = true).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44); minWidth = dp(44)
                setPadding(dp(12), 0, dp(12), 0)
                isClickable = true; isFocusable = true
                setOnClickListener { Root.request(this@MainActivity) }
            }
            addView(pill)
        }
    }

    private class PillLook(val text: String, val fg: Int, val bg: Int, val desc: String)

    private fun renderPill() {
        val p = pill ?: return
        val look = when (Root.state) {
            RootState.UNKNOWN -> PillLook("su", C.MUTED, C.SURFACE, "su: запросить root")
            RootState.ASKING -> PillLook("su…", C.TEXT, C.SURFACE, "su: идёт запрос root")
            RootState.GRANTED -> PillLook("root ✓", C.OK_TXT, C.OK_BG, "root выдан; нажмите, чтобы проверить снова")
            RootState.DENIED -> PillLook("root ✗", C.WARN, C.SURFACE, "root отклонён; нажмите, чтобы запросить снова")
        }
        p.text = look.text
        p.setTextColor(look.fg)
        p.contentDescription = look.desc
        // Касание 44dp, видимая пилюля ниже.
        p.background = InsetDrawable(rounded(look.bg, dp(999).toFloat()), 0, dp(7), 0, dp(7))
        ui.removeCallbacks(asking)
        if (Root.state == RootState.ASKING) { dots = 2; ui.postDelayed(asking, 400) }
    }

    private fun card() = vbox(12).apply {
        card = this
        setPadding(dp(18), dp(18), dp(18), dp(10))
        val r = dp(16).toFloat()
        background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF), rounded(C.SURFACE, r), rounded(C.TEXT, r))
        isClickable = true; isFocusable = true
        contentDescription = CARD_DESC
        setOnClickListener { onCard() }
        addView(label("Внутренняя память · /data", 15f, C.MUTED))
        usedTxt = label("—", 30f, mono = true, bold = true)
        addView(usedTxt)
        bar = SegBar(context)
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        freeTxt = label("", 13f, C.FREE_TXT, mono = true)
        addView(freeTxt)
        legend = vbox(2)
        addView(legend)
        // Разделитель и под ним 2dp амберная полоса, пока идёт скан.
        addView(vbox().apply {
            addView(View(context).apply { setBackgroundColor(C.LINE) }, LinearLayout.LayoutParams(MATCH_PARENT, dp(1)))
            scanLine = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
                isIndeterminate = true
                indeterminateTintList = ColorStateList.valueOf(C.ACCENT)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                visibility = View.INVISIBLE
            }
            addView(scanLine, LinearLayout.LayoutParams(MATCH_PARENT, dp(2)))
        }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        addView(vbox().apply {
            addView(hbox(8).apply {
                minimumHeight = dp(48)
                storeTitle = label("Общее хранилище", 15f, C.TEXT, bold = true)
                addView(storeTitle, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                storeTotal = label("", 13f, C.TEXT, mono = true)
                addView(storeTotal)
                storeArrow = label("›", 18f, C.MUTED)
                addView(storeArrow)
            })
            freshTxt = label("", 12f, C.MUTED, mono = true).apply {
                gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { refreshNow() }
                isClickable = false
            }
            addView(freshTxt)
            deltaTxt = label("", 12f, C.MUTED, mono = true).apply { visibility = View.GONE }
            addView(deltaTxt)
        })
        permBox = vbox(10).apply {
            visibility = View.GONE
            setPadding(0, dp(4), 0, dp(8))
            addView(label("Чтобы посчитать размеры каталогов во внутренней памяти, разрешите ancdu " +
                "«Доступ ко всем файлам». ancdu ничего не отправляет и удаляет только по вашему подтверждению.",
                13f, C.MUTED))
            addView(action("Открыть настройки", null, true) { Perms.askFiles(this@MainActivity) })
        }
        addView(permBox)
    }

    /** Есть ли в Holder дерево общего хранилища (без root). */
    private fun storageShown(): Boolean = Holder.h != 0L && Holder.root == Scans.STORAGE && !Holder.viaRoot

    /**
     * Нижняя строка карточки. Объём и элементы — из самого дерева (не statfs): показанного в
     * Holder, иначе итога скана этого процесса, иначе записи кэша.
     */
    private fun renderStore() {
        if (!::storeTitle.isInitialized) return
        if (!Perms.files()) {
            storeTitle.text = "Открыть дерево: разрешите доступ ко всем файлам"
            storeTitle.setTextColor(C.WARN)
            storeTotal.text = ""
            storeArrow.setTextColor(C.WARN)
            freshTxt.visibility = View.GONE
            deltaTxt.visibility = View.GONE
            scanLine.visibility = View.INVISIBLE
            card.contentDescription = "$CARD_DESC: разрешите доступ ко всем файлам"
            return
        }
        permBox.visibility = View.GONE
        card.contentDescription = CARD_DESC
        storeTitle.text = "Общее хранилище"
        storeTitle.setTextColor(C.TEXT)
        storeArrow.setTextColor(C.MUTED)
        val meta = Scans.meta(this, Scans.STORAGE, false)
        val last = Scans.lastStorage
        var disk: Long? = null
        var items: Long? = null
        var time: Long? = null
        var scanned = false
        var approx = false
        if (storageShown() && !Holder.deleting) {
            // Главный поток, опубликованный дескриптор — обычное чтение дерева.
            val inf = LongArray(4).also { Native.nodeInfo(Holder.h, intArrayOf(0), 1, it) }
            disk = inf[0]; items = inf[2]
            when (Holder.kind) {
                Kind.SCAN -> { time = last?.time ?: meta?.time; scanned = last != null }
                Kind.INDEX -> { approx = true; time = meta?.time }
                else -> time = meta?.time
            }
        } else if (last != null) {
            disk = last.disk; items = last.items; time = last.time; scanned = true
        } else if (meta != null) {
            disk = meta.disk; items = meta.items ?: meta.files; time = meta.time
        }
        storeTotal.text = when {
            items == null -> ""
            disk == null -> "${Fmt.count(items)} эл."
            else -> "${Fmt.size(disk)} · ${Fmt.count(items)} эл."
        }
        val running = BgScan.running
        scanLine.visibility = if (running) View.VISIBLE else View.INVISIBLE
        val line = Freshness.line(running, BgScan.p[1], time, scanned, gate == Gate.POWER, approx,
            System.currentTimeMillis())
        freshTxt.visibility = View.VISIBLE
        freshTxt.text = line
        // «обновить ›» — своя цель касания: скан вручную.
        val tappable = !running && line.endsWith(Freshness.REFRESH)
        freshTxt.isClickable = tappable; freshTxt.isFocusable = tappable
        freshTxt.minHeight = if (tappable) dp(48) else 0
        freshTxt.setTextColor(if (tappable) C.ACCENT else C.MUTED)
        freshTxt.contentDescription = if (tappable) "Обновить дерево общего хранилища" else null
        val prev = last?.prevDisk
        if (last != null && prev != null && storageShown() && Holder.kind == Kind.SCAN) {
            deltaTxt.text = Freshness.delta(last.disk - prev)
            deltaTxt.visibility = View.VISIBLE
        } else {
            deltaTxt.visibility = View.GONE
        }
    }

    /** «обновить ›»: скан вручную — энергосбережение и нагрев не мешают явной просьбе. */
    private fun refreshNow() {
        if (BgScan.running) return
        if (BgScan.start(this)) gate = Gate.RUNNING
        else BgScan.failure?.let { dialog = alert("Скан не запущен", it) }
        renderStore()
    }

    /** Тап по карточке. Без доступа — раскрыть объяснение; иначе открыть дерево. */
    private fun onCard() {
        if (!Perms.files()) {
            permBox.visibility = if (permBox.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            return
        }
        openStorage()
    }

    /**
     * Готовое живое дерево — сразу (ждущее подставляется); иначе кэш; иначе экран прогресса,
     * ПРИЦЕПЛЕННЫЙ к идущему фоновому скану — никогда не второй скан.
     */
    private fun openStorage() {
        if (Holder.deleting || opening) return
        if (Holder.pending != 0L && Holder.pendingRoot == Scans.STORAGE) Holder.promote()
        val shown = storageShown()
        if (shown && Holder.kind != Kind.INDEX) { browse(); return }
        val meta = Scans.meta(this, Scans.STORAGE, false)
        if (meta != null) {
            openCache(Holder.cacheFile(this, Scans.STORAGE, false).name, Scans.STORAGE, false, date(meta.time))
            return
        }
        if (BgScan.active) { attach(); return }
        if (shown) { browse(); return }
        if (BgScan.start(this)) { gate = Gate.RUNNING; renderStore(); attach() }
        else dialog = alert("Скан не запущен", BgScan.failure ?: "Нет доступа к общему хранилищу.")
    }

    private fun browse() = startActivity(Intent(this, BrowserActivity::class.java))

    private fun attach() = startActivity(Intent(this, ScanActivity::class.java).putExtra(EXTRA_ATTACH, true))

    /** Root внизу, приглушённо: чипы корней, подсказка после отказа, кэши root-сканов. */
    private fun rootBlock(): LinearLayout? {
        if (!su) {
            // su нет — блок нужен, только если остались кэши root-сканов.
            val prefs = getSharedPreferences(Scans.PREFS, MODE_PRIVATE)
            if (prefs.all.values.none { CacheMeta.parse(it as? String)?.su == true }) return null
        }
        return vbox(10).apply {
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = GradientDrawable().apply {
                setColor(C.BG); cornerRadius = dp(14).toFloat(); setStroke(dp(1), C.LINE)
            }
            val head = SpannableString("Root · напрямую, без FUSE, видно /data/data")
            head.setSpan(StyleSpan(Typeface.BOLD), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            head.setSpan(ForegroundColorSpan(C.TEXT), 0, 4, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            addView(label(head, 13f, C.MUTED))
            if (su) addView(hbox(8).apply {
                for (path in listOf("/data", "/", "/data/media")) {
                    addView(chip(path, false) { startScan(path, true) }.apply {
                        setTextColor(C.MUTED)
                        contentDescription = "Сканировать как root: $path"
                    }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
                }
            })
            rootHint = label("root отклонён — нажмите su вверху, чтобы запросить снова", 12f, C.MUTED).apply {
                visibility = View.GONE
            }
            addView(rootHint)
            rootCaches = vbox(8)
            addView(rootCaches)
        }
    }

    private fun renderRootHint() {
        rootHint?.visibility = if (su && Root.state == RootState.DENIED) View.VISIBLE else View.GONE
    }

    private fun showStatfs() {
        val s = LongArray(3)
        if (Native.statfs("/data", s) != 0) return
        val used = s[0] - s[1]
        usedTxt.text = "${Fmt.size(used)} / ${Fmt.size(s[0])}"
        freeTxt.text = "свободно ${Fmt.size(s[2])} · занято ${Fmt.pct(used, s[0])}"
        bar.used = ListMath.bar(used, s[0])
    }

    private fun openApps() = startActivity(Intent(this, AppsActivity::class.java))

    private fun renderTier0(t: Tier0) {
        val segs = t.segs
        if (segs != null) {
            bar.segs = segs
            legend.removeAllViews()
            for (seg in segs.filter { it.bytes > 0 }.sortedByDescending { it.bytes }.take(5)) {
                val sp = SpannableString("■ ${seg.label}  ${Fmt.size(seg.bytes)}")
                sp.setSpan(ForegroundColorSpan(seg.color), 0, 1, 0)
                legend.addView(label(sp, 13f, C.TEXT, mono = true))
            }
        }
        appsBox.removeAllViews()
        val apps = t.apps
        if (apps == null) {
            // Нет «Доступа к истории использования»: AppsActivity объясняет и ведёт в настройки.
            appsBox.addView(action("Приложения: нет доступа",
                "нужен доступ к истории использования · подробнее →", false) { openApps() })
            return
        }
        appsBox.addView(hbox().apply {
            addView(label("Приложения", 15f, bold = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label("все →", 14f, C.ACCENT).apply {
                minHeight = dp(44); gravity = Gravity.CENTER_VERTICAL; isClickable = true; isFocusable = true
                setOnClickListener { openApps() }
            })
        })
        for (a in apps.take(3)) appsBox.addView(hbox().apply {
            minimumHeight = dp(44)
            isClickable = true; isFocusable = true
            contentDescription = "${a.label}, ${Fmt.size(a.total)}"
            setOnClickListener { openApps() }
            addView(label(a.label, 15f), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(label(Fmt.size(a.total), 14f, mono = true))
        })
    }

    /** Заголовки строк «Последний скан» без root (для тестов); общее хранилище — на карточке. */
    fun lastScans(): List<String> = titles.toList()
    /** Заголовки строк кэшей root-сканов в блоке Root (для тестов). */
    fun rootScans(): List<String> = rootTitles.toList()

    private fun date(ms: Long): String =
        SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ru")).format(Date(ms))

    private fun renderLast() {
        lastBox.removeAllViews()
        rootCaches?.removeAllViews()
        titles.clear(); rootTitles.clear()
        val prefs = getSharedPreferences(Scans.PREFS, MODE_PRIVATE)
        val storage = Holder.cacheFile(this, Scans.STORAGE, false).name
        val rows = prefs.all.mapNotNull { (file, v) ->
            val m = CacheMeta.parse(v as? String) ?: return@mapNotNull null
            if (file == storage) null else file to m
        }.sortedByDescending { it.second.time }
        for ((file, m) in rows) {
            val box = (if (m.su) rootCaches else lastBox) ?: continue
            val title = "Последний скан: ${m.root}"
            val date = date(m.time)
            if (m.su) rootTitles += title else titles += title
            box.addView(action(title, "$date · ${Fmt.count(m.files)} файлов · ${m.ms} мс" + if (m.su) " · root" else "",
                false) { openCache(file, m.root, m.su, date) })
        }
    }

    /**
     * Чтение кэша — на Holder.io (FIFO с saveCache/delete/free), Holder.set — на главном потоке.
     * Экран закрылся, пока кэш читался, — новая сессия освобождается на io, Holder не трогается.
     */
    private fun openCache(name: String, root: String, su: Boolean, date: String) {
        if (opening) return
        opening = true
        val app = applicationContext
        val file = File(filesDir, name)
        Holder.io.execute {
            val err = IntArray(1)
            val h = try { Native.openCache(file.path, err) } catch (e: Exception) { 0L }
            if (h == 0L) {
                app.getSharedPreferences(Scans.PREFS, MODE_PRIVATE).edit().remove(name).commit()
                file.delete()
            }
            runOnUiThread {
                opening = false
                if (isDestroyed || isFinishing) {
                    if (h != 0L) Holder.io.execute { Native.free(h) }
                    return@runOnUiThread
                }
                if (h == 0L) {
                    dialog = alert("Кэш повреждён", "Файл кэша удалён, запустите скан заново.")
                    renderLast()
                    renderStore()
                    return@runOnUiThread
                }
                Holder.set(h, Kind.CACHE, root, "кэш от $date", su)
                startActivity(Intent(this, BrowserActivity::class.java))
            }
        }
    }

    private fun startScan(root: String, su: Boolean) {
        startActivity(Intent(this, ScanActivity::class.java).putExtra(EXTRA_ROOT, root).putExtra(EXTRA_SU, su))
    }

    companion object {
        const val CARD_DESC = "Открыть дерево общего хранилища"
    }
}
