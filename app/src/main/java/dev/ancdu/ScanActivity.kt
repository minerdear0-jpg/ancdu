package dev.ancdu

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast

const val EXTRA_ROOT = "root"
const val EXTRA_SU = "su"
/** Не запускать свой скан, а ждать идущий фоновый ([BgScan]) — никогда не второй скан. */
const val EXTRA_ATTACH = "attach"

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
    /** Экран прогресса построен (скан идёт ≥ [SHOW_AFTER_MS]); до этого — только фон. */
    var built = false
        private set
    /** Для тестов: число файлов, показанное последним обновлением. */
    val files: Long get() = if (attach) BgScan.p[1] else p[1]
    /** Режим attach: прогресс — из BgScan (его дескриптор приватен), Native этот экран не зовёт. */
    var attach = false
        private set
    private var attachedReg = false
    private val onBg: () -> Unit = { attachedTick() }

    private val liveSrc = object : RowSource {
        override val count get() = liveN
        override fun bind(index: Int, row: Row) {
            val nd = liveNodes[index]
            row.name = if (nd < 0) tx.s(R.string.scan_other)
                       else if (Holder.h != handle) "" else Native.str(Native.liveName(handle, nd))
            row.size = Fmt.size(liveDisk[index], tx)
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
        attach = intent.getBooleanExtra(EXTRA_ATTACH, false)
        if (attach) {
            root = BgScan.ROOT; su = false
            setContentView(View(this).apply { setBackgroundColor(C.BG) })
            BgScan.attached++; attachedReg = true
            BgScan.addListener(onBg)
            attachedTick()
            return
        }
        val started = savedInstanceState != null
        val saved = savedInstanceState?.getLong("h") ?: 0L
        val resumed = started && saved != 0L && Holder.h == saved &&
            Holder.root == root && Holder.kind == (if (su) Kind.ROOT else Kind.SCAN)
        if (resumed) handle = saved
        // Пересоздан, а скана уже нет (закончился с ошибкой/отменой или процесс перезапущен) —
        // назад к главному, а не новый скан (root-скан снова спросил бы Magisk).
        if (started && !resumed) { finished = true; finish(); return }
        // Только фон: короткий скан не мигает экраном прогресса; он строится в update() через 400 мс.
        setContentView(View(this).apply { setBackgroundColor(C.BG) })
        if (!resumed && !start()) return
        ui.post(tick)
    }

    private fun start(): Boolean {
        val err = IntArray(1)
        val h = if (su) Native.rootStart(Root.helper(this), root, true, Root.memfdAllowed(this), err)
                else Native.scanStart(root, true, 0, err)
        if (h == 0L) {
            finished = true
            val f = ScanFail(err[0]).also { NativeErr.log("scan start", it) }
            failure = alert(tx.s(R.string.scan_not_started), NativeErr.text(tx, f), onDismiss = ::leave)
            return false
        }
        handle = h
        Holder.set(h, if (su) Kind.ROOT else Kind.SCAN, root, su)
        return true
    }

    override fun onSaveInstanceState(out: Bundle) {
        super.onSaveInstanceState(out)
        out.putLong("h", handle)
    }

    private fun buildUi() {
        built = true
        val pad = dp(16)
        val t = tx
        val head = vbox(4).apply {
            addView(label(t.s(R.string.scanning, if (su) "root" else t.s(R.string.no_root)), 13f, C.MUTED))
            addView(label(root, 22f, mono = true, bold = true))
        }
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            indeterminateTintList = android.content.res.ColorStateList.valueOf(C.ACCENT)
        }
        val grid = GridLayout(this).apply { columnCount = 2 }
        val names = arrayOf(t.s(R.string.tile_files), t.s(R.string.tile_size), t.s(R.string.tile_speed), t.s(R.string.tile_time))
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
        val cancel = if (attach) action(t.s(R.string.close), t.s(R.string.scan_continues), false) { finished = true; finish() }
                     else action(t.s(R.string.cancel), null, false) { abort(true) }
        setContentView(vbox(16).apply {
            setBackgroundColor(C.BG)
            setPadding(pad, dp(28), pad, dp(24))
            addView(head)
            addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(grid, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(vbox(6).apply {
                setPadding(pad, pad, pad, pad)
                background = rounded(C.SURFACE, dp(14).toFloat())
                addView(label(t.s(R.string.scan_now), 13f, C.MUTED)); addView(cur)
            })
            if (!attach) addView(label(t.s(R.string.scan_largest), 13f, C.MUTED))
            addView(live, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(cancel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        })
    }

    private fun update() {
        val h = handle
        // Сессию сменили или освободили не мы — её дескриптор больше не наш.
        if (Holder.h != h) { finished = true; finish(); return }
        val path = Native.progress(h, p)
        if (!built && p[0] == ST_RUNNING.toLong() && p[4] >= SHOW_AFTER_MS) buildUi()
        if (built) render(h, path)
        when (p[0].toInt()) {
            ST_RUNNING -> {}
            ST_DONE, ST_FULL -> done(h)
            ST_CANCELLED -> { log(); drop(); toast(tx.s(R.string.scan_cancelled)); finish() }
            else -> {
                log()
                val f = ScanFail(0, Native.str(Native.error(h))).also { NativeErr.log("scan", it) }
                drop()
                failure = alert(tx.s(R.string.scan_failed),
                    NativeErr.text(tx, f) + if (su) "\n\n" + tx.s(R.string.scan_root_hint) else "",
                    onDismiss = ::leave)
            }
        }
    }

    private fun renderTiles(p: LongArray, path: String) {
        val secs = maxOf(p[4], 1) / 1000.0
        val t = tx
        tiles[0].text = Fmt.count(p[1], t.locale)
        tiles[1].text = Fmt.size(p[2], t)
        tiles[2].text = t.s(R.string.rate_per_s, Fmt.count((p[1] / secs).toLong(), t.locale))
        tiles[3].text = Fmt.timer(p[4], t.locale)
        cur.text = path
    }

    /**
     * Режим attach: каждый тик BgScan. Скан идёт — плитки из его копии progress; закончился —
     * дерево (подставленное или ждущее — тогда promote) открывается в браузере, иначе ошибка.
     */
    private fun attachedTick() {
        if (finished) return
        // Ждём только скан общего хранилища (он может стоять в очереди за обновлением другого корня).
        if (BgScan.storageActive) {
            if (BgScan.storageRunning) {
                if (!built && BgScan.p[4] >= SHOW_AFTER_MS) buildUi()
                if (built) renderTiles(BgScan.p, BgScan.path)
            }
            return
        }
        finished = true
        if (BgScan.pendingStorage() && !Holder.deleting) Holder.promote()
        if (Holder.h != 0L && Holder.root == BgScan.ROOT && !Holder.viaRoot) {
            startActivity(Intent(this, BrowserActivity::class.java))
            finish()
        } else {
            failure = alert(tx.s(R.string.scan_failed),
                BgScan.failure?.let { NativeErr.text(tx, it) } ?: tx.s(R.string.unknown_error), onDismiss = ::leave)
        }
    }

    private fun render(h: Long, path: ByteArray) {
        renderTiles(p, Native.str(path))
        liveN = Native.liveTop(h, liveNodes, liveDisk)
        sortLive()
        live.refresh()
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
        if (notify) toast(tx.s(R.string.scan_cancelled))
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
        if (su) { Root.rememberMemfd(this, p[5] == 1L); Root.granted(this) }
        // Кэш и запись «caches» — на io (FIFO с delete и free этого же дескриптора), см. Scans.finish.
        val d = Scans.finish(this, h, root, su, p)
        // Итог скана — в плашку браузера: «скан · 0,2 с». Тот же дескриптор: только поля.
        Holder.set(h, Holder.kind, root, su, d.time, d.ms)
        startActivity(Intent(this, BrowserActivity::class.java))
        finish()
    }

    companion object {
        const val SHOW_AFTER_MS = 400L
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        if (finished || attach) { finished = true; finish() } else abort(true)
    }

    override fun onDestroy() {
        ui.removeCallbacks(tick)
        failure?.dismiss()
        if (attachedReg) {
            // Фоновый скан не наш: не отменяется, просто перестаём ждать.
            attachedReg = false
            BgScan.attached--
            BgScan.removeListener(onBg)
            finished = true
        }
        // Закрыли иначе (например, смахнули задачу) посреди скана — не оставляем его и su висеть.
        if (isFinishing && !finished) abort(false)
        super.onDestroy()
    }
}
