package dev.ancdu

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

enum class Kind { SCAN, ROOT, INDEX, CACHE }

/** Текущее дерево процесса: переживает пересоздание Activity. */
object Holder {
    @Volatile var h = 0L; private set
    @Volatile var kind = Kind.SCAN; private set
    @Volatile var root = ""; private set
    @Volatile var label = ""; private set
    @Volatile var viaRoot = false; private set
    /** Время дерева (мс): скана или кэша, из которого оно открыто; 0 — неизвестно. */
    @Volatile var time = 0L; private set

    /** Единственный поток для блокирующих и мутирующих операций над сессиями:
     *  delete, saveCache, free. FIFO гарантирует, что free не гоняется с ними. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ancdu-io").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())
    private val deleteListeners = ArrayList<(Int) -> Unit>()
    private val sessionListeners = ArrayList<() -> Unit>()

    private fun checkMain(what: String) =
        check(Looper.myLooper() == Looper.getMainLooper()) { "$what не с главного потока" }

    /**
     * Только главный поток. Поля меняются сразу; затем, если дескриптор сменился, слушатели сессии
     * вызываются СИНХРОННО — живые экраны отцепляются от старого дескриптора — и только после этого
     * прежняя сессия освобождается на [io] (free может ждать Magisk).
     */
    fun set(handle: Long, kind: Kind, root: String, label: String, viaRoot: Boolean, time: Long = 0L) {
        checkMain("Holder.set")
        val old = h
        h = handle; this.kind = kind; this.root = root; this.label = label; this.viaRoot = viaRoot; this.time = time
        if (old == handle) return
        for (l in sessionListeners.toList()) l()
        if (old != 0L) io.execute { Native.free(old) }
    }

    /** Только главный поток. Сессии больше нет; прежняя освобождается на [io]. */
    fun clear() = set(0L, Kind.SCAN, "", "", false)

    /*
     * Более новое дерево, ещё не показанное (фоновый скан закончился, пока его нельзя было
     * подставить: открыт браузер). Поля пишет только главный поток. Native на [pending] до
     * [promote] никто не вызывает, кроме saveCache, поставленного на io раньше [offer].
     */
    @Volatile var pending = 0L; private set
    var pendingKind = Kind.SCAN; private set
    var pendingRoot = ""; private set
    var pendingLabel = ""; private set
    var pendingViaRoot = false; private set
    var pendingTime = 0L; private set

    /**
     * Только главный поток. Кладёт [handle] в слот ожидания. Слушателей НЕ вызывает: живые экраны
     * продолжают показывать своё дерево. Прежний непоказанный pending освобождается на [io].
     */
    fun offer(handle: Long, kind: Kind, root: String, label: String, viaRoot: Boolean, time: Long = 0L) {
        checkMain("Holder.offer")
        val old = pending
        pending = handle; pendingKind = kind; pendingRoot = root; pendingLabel = label; pendingViaRoot = viaRoot
        pendingTime = time
        if (old != 0L && old != handle) io.execute { Native.free(old) }
    }

    /**
     * Только главный поток. [set] из слота ожидания. Слот очищается ДО set: слушатель внутри set
     * (offer, dropPending) уже не увидит этот дескриптор и не освободит его второй раз.
     * false — слот пуст.
     */
    fun promote(): Boolean {
        checkMain("Holder.promote")
        val p = pending
        if (p == 0L) return false
        pending = 0L
        set(p, pendingKind, pendingRoot, pendingLabel, pendingViaRoot, pendingTime)
        return true
    }

    /** Только главный поток. Непоказанное дерево устарело («грязное»): освобождается на [io]. */
    fun dropPending() {
        checkMain("Holder.dropPending")
        val old = pending
        pending = 0L
        if (old != 0L) io.execute { Native.free(old) }
    }

    /**
     * Сколько BrowserActivity закрепили дескриптор (onCreate с h != 0 … onPause при
     * isFinishing или onDestroy). Только главный поток. Пока > 0, фоновый скан не подставляется
     * через [set], а [offer]-ится. Снятие закрепления уведомляет экраны (BgScan.changed): уже
     * видимый главный экран снова проверяет подстановку ждущего дерева.
     */
    private val pins = Swap.Pins { BgScan.changed() }
    val browsers: Int get() = pins.count
    fun pinBrowser() { checkMain("Holder.pinBrowser"); pins.pin() }
    fun unpinBrowser() { checkMain("Holder.unpinBrowser"); pins.unpin() }

    /** Только главный поток. Слушатель вызывается внутри [set] при смене дескриптора, до free(old). */
    fun addSessionListener(l: () -> Unit) { sessionListeners += l }
    fun removeSessionListener(l: () -> Unit) { sessionListeners -= l }

    /** Идёт удаление (на любом дескрипторе). Только главный поток. Пока true — никаких чтений дерева. */
    var deleting = false; private set

    /**
     * Что показывает диалог удаления; переживает пересоздание экрана. Только главный поток.
     * [delName] — имя узла, [delTotal] — его items на момент подтверждения, [delStartMs] —
     * SystemClock.elapsedRealtime() старта, [delStopping] — «Стоп» уже нажат.
     */
    var delName = ""; private set
    var delTotal = 1L; private set
    var delStartMs = 0L; private set
    var delStopping = false; private set
    /** Удаление идёт через su (root-сессия или быстрый путь /data/media). Только главный поток. */
    var delRoot = false; private set
    /** Размер узла на диске на момент подтверждения — для «освобождено …». Только главный поток. */
    var delDisk = 0L; private set

    /*
     * Удаление в полёте. Под [delLock]: [delHandle] != 0 только пока на io идёт шаг ядра —
     * с момента перед Native.delete (после массового шага MediaStore, если он есть) до его
     * возврата; сбрасывается до того, как io перейдёт к следующей задаче, в том числе к free
     * этого дескриптора, — поэтому Native.deleteProgress/deleteStop под замком никогда не видят
     * освобождённую сессию и не видят счётчик прошлого удаления. [delStopAsked] — «Стоп» нажат;
     * [delRows] — строк удалено массовым шагом; [delNative] — последний счётчик ядра;
     * [delDone] — их сумма, последний прогресс.
     */
    private val delLock = Any()
    private var delHandle = 0L
    private var delStopAsked = false
    private var delRows = 0L
    private var delNative = 0L
    private var delDone = 0L

    /** Для тестов: строк MediaStore удалено массовым шагом последнего удаления (пишет io). */
    @Volatile var lastBulkRows = 0L; private set

    /**
     * Любой поток (всё под замком; экран зовёт с главного). Сколько записей удалено идущим
     * (или последним) удалением: строки массового шага плюс счётчик ядра.
     * Единственный вызов Native на дескрипторе во время delete наряду с [deleteStop].
     */
    fun deleteProgress(): Long = synchronized(delLock) {
        if (delHandle != 0L) delNative = Native.deleteProgress(delHandle)
        delDone = DeleteSteps.done(delRows, delNative)
        delDone
    }

    /**
     * Только главный поток. Просит остановить идущее удаление; оно вернёт -EINTR. Во время
     * массового шага (и пока удаление ждёт в очереди io) «Стоп» — только флаг: шаг
     * останавливается между пачками, а ядро тогда не вызывается вовсе (флаг ядра не
     * трогается). Во время шага ядра флаг передаётся ему ([delHandle] != 0). После возврата
     * Native.delete флаг ядра уже не трогается (остаётся окно в микросекунды между сбросом в
     * ядре и снятием [delHandle]; худший исход — следующее удаление этой сессии сразу
     * остановится, ничего не удалив).
     */
    fun deleteStop() {
        checkMain("Holder.deleteStop")
        if (!deleting) return
        delStopping = true
        synchronized(delLock) {
            delStopAsked = true
            if (delHandle != 0L) Native.deleteStop(delHandle)
        }
    }

    /** Только главный поток. Слушатели — живые экраны; получают код завершения удаления. */
    fun addDeleteListener(l: (Int) -> Unit) { deleteListeners += l }
    fun removeDeleteListener(l: (Int) -> Unit) { deleteListeners -= l }

    /**
     * Только главный поток. Удаляет узел [node] сессии [handle] на [io]. По завершении на главном
     * потоке снимает [deleting], уведомляет слушателей, затем вызывает [done].
     * [name] и [total] — для диалога прогресса.
     * [bulk] — необязательный массовый шаг MediaStore на io ДО ядра (дескриптор он не трогает):
     * получает «нажат ли Стоп» и счётчик удалённых строк. Затем ВСЕГДА Native.delete/deleteMedia
     * на том же узле — кроме «Стопа» до этого момента (в том числе пока удаление ждало в
     * очереди io): тогда ядро не вызывается, итог -EINTR (DeleteSteps); если массовый шаг уже
     * что-то удалил — узел помечается F_ERR (Native.markErr), дерево не выдаёт его за целый.
     * [media] — Native.deleteMedia (нужен [helper]): узел удаляется через /data/media в обход FUSE.
     * [afterIo] — на io сразу после шага ядра с его кодом, если удаление не отменено
     * (например, очистка строк MediaStore).
     */
    fun delete(handle: Long, node: Int, helper: String?, done: (Int) -> Unit = {},
               name: String = "", total: Long = 1L, disk: Long = 0L, media: Boolean = false,
               bulk: ((stopped: () -> Boolean, add: (Long) -> Unit) -> Unit)? = null,
               afterIo: ((Int) -> Unit)? = null) {
        checkMain("Holder.delete")
        check(!deleting) { "удаление уже идёт" }
        deleting = true
        delName = name; delTotal = DeleteProgress.total(total)
        delStartMs = SystemClock.elapsedRealtime(); delStopping = false; delRoot = helper != null
        delDisk = disk
        synchronized(delLock) { delHandle = 0L; delStopAsked = false; delRows = 0L; delNative = 0L; delDone = 0L }
        lastBulkRows = 0L
        // Идущий фоновый скан и непоказанное дерево могли увидеть удаляемое — пересканировать.
        BgScan.deleteStarted()
        io.execute {
            var r = -1
            var nativeRan = false
            try {
                r = DeleteSteps.run(
                    bulk = bulk?.let { b -> {
                        b({ synchronized(delLock) { delStopAsked } }) { n ->
                            synchronized(delLock) { delRows = DeleteSteps.done(delRows, n) }
                        }
                    } },
                    arm = {
                        synchronized(delLock) {
                            if (delStopAsked) false else { delHandle = handle; nativeRan = true; true }
                        }
                    },
                    native = {
                        if (media && helper != null) Native.deleteMedia(handle, node, helper)
                        else Native.delete(handle, node, helper)
                    },
                    onBulkError = { Log.w("ancdu", "bulk delete failed, rm_tree continues", it) },
                    bulkRows = { synchronized(delLock) { delRows } },
                    // На io, ядро не вызывалось и deleteProgress/deleteStop ядро не трогают (delHandle 0).
                    markPartial = { runCatching { Native.markErr(handle, node) } })
            } finally {
                synchronized(delLock) {
                    // Итог берётся здесь, на io, пока free этого дескриптора не мог начаться.
                    // Ядро не вызывалось — его счётчик относится к прошлому удалению, не берём.
                    if (nativeRan) delNative = runCatching { Native.deleteProgress(handle) }.getOrDefault(delNative)
                    delHandle = 0L
                    delDone = DeleteSteps.done(delRows, delNative)
                    lastBulkRows = delRows
                }
                if (afterIo != null && !DeleteProgress.isCancelled(r, delDone)) runCatching { afterIo(r) }
                // И при исключении: deleting не должен остаться true навсегда.
                main.post {
                    deleting = false
                    for (l in deleteListeners.toList()) l(r)
                    done(r)
                    BgScan.deleteFinished()
                }
            }
        }
    }

    fun progress(): LongArray = LongArray(6).also { if (h != 0L) Native.progress(h, it) }

    fun cacheFile(ctx: Context, root: String, viaRoot: Boolean): File =
        File(ctx.filesDir, "last-" + (if (viaRoot) "su" else "app") + root.replace('/', '_') + ".ancdu")
}
