package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
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
    private val titles = ArrayList<String>()
    /** Только наличие исполняемого su; сам su не запускается до первого root-скана. */
    private var su = false
    /** Поколение загрузки яруса 0: устаревшие результаты (после onPause) отбрасываются. */
    private var gen = 0
    /** Идёт открытие кэша на Holder.io: повторные тапы игнорируются. */
    private var opening = false
    private var dialog: AlertDialog? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkBars()
        su = Root.suExists()
        body = vbox(20).apply { setPadding(dp(16), dp(20), dp(16), dp(20)) }
        body.addView(header())
        body.addView(card())
        body.addView(action("Сканировать хранилище", "/storage/emulated/0 · без root", true) { scanStorage() })
        body.addView(rootBlock())
        lastBox = vbox(8)
        body.addView(lastBox)
        appsBox = vbox(4)
        body.addView(appsBox)
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(body) })
        showStatfs()
    }

    override fun onResume() {
        super.onResume()
        renderLast()
        val my = ++gen
        val app = applicationContext
        Thread {
            val t = try { Quotas.load(app) } catch (e: Exception) { null }
            runOnUiThread { if (t != null && !isDestroyed && my == gen) renderTier0(t) }
        }.start()
    }

    override fun onPause() {
        gen++
        super.onPause()
    }

    override fun onDestroy() {
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    private fun header() = hbox(8).apply {
        addView(label("ancdu", 26f, mono = true, bold = true))
        addView(label("0.1", 12f, C.MUTED, mono = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        addView(label(if (su) "root: есть su" else "root: нет", 12f, if (su) C.OK_TXT else C.MUTED, mono = true).apply {
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = rounded(if (su) C.OK_BG else C.SURFACE, dp(999).toFloat())
        })
    }

    private fun card() = vbox(12).apply {
        setPadding(dp(18), dp(18), dp(18), dp(18))
        background = rounded(C.SURFACE, dp(16).toFloat())
        addView(label("Внутренняя память · /data", 15f, C.MUTED))
        usedTxt = label("—", 30f, mono = true, bold = true)
        addView(usedTxt)
        bar = SegBar(context)
        addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        freeTxt = label("", 13f, C.FREE_TXT, mono = true)
        addView(freeTxt)
        legend = vbox(2)
        addView(legend)
    }

    private fun rootBlock() = vbox(12).apply {
        setPadding(dp(18), dp(14), dp(18), dp(14))
        background = rounded(C.SURFACE, dp(14).toFloat())
        addView(label("Сканировать как root", 16f, bold = true))
        addView(label("напрямую, без FUSE, видно /data/data", 12f, C.MUTED))
        addView(hbox(8).apply {
            for ((t, path) in listOf("/data" to "/data", "/" to "/", "media/0" to "/data/media/0")) {
                addView(chip(t, false) { if (su) startScan(path, true) }.apply {
                    isEnabled = su; alpha = if (su) 1f else 0.4f
                    contentDescription = "Сканировать как root: $path"
                }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            }
        })
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

    /** Заголовки строк «Последний скан» (для тестов). */
    fun lastScans(): List<String> = titles.toList()

    private fun renderLast() {
        lastBox.removeAllViews()
        titles.clear()
        val prefs = getSharedPreferences("caches", Context.MODE_PRIVATE)
        val fmt = SimpleDateFormat("dd.MM HH:mm", Locale.forLanguageTag("ru"))
        val rows = prefs.all.mapNotNull { (file, v) ->
            val parts = (v as? String)?.split('|') ?: return@mapNotNull null
            if (parts.size < 5) return@mapNotNull null
            val files = parts[2].toLongOrNull() ?: return@mapNotNull null
            val time = parts[4].toLongOrNull() ?: return@mapNotNull null
            Triple(file, parts, files to time)
        }.sortedByDescending { it.third.second }
        for ((file, parts, nums) in rows) {
            val (root, su, _, ms) = parts
            val (files, time) = nums
            val title = "Последний скан: $root"
            val date = fmt.format(Date(time))
            titles += title
            lastBox.addView(action(title, "$date · ${Fmt.count(files)} файлов · $ms мс" +
                if (su == "true") " · root" else "", false) { openCache(file, root, su == "true", date) })
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
                app.getSharedPreferences("caches", Context.MODE_PRIVATE).edit().remove(name).commit()
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
                    return@runOnUiThread
                }
                Holder.set(h, Kind.CACHE, root, "кэш от $date", su)
                startActivity(Intent(this, BrowserActivity::class.java))
            }
        }
    }

    private fun scanStorage() {
        if (!Perms.files()) {
            dialog = alert("Нужен доступ ко всем файлам",
                "Чтобы посчитать размеры каталогов во внутренней памяти, разрешите ancdu «Доступ ко всем файлам». " +
                    "ancdu ничего не отправляет и удаляет только по вашему подтверждению.",
                ok = "Открыть настройки", cancel = "Отмена") { Perms.askFiles(this) }
            return
        }
        startScan("/storage/emulated/0", false)
    }

    private fun startScan(root: String, su: Boolean) {
        startActivity(Intent(this, ScanActivity::class.java).putExtra(EXTRA_ROOT, root).putExtra(EXTRA_SU, su))
    }
}
