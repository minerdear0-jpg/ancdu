package dev.ancdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

/**
 * Фоновый скан общего хранилища без root: кэш показывается сразу, свежее дерево подставляется,
 * когда готово (stale-while-revalidate). Он же обновляет показанное браузером дерево любого корня
 * в том же режиме su ([refresh], [deleteFinished]). Всё состояние — только главный поток.
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

    private val STORAGE = ScanTarget.STORAGE

    private val ui = Handler(Looper.getMainLooper())
    private var h = 0L
    /** Цель идущего (или последнего) скана. */
    private var cur = STORAGE
    private var app: Context? = null
    /** Удаление шло, пока скан шёл, — итог мог увидеть полуудалённое: выбросить и пересканировать. */
    private var dirty = false
    /**
     * Очередь сканов: после идущего удаления или следом за идущим сканом другой цели. FIFO без
     * повторов — постановка не вытесняет ждущие цели (обновление после удаления не теряется
     * из-за второго удаления или скана главного экрана). su ставится только с согласия
     * ([Swap.autoRoot] или прямой долгий тап через [refresh]).
     */
    private val queue = ScanQueue()
    private var indexing = false

    /** Последний progress идущего (или последнего) скана — копия для экранов. */
    val p = LongArray(6)
    /** Текущий путь идущего скана (из того же progress). */
    var path = ""; private set
    /** Ошибка последнего скана (текст — [NativeErr.text]); null — последний закончился удачно или идёт. */
    var failure: ScanFail? = null; private set

    val running: Boolean get() = h != 0L
    /** Идёт скан именно общего хранилища без root (карточка главного экрана). */
    val storageRunning: Boolean get() = running && cur == STORAGE
    /** Скан общего хранилища идёт или ждёт в очереди — только он важен карточке и экрану attach. */
    val storageActive: Boolean get() = queue.active(STORAGE, if (running) cur else null)
    /** Идёт скан или ждёт пересканирование после удаления. */
    val active: Boolean get() = h != 0L || !queue.isEmpty

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
            pm?.currentThermalStatus ?: 0, storageActive)
    }

    /** onResume главного экрана: запускает скан, если [gate] разрешает. Возвращает решение. */
    fun maybeStart(ctx: Context): Gate {
        val g = gate(ctx)
        if (g == Gate.START && auto) start(ctx)
        return g
    }

    /** Контекст приложения для сканов, которые запускает не экран ([deleteFinished]). Главный поток. */
    fun bind(ctx: Context) { app = ctx.applicationContext }

    /**
     * Ручной или автоматический запуск скана общего хранилища. Идёт скан другого корня — этот
     * встаёт в очередь следом. false — не запущен (удаление, нет доступа, ошибка).
     */
    fun start(ctx: Context): Boolean {
        if (running && cur != STORAGE && !Holder.deleting && Perms.files()) {
            app = ctx.applicationContext
            queue += STORAGE
            changed()
            return true
        }
        return start(ctx, STORAGE)
    }

    private fun start(ctx: Context, t: ScanTarget): Boolean {
        if (running || Holder.deleting || (!t.su && !Perms.files())) return false
        app = ctx.applicationContext
        val err = IntArray(1)
        // su — как root-скан ScanActivity: хелпер под su, запуск с главного потока (не Holder.io).
        val nh = if (t.su) Native.rootStart(Root.helper(ctx), t.root, true, Root.memfdAllowed(ctx), err)
                 else Native.scanStart(t.root, true, 0, err)
        if (nh == 0L) {
            failure = ScanFail(err[0]).also { NativeErr.log("bgscan start", it) }
            changed()
            return false
        }
        h = nh; cur = t; queue.remove(t); dirty = false; failure = null; path = ""; p.fill(0)
        ui.post(poll)
        changed()
        return true
    }

    private val poll = object : Runnable {
        override fun run() {
            val handle = h
            if (handle == 0L) return
            path = Native.str(Native.progress(handle, p))
            when (p[0].toInt()) {
                ST_RUNNING -> {
                    if (p[4] >= INDEX_AFTER_MS && cur == STORAGE && noTree()) startIndex()
                    changed()
                    ui.postDelayed(this, 100)
                }
                ST_DONE, ST_FULL -> done(handle)
                else -> failed(handle)
            }
        }
    }

    private fun log(state: String) =
        Log.i("ancdu", "bgscan state=$state su=${cur.su} files=${p[1]} ms=${p[4]}")

    private fun done(handle: Long) {
        h = 0L
        log("done")
        if (dirty) { Holder.io.execute { Native.free(handle) }; discard(); return }
        val ctx = app ?: run { Holder.io.execute { Native.free(handle) }; return discard() }
        val t = cur
        if (t.su) { Root.rememberMemfd(ctx, p[5] == 1L); Root.granted(ctx) }
        val d = Scans.finish(ctx, handle, t.root, t.su, p)
        publish(handle, t, if (t.su) Kind.ROOT else Kind.SCAN, d.time, d.ms)
        // Ждёт обновление другой цели (браузер) — следом; во время удаления — после него.
        if (!queue.isEmpty && !Holder.deleting) restart()
        changed()
    }

    private fun failed(handle: Long) {
        h = 0L
        log("failed")
        // Скан закончился — владелец читает текст ошибки, затем дескриптор уходит на io.
        failure = ScanFail(0, Native.str(Native.error(handle))).also { NativeErr.log("bgscan", it) }
        Holder.io.execute { Native.free(handle) }
        if (dirty) { discard(); return }
        if (cur == STORAGE && noTree()) startIndex()
        if (!queue.isEmpty && !Holder.deleting) restart()
        changed()
    }

    /**
     * «Грязный» (или бесхозный) итог уже освобождён: скан повторяется, если стоит в очереди
     * ([deleteStarted] ставит его, кроме su без выдачи), после удаления, если оно ещё идёт.
     */
    private fun discard() {
        if (!queue.isEmpty && !Holder.deleting) restart()
        changed()
    }

    /** Следующая цель из очереди; не запустилась — следующая за ней. */
    private fun restart() {
        val ctx = app
        while (true) {
            // root отклонён после постановки — su-цель выбрасывается (Magisk сам не спрашивает).
            val t = queue.next(rootDenied = Root.state == RootState.DENIED) ?: break
            if (ctx != null && start(ctx, t)) return
        }
        changed()
    }

    /**
     * Подставить готовое дерево цели [t]: главный экран (или ждущий скан хранилища ScanActivity)
     * на виду и ни один браузер не держит дескриптор — сразу Holder.set; иначе — Holder.offer (без
     * рывка дерева). Сессию другого корня или другого режима su фоновый скан сам не вытесняет:
     * тоже offer — подставит браузер (тот же корень и режим) или тап по карточке.
     */
    private fun publish(handle: Long, t: ScanTarget, kind: Kind, time: Long, ms: Long) {
        val visible = mainResumed || (attached > 0 && t == STORAGE)
        if (Swap.direct(visible, Holder.browsers, Holder.deleting, ownsHolder(t))) {
            Holder.set(handle, kind, t.root, t.su, time, ms)
            if (Swap.newer(Holder.pending, Holder.pendingRoot, Holder.pendingViaRoot, t.root, t.su)) Holder.dropPending()
        } else {
            Holder.offer(handle, kind, t.root, t.su, time, ms)
        }
    }

    /** В Holder пусто или дерево того же корня в том же режиме su — его можно заменить свежим. */
    private fun ownsHolder(t: ScanTarget): Boolean = Holder.h == 0L || (Holder.root == t.root && Holder.viaRoot == t.su)

    /** В Holder ждёт дерево общего хранилища без root. */
    fun pendingStorage(): Boolean = Swap.newer(Holder.pending, Holder.pendingRoot, Holder.pendingViaRoot, ROOT, false)

    /** Главный экран на виду: подставить ждущее дерево, если его никто не держит. Главный поток. */
    fun promoteOnMain() {
        if (Swap.promoteOnMain(pendingStorage(), mainResumed, Holder.browsers, Holder.deleting, ownsHolder(STORAGE)))
            Holder.promote()
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
                // У индекса нет времени скана: карточка не скажет «только что».
                publish(ih, STORAGE, Kind.INDEX, 0L, -1L)
                changed()
            }
        }, "ancdu-index").apply { isDaemon = true }.start()
    }

    /**
     * Holder.delete, главный поток, до постановки удаления на io. Идущий скан станет «грязным» и
     * повторится; непоказанное дерево уже устарело — выбрасывается, пересканирование после
     * удаления (root — только если root выдан: сам фоновый скан Magisk не спрашивает).
     */
    fun deleteStarted() {
        if (running) {
            dirty = true
            if (Swap.autoRoot(cur.su, false, Root.state)) queue += cur
        }
        if (Holder.pending != 0L) {
            val t = ScanTarget(Holder.pendingRoot, Holder.pendingViaRoot)
            Holder.dropPending()
            if (Swap.autoRoot(t.su, false, Root.state)) queue += t
        }
    }

    /**
     * Holder.delete завершилось с кодом [r] (главный поток, до слушателей удаления): отложенное
     * пересканирование. Удалено не всё ([DeleteProgress.refreshAfter]) — показанное дерево
     * обновляется само в том же режиме su, даже если скана не было. Root — только после
     * root-удаления (выдача только что использована) или если root выдан.
     */
    fun deleteFinished(r: Int) {
        val t = ScanTarget(Holder.root, Holder.viaRoot)
        if (Holder.h != 0L && DeleteProgress.refreshAfter(r, Holder.delRoot, Holder.deleteProgress(), Holder.delDir) &&
            Swap.autoRoot(t.su, Holder.delRoot, Root.state)) queue += t
        if (!queue.isEmpty && !running) restart()
    }

    /**
     * Браузер, по долгому тапу: обновить показанное дерево [root] в режиме [su] перед удалением
     * каталога из устаревшего дерева. su может вызвать запрос Magisk — только как прямой итог
     * действия пользователя. Итог — как у любого фонового скана: Holder.offer и [changed]. Идёт
     * скан той же цели — его итог и есть обновление; другой — этот следом. false — не запущен.
     */
    fun refresh(ctx: Context, root: String, su: Boolean): Boolean {
        val t = ScanTarget(root, su)
        app = ctx.applicationContext
        if (running) {
            if (cur != t) queue += t
            return true
        }
        return start(ctx, t)
    }

    /**
     * Браузер отменил запрос листа (навигация, «назад», экран закрыт): ещё не начатое su-обновление
     * [t] снимается с очереди — запрос Magisk бывает только прямым итогом долгого тапа.
     */
    fun unqueue(t: ScanTarget) {
        if (t.su && t in queue) {
            queue.remove(t)
            changed()
        }
    }
}
