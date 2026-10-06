package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.LinearLayout
import android.widget.ScrollView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {
    /** Карточка памяти — вход в дерево общего хранилища. */
    lateinit var storage: StorageCard
        private set
    /** Пилюля su и блок Root. */
    lateinit var rootPanel: RootPanel
        private set
    private lateinit var appsBox: LinearLayout
    private lateinit var lastBox: LinearLayout
    private val titles = ArrayList<String>()
    private val rootTitles = ArrayList<String>()
    /** Поколение загрузки яруса 0: устаревшие результаты (после onPause) отбрасываются. */
    private var gen = 0
    /** Идёт открытие кэша на Holder.io: повторные тапы игнорируются. */
    var opening = false
        private set
    private var dialog: AlertDialog? = null

    /** Тик/итог фонового скана или снятие закрепления браузером: подставить ждущее, перерисовать. */
    private val onBg: () -> Unit = { BgScan.promoteOnMain(); storage.render() }
    private val onRoot: () -> Unit = { rootPanel.render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkBars()
        Root.load(this)
        val prefs = getSharedPreferences(Scans.PREFS, MODE_PRIVATE)
        val rootCaches = prefs.all.values.any { CacheMeta.parse(it as? String)?.su == true }
        rootPanel = RootPanel(this, Root.suExists(), rootCaches)
        storage = StorageCard(this)
        val body = vbox(20).apply { setPadding(dp(16), dp(20), dp(16), dp(20)) }
        body.addView(header())
        body.addView(storage.view)
        appsBox = vbox(4)
        body.addView(appsBox)
        lastBox = vbox(8)
        body.addView(lastBox)
        rootPanel.block?.let { body.addView(it) }
        setContentView(ScrollView(this).apply { setBackgroundColor(C.BG); addView(body) })
        storage.showStatfs()
    }

    override fun onResume() {
        super.onResume()
        BgScan.mainResumed = true
        BgScan.addListener(onBg)
        Root.addListener(onRoot)
        // Скан закончился, пока был открыт браузер: здесь его уже никто не держит — подставляем.
        BgScan.promoteOnMain()
        storage.gate = BgScan.maybeStart(this)
        renderLast()
        storage.render()
        rootPanel.render()
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
        rootPanel.destroy()
        dialog?.dismiss()
        dialog = null
        super.onDestroy()
    }

    fun showAlert(title: String, msg: String) { dialog = alert(title, msg) }

    private fun header() = hbox(8).apply {
        addView(label("ancdu", 26f, mono = true, bold = true))
        addView(label("0.1", 12f, C.MUTED, mono = true), LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        // Только когда su есть: без него пилюли нет совсем.
        rootPanel.pill?.let { addView(it) }
    }

    private fun openApps() = startActivity(Intent(this, AppsActivity::class.java))

    private fun renderTier0(t: Tier0) {
        t.segs?.let { storage.showSegs(it) }
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
        rootPanel.caches?.removeAllViews()
        titles.clear(); rootTitles.clear()
        val prefs = getSharedPreferences(Scans.PREFS, MODE_PRIVATE)
        val storageName = Holder.cacheFile(this, Scans.STORAGE, false).name
        val rows = prefs.all.mapNotNull { (file, v) ->
            val m = CacheMeta.parse(v as? String) ?: return@mapNotNull null
            if (file == storageName) null else file to m
        }.sortedByDescending { it.second.time }
        for ((file, m) in rows) {
            val box = (if (m.su) rootPanel.caches else lastBox) ?: continue
            val title = "Последний скан: ${m.root}"
            if (m.su) rootTitles += title else titles += title
            box.addView(action(title, "${date(m.time)} · ${Fmt.count(m.files)} файлов · ${m.ms} мс" +
                if (m.su) " · root" else "", false) { openCache(file, m.root, m.su, m.time) })
        }
    }

    /**
     * Чтение кэша — на Holder.io (FIFO с saveCache/delete/free), Holder.set — на главном потоке.
     * Экран закрылся, пока кэш читался, — новая сессия освобождается на io, Holder не трогается.
     * [time] — время скана из записи «caches»: оно и становится временем дерева (Holder.time).
     */
    fun openCache(name: String, root: String, su: Boolean, time: Long) {
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
                    showAlert("Кэш повреждён", "Файл кэша удалён, запустите скан заново.")
                    renderLast()
                    storage.render()
                    return@runOnUiThread
                }
                Holder.set(h, Kind.CACHE, root, "кэш от ${date(time)}", su, time)
                startActivity(Intent(this, BrowserActivity::class.java))
            }
        }
    }
}
