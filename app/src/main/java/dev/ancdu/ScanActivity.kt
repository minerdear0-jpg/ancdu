package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

const val EXTRA_ROOT = "root"
const val EXTRA_SU = "su"

class ScanActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tiles: Array<TextView>
    private lateinit var cur: TextView
    private lateinit var live: NcduListView
    private val p = LongArray(6)
    private val liveNodes = IntArray(256)
    private val liveDisk = LongArray(256)
    private var liveN = 0
    private var root = ""
    private var su = false
    /** Сессия этого экрана; переживает пересоздание через Bundle. Native — только пока она в Holder. */
    private var handle = 0L
    /** Скан для этого экрана закончен (любым исходом): тик остановлен, «назад» просто закрывает. */
    private var finished = false
    /** Для тестов: диалог ошибки, если показан. */
    var failure: AlertDialog? = null
        private set
    /** Для тестов: число файлов, показанное последним обновлением. */
    val files: Long get() = p[1]

    private val liveSrc = object : RowSource {
        override val count get() = liveN
        override fun bind(index: Int, row: Row) {
            val nd = liveNodes[index]
            row.name = if (nd < 0) "…прочее"
                       else if (Holder.h != handle) "" else Native.str(Native.liveName(handle, nd))
            row.size = Fmt.size(liveDisk[index])
            row.bar = ListMath.bar(liveDisk[index], liveDisk[0])
            row.pct = Fmt.pct(liveDisk[index], p[2])
            row.desc = "${row.name}, ${row.size}"
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (finished) return
            update()
            ui.postDelayed(this, 100)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkBars()
        root = intent.getStringExtra(EXTRA_ROOT) ?: "/storage/emulated/0"
        su = intent.getBooleanExtra(EXTRA_SU, false)
        val started = savedInstanceState != null
        val saved = savedInstanceState?.getLong("h") ?: 0L
        val resumed = started && saved != 0L && Holder.h == saved &&
            Holder.root == root && Holder.kind == (if (su) Kind.ROOT else Kind.SCAN)
        if (resumed) handle = saved
        // Пересоздан, а скана уже нет (закончился с ошибкой/отменой или процесс перезапущен) —
        // назад к главному, а не новый скан (root-скан снова спросил бы Magisk).
        if (started && !resumed) { finished = true; finish(); return }
        if (!resumed && !start()) return
        buildUi()
        ui.post(tick)
    }

    private fun start(): Boolean {
        val err = IntArray(1)
        val h = if (su) Native.rootStart(Root.helper(this), root, true, Root.memfdAllowed(this), err)
                else Native.scanStart(root, true, 0, err)
        if (h == 0L) {
            finished = true
            failure = alert("Скан не запущен", "Код ошибки ${err[0]}", onDismiss = ::leave)
            return false
        }
        handle = h
        Holder.set(h, if (su) Kind.ROOT else Kind.SCAN, root, if (su) "root · скан" else "скан", su)
        return true
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLong("h", handle)
    }

    private fun buildUi() {
        val pad = dp(16)
        val head = vbox(4).apply {
            addView(label("Сканирование · " + (if (su) "root" else "без root"), 13f, C.MUTED))
            addView(label(root, 22f, mono = true, bold = true))
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
        }
        val grid = GridLayout(this).apply { columnCount = 2 }
        val names = arrayOf("Файлов", "Объём", "Скорость", "Время")
        tiles = Array(4) { label("—", 24f, mono = true, bold = true) }
        for (i in 0 until 4) {
            val cell = vbox(6).apply {
                setPadding(pad, pad, pad, pad)
                background = rounded(C.SURFACE, dp(14).toFloat())
                addView(label(names[i], 13f, C.MUTED))
                addView(tiles[i])
            }
            grid.addView(cell, GridLayout.LayoutParams(
                GridLayout.spec(i / 2, 1f), GridLayout.spec(i % 2, 1f)).apply {
                width = 0; setMargins(dp(4), dp(4), dp(4), dp(4))
            })
        }
        cur = label("", 13f, mono = true).apply { maxLines = 3; ellipsize = TextUtils.TruncateAt.START }
        live = NcduListView(this).apply { source = liveSrc }
        val cancel = action("Отмена", null, false) { abort(true) }
        setContentView(vbox(16).apply {
            setBackgroundColor(C.BG)
            setPadding(pad, dp(28), pad, dp(24))
            addView(head)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(vbox(6).apply {
                setPadding(pad, pad, pad, pad)
                background = rounded(C.SURFACE, dp(14).toFloat())
                addView(label("Сейчас", 13f, C.MUTED)); addView(cur)
            })
            addView(label("Крупнейшие пока", 13f, C.MUTED))
            addView(live, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(cancel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    private fun update() {
        val h = handle
        // Сессию сменили или освободили не мы — её дескриптор больше не наш.
        if (Holder.h != h) { finished = true; finish(); return }
        val path = Native.progress(h, p)
        val secs = maxOf(p[4], 1) / 1000.0
        tiles[0].text = Fmt.count(p[1])
        tiles[1].text = Fmt.size(p[2])
        tiles[2].text = Fmt.count((p[1] / secs).toLong()) + "/с"
        tiles[3].text = String.format(java.util.Locale.ROOT, "%02d:%04.1f", p[4] / 60000, (p[4] % 60000) / 1000.0)
        cur.text = Native.str(path)
        liveN = Native.liveTop(h, liveNodes, liveDisk)
        sortLive()
        live.refresh()
        when (p[0].toInt()) {
            ST_RUNNING -> {}
            ST_DONE, ST_FULL -> done(h)
            ST_CANCELLED -> { log(); drop(); toast("Скан отменён"); finish() }
            else -> {
                log()
                val msg = Native.str(Native.error(h)).ifEmpty { "неизвестная ошибка" }
                drop()
                failure = alert("Скан не удался",
                    msg + if (su) "\n\nRoot отклонён? Попробуйте скан без root." else "",
                    onDismiss = ::leave)
            }
        }
    }

    private fun sortLive() {
        val idx = (0 until liveN).sortedByDescending { liveDisk[it] }
        val n2 = IntArray(liveN) { liveNodes[idx[it]] }
        val d2 = LongArray(liveN) { liveDisk[idx[it]] }
        n2.copyInto(liveNodes); d2.copyInto(liveDisk)
    }

    private fun log() {
        Log.i("ancdu", "scan root=$root su=${if (su) 1 else 0} memfd=${p[5]} state=${p[0]} files=${p[1]} ms=${p[4]}")
    }

    /** Скан этому экрану больше не нужен: тик стоп, список отцеплен, сессия освобождается на Holder.io. */
    private fun drop() {
        finished = true
        if (::live.isInitialized) live.source = null
        if (Holder.h == handle) Holder.clear()
    }

    /**
     * Отмена пользователем. Не ждём ST_CANCELLED: root-скан с висящим запросом Magisk не завершится,
     * пока su не выйдет. cancel закрывает stdin хелпера, free на Holder.io дожидается выхода su.
     */
    private fun abort(notify: Boolean) {
        if (Holder.h == handle) Native.cancel(handle)
        p[0] = ST_CANCELLED.toLong()
        log()
        drop()
        if (notify) toast("Скан отменён")
        finish()
    }

    /** Закрытие диалога ошибки любым путём — назад к главному (но не при пересоздании). */
    private fun leave() {
        failure = null
        if (!isChangingConfigurations) finish()
    }

    private fun toast(s: String) = Toast.makeText(applicationContext, s, Toast.LENGTH_SHORT).show()

    private fun done(h: Long) {
        finished = true
        log()
        if (su) Root.rememberMemfd(this, p[5] == 1L)
        val app: Context = applicationContext
        val file = Holder.cacheFile(app, root, su)
        val meta = "$root|$su|${p[1]}|${p[4]}|${System.currentTimeMillis()}"
        // На io: FIFO с delete и free этого же дескриптора (контракт Native).
        Holder.io.execute {
            if (Native.saveCache(h, file.path) == 0)
                app.getSharedPreferences("caches", Context.MODE_PRIVATE).edit().putString(file.name, meta).apply()
        }
        startActivity(Intent(this, BrowserActivity::class.java))
        finish()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (finished) finish() else abort(true)
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        failure?.dismiss()
        // Закрыли иначе (например, смахнули задачу) посреди скана — не оставляем его и su висеть.
        if (isFinishing && !finished) abort(false)
        super.onDestroy()
    }
}
