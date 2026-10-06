package dev.ancdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Фоновый скан общего хранилища без root: кэш показывается сразу, свежее дерево подставляется,
 * когда готово (stale-while-revalidate). Всё состояние — только главный поток.
 *
 * Дескриптор идущего скана ПРИВАТЕН до Holder.set/offer: пока скан идёт, на нём вызывается
 * только Native.progress (атомики) из [poll]. После терминального состояния владелец (этот
 * объект, на главном потоке) читает итог — Native.error или корень дерева в Scans.finish — и
 * сразу публикует дескриптор или освобождает его на Holder.io. onPause скан не отменяет.
 */
object BgScan {
    const val ROOT = Scans.STORAGE
    /** Скан дольше этого при отсутствии кэша — карточка показывает приблизительный индекс. */
    const val INDEX_AFTER_MS = 2000L

    private val ui = Handler(Looper.getMainLooper())
    private var h = 0L
    private var app: Context? = null
    /** Удаление шло, пока скан шёл, — итог мог увидеть полуудалённое: выбросить и пересканировать. */
    private var dirty = false
    /** Пересканировать, когда закончится идущее удаление. */
    private var rescan = false
    private var indexing = false

    /** Последний progress идущего (или последнего) скана — копия для экранов. */
    val p = LongArray(6)
    /** Текущий путь идущего скана (из того же progress). */
    var path = ""; private set
    /** Текст ошибки последнего скана; null — последний закончился удачно или идёт. */
    var failure: String? = null; private set

    val running: Boolean get() = h != 0L
    /** Идёт скан или ждёт пересканирование после удаления. */
    val active: Boolean get() = h != 0L || rescan

    /** MainActivity между onResume и onPause. */
    var mainResumed = false
    /** Сколько ScanActivity ждут этот скан (режим attach). */
    var attached = 0
    /** Для тестов: false — [maybeStart] только считает решение, скан не запускает. */
    @Volatile var auto = true

    private val listeners = ArrayList<() -> Unit>()
    /** Слушатели — живые экраны; вызываются на каждом тике и при любой смене состояния. */
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    /** Уведомить экраны (в том числе после Holder.offer — сам он слушателей не зовёт). */
    fun changed() { for (l in listeners.toList()) l() }

    private fun cacheAge(ctx: Context): Long? =
        Scans.meta(ctx, ROOT, false)?.let { System.currentTimeMillis() - it.time }

    fun gate(ctx: Context): Gate {
        val pm = ctx.getSystemService(PowerManager::class.java)
        return ScanGate.decide(Perms.files(), cacheAge(ctx), pm?.isPowerSaveMode == true,
            pm?.currentThermalStatus ?: 0, running)
    }

    /** onResume главного экрана: запускает скан, если [gate] разрешает. Возвращает решение. */
    fun maybeStart(ctx: Context): Gate {
        val g = gate(ctx)
        if (g == Gate.START && auto) start(ctx)
        return g
    }

    /** Ручной или автоматический запуск. false — не запущен (уже идёт, удаление, нет доступа, ошибка). */
    fun start(ctx: Context): Boolean {
        if (running || Holder.deleting || !Perms.files()) return false
        app = ctx.applicationContext
        val err = IntArray(1)
        val nh = Native.scanStart(ROOT, true, 0, err)
        if (nh == 0L) {
            failure = "Скан не запущен: код ошибки ${err[0]}"
            changed()
            return false
        }
        h = nh; dirty = false; rescan = false; failure = null; path = ""; p.fill(0)
        ui.post(poll)
        changed()
        return true
    }

    private val poll = object : Runnable {
        override fun run() {
            val cur = h
            if (cur == 0L) return
            path = Native.str(Native.progress(cur, p))
            when (p[0].toInt()) {
                ST_RUNNING -> {
                    if (p[4] >= INDEX_AFTER_MS && noTree()) startIndex()
                    changed()
                    ui.postDelayed(this, 100)
                }
                ST_DONE, ST_FULL -> done(cur)
                else -> failed(cur)
            }
        }
    }

    private fun log(state: String) = Log.i("ancdu", "bgscan state=$state files=${p[1]} ms=${p[4]}")

    private fun done(cur: Long) {
        h = 0L
        log("done")
        if (dirty) { discard(cur); return }
        val ctx = app ?: return discard(cur)
        val d = Scans.finish(ctx, cur, ROOT, false, p)
        publish(cur, Kind.SCAN, "скан" + d.suffix)
        changed()
    }

    private fun failed(cur: Long) {
        h = 0L
        log("failed")
        // Скан закончился — владелец читает текст ошибки, затем дескриптор уходит на io.
        failure = Native.str(Native.error(cur)).ifEmpty { "неизвестная ошибка" }
        Holder.io.execute { Native.free(cur) }
        if (dirty) { rescanSoon(); return }
        if (noTree()) startIndex()
        changed()
    }

    /** «Грязный» итог: освобождается, скан повторяется (после удаления, если оно ещё идёт). */
    private fun discard(cur: Long) {
        Holder.io.execute { Native.free(cur) }
        rescanSoon()
    }

    private fun rescanSoon() {
        rescan = true
        if (!Holder.deleting) restart()
        changed()
    }

    private fun restart() {
        rescan = false
        val ctx = app
        if (ctx == null || !start(ctx)) changed()
    }

    /**
     * Подставить готовое дерево: главный экран (или ждущий ScanActivity) на виду и ни один
     * браузер не держит дескриптор — сразу Holder.set; иначе — Holder.offer (без рывка дерева).
     */
    private fun publish(handle: Long, kind: Kind, label: String) {
        if ((mainResumed || attached > 0) && Holder.browsers == 0 && !Holder.deleting) {
            Holder.set(handle, kind, ROOT, label, false)
            if (Holder.pending != 0L && Holder.pendingRoot == ROOT) Holder.dropPending()
        } else {
            Holder.offer(handle, kind, ROOT, label, false)
        }
    }

    /** Есть ли что показать на карточке, кроме идущего скана: дерево, ожидание или кэш. */
    private fun noTree(): Boolean {
        val ctx = app ?: return false
        return !(Holder.h != 0L && Holder.root == ROOT) && !(Holder.pending != 0L && Holder.pendingRoot == ROOT) &&
            Scans.meta(ctx, ROOT, false) == null
    }

    /**
     * Скрытый запасной путь: индекс MediaStore (≈1 с, приблизительно), если скан не удался или
     * идёт дольше [INDEX_AFTER_MS] без кэша. Дескриптор строится на своём потоке и до публикации
     * никому не виден; к моменту готовности настоящий скан уже подставлен — индекс освобождается.
     */
    private fun startIndex() {
        if (indexing) return
        val ctx = app ?: return
        indexing = true
        Thread({
            val res = runCatching { MediaIndex.build(ctx) }
            ui.post {
                indexing = false
                val ih = res.getOrNull() ?: 0L
                if (ih == 0L) {
                    Log.i("ancdu", "bgscan index failed: ${res.exceptionOrNull()?.message}")
                    return@post
                }
                if (!noTree()) { Holder.io.execute { Native.free(ih) }; return@post }
                publish(ih, Kind.INDEX, "индекс · приблизительно")
                changed()
            }
        }, "ancdu-index").apply { isDaemon = true }.start()
    }

    /**
     * Holder.delete, главный поток, до постановки удаления на io. Идущий скан станет «грязным»;
     * непоказанное дерево общего хранилища уже устарело — выбрасывается, пересканирование после
     * удаления.
     */
    fun deleteStarted() {
        if (running) dirty = true
        if (Holder.pending != 0L && Holder.pendingRoot == ROOT) {
            Holder.dropPending()
            rescan = true
        }
    }

    /** Holder.delete завершилось (главный поток): отложенное пересканирование. */
    fun deleteFinished() {
        if (rescan && !running) restart()
    }
}
