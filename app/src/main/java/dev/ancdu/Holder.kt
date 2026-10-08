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

/**
 * Один объект удаления: узел [node] и путь его удаления — root ([helper]), быстрый путь
 * /data/media ([media]), массовый шаг MediaStore до ядра ([bulk]) и шаг после ядра ([afterIo]).
 * Собирается из дерева на месте (BrowserActivity.deleteItem); лямбды не держат Activity.
 */
class DeleteItem(
    val node: Int,
    val helper: String?,
    val media: Boolean,
    val name: String,
    val dir: Boolean,
    val disk: Long,
    val bulk: ((stopped: () -> Boolean, add: (Long) -> Unit) -> Unit)?,
    val afterIo: ((Int) -> Unit)?,
)

/** Шаг группы, решённый на io к его началу: удалить [Go.item] или пропустить с итогом [Skip.result]. */
sealed class Planned {
    class Go(val item: DeleteItem) : Planned()
    class Skip(val result: ItemResult) : Planned()
}

/**
 * Объект группы: имя, каталог ли и размер на момент подтверждения (для итога, если он не
 * начнётся) и [plan] — на io к его началу.
 */
class GroupJob(val name: String, val dir: Boolean, val disk: Long, val plan: () -> Planned)

/** Текущее дерево процесса: переживает пересоздание Activity. */
object Holder {
    @Volatile var h = 0L; private set
    @Volatile var kind = Kind.SCAN; private set
    @Volatile var root = ""; private set
    @Volatile var viaRoot = false; private set
    /** Время дерева (мс): скана или кэша, из которого оно открыто; 0 — неизвестно. */
    @Volatile var time = 0L; private set
    /** Длительность скана, давшего дерево (мс), для плашки браузера; -1 — нет (идёт, кэш, индекс). */
    @Volatile var ms = -1L; private set
    /** Поколение сессии: растёт при каждой смене дескриптора (адрес может повториться после free). */
    @Volatile var gen = 0L; private set

    /** Единственный поток для блокирующих и мутирующих операций над сессиями:
     *  delete, saveCache, free. FIFO гарантирует, что free не гоняется с ними. */
    val io: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "ancdu-io").apply { isDaemon = true }
    }

    private val main = Handler(Looper.getMainLooper())
    private val deleteListeners = ArrayList<(Int) -> Unit>()
    private val sessionListeners = ArrayList<() -> Unit>()

    private fun checkMain(what: String) =
        check(Looper.myLooper() == Looper.getMainLooper()) { "$what off the main thread" }

    /**
     * Только главный поток. Поля меняются сразу; затем, если дескриптор сменился, слушатели сессии
     * вызываются СИНХРОННО — живые экраны отцепляются от старого дескриптора — и только после этого
     * прежняя сессия освобождается на [io] (free может ждать Magisk).
     */
    fun set(handle: Long, kind: Kind, root: String, viaRoot: Boolean, time: Long = 0L, ms: Long = -1L) {
        checkMain("Holder.set")
        val old = h
        h = handle; this.kind = kind; this.root = root; this.viaRoot = viaRoot; this.time = time; this.ms = ms
        // Δ против точки отсчёта (на io) — раньше слушателей: их чтения на io встают после расчёта.
        if (old == handle) { Growth.onTree(handle, gen, kind, root, viaRoot); return }
        gen++
        Growth.onTree(handle, gen, kind, root, viaRoot)
        for (l in sessionListeners.toList()) l()
        if (old != 0L) io.execute { Native.free(old) }
    }

    /** Только главный поток. Сессии больше нет; прежняя освобождается на [io]. */
    fun clear() = set(0L, Kind.SCAN, "", false)

    /*
     * Более новое дерево, ещё не показанное (фоновый скан закончился, пока его нельзя было
     * подставить: открыт браузер). Поля пишет только главный поток. Native на [pending] до
     * [promote] никто не вызывает, кроме saveCache, поставленного на io раньше [offer].
     */
    @Volatile var pending = 0L; private set
    var pendingKind = Kind.SCAN; private set
    var pendingRoot = ""; private set
    var pendingViaRoot = false; private set
    var pendingTime = 0L; private set
    var pendingMs = -1L; private set

    /**
     * Только главный поток. Кладёт [handle] в слот ожидания. Слушателей НЕ вызывает: живые экраны
     * продолжают показывать своё дерево. Прежний непоказанный pending освобождается на [io].
     */
    fun offer(handle: Long, kind: Kind, root: String, viaRoot: Boolean, time: Long = 0L, ms: Long = -1L) {
        checkMain("Holder.offer")
        val old = pending
        pending = handle; pendingKind = kind; pendingRoot = root; pendingViaRoot = viaRoot
        pendingTime = time; pendingMs = ms
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
        set(p, pendingKind, pendingRoot, pendingViaRoot, pendingTime, pendingMs)
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

    /** Сколько удалений (одиночных и групп) закончилось в процессе: дерево могло измениться. Только главный поток. */
    var deletes = 0L; private set

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
    /** Путь узла (байты имён от корня) — найти его в обновлённом дереве. Только главный поток. */
    var delNames: List<ByteArray> = emptyList(); private set
    /**
     * Удаляется каталог (не файл). У группы — к концу: есть каталог, удалённый частично
     * ([GroupResult.needsRefresh]), — дерево обновится. Только главный поток.
     */
    var delDir = true; private set
    /** Объектов в удалении: 1 — одиночное, больше — группа (диалог «Удаление 3 объектов»). Только главный поток. */
    var delCount = 1; private set
    /** Итог каждого объекта последнего удаления (у одиночного — один). Только главный поток. */
    var delResults: List<ItemResult> = emptyList(); private set
    /** Для тестов: вызывается на io перед k-м объектом группы (k от 0). */
    @Volatile var beforeItem: ((Int) -> Unit)? = null

    /*
     * Удаление в полёте. Под [delLock]: [delHandle] != 0 только пока на io идёт шаг ядра —
     * с момента перед Native.delete (после массового шага MediaStore, если он есть) до его
     * возврата; сбрасывается до того, как io перейдёт к следующей задаче, в том числе к free
     * этого дескриптора, — поэтому Native.deleteProgress/deleteStop под замком никогда не видят
     * освобождённую сессию и не видят счётчик прошлого удаления. [delStopAsked] — «Стоп» нажат;
     * [delRows] — строк удалено массовым шагом; [delNative] — последний счётчик ядра;
     * [delDone] — их сумма, последний прогресс. У группы [delRows]/[delNative] — текущего объекта,
     * [delBase] — сумма уже законченных (у одиночного всегда 0).
     */
    private val delLock = Any()
    private var delHandle = 0L
    private var delStopAsked = false
    private var delRows = 0L
    private var delNative = 0L
    private var delDone = 0L
    private var delBase = 0L

    /** Для тестов: строк MediaStore удалено массовым шагом последнего удаления (пишет io). */
    @Volatile var lastBulkRows = 0L; private set

    /**
     * Любой поток (всё под замком; экран зовёт с главного). Сколько записей удалено идущим
     * (или последним) удалением: строки массового шага плюс счётчик ядра.
     * Единственный вызов Native на дескрипторе во время delete наряду с [deleteStop].
     */
    fun deleteProgress(): Long = synchronized(delLock) {
        if (delHandle != 0L) delNative = Native.deleteProgress(delHandle)
        delDone = DeleteSteps.done(delBase, DeleteSteps.done(delRows, delNative))
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
     * Только главный поток. Удаляет узел [DeleteItem.node] сессии [handle] на [io]. По завершении на
     * главном потоке снимает [deleting], уведомляет слушателей, затем вызывает [done].
     * [total] — items узла для диалога прогресса; [names] — путь узла (байты имён от корня).
     * [DeleteItem.bulk] — необязательный массовый шаг MediaStore на io ДО ядра (дескриптор он не
     * трогает): получает «нажат ли Стоп» и счётчик удалённых строк. Затем ВСЕГДА
     * Native.delete/deleteMedia на том же узле — кроме «Стопа» до этого момента (в том числе пока
     * удаление ждало в очереди io): тогда ядро не вызывается, итог -EINTR (DeleteSteps); если
     * массовый шаг уже что-то удалил — узел помечается F_ERR (Native.markErr), дерево не выдаёт его
     * за целый. [DeleteItem.media] — Native.deleteMedia (нужен helper): узел удаляется через
     * /data/media в обход FUSE. [DeleteItem.afterIo] — на io сразу после шага ядра с его кодом,
     * если удаление не отменено (например, очистка строк MediaStore).
     */
    fun delete(handle: Long, item: DeleteItem, done: (Int) -> Unit = {}, total: Long = 1L,
               names: List<ByteArray> = emptyList()) {
        checkMain("Holder.delete")
        check(!deleting) { "a delete is already running" }
        begin(item.name, total, item.disk, names, item.dir, root = item.helper != null, count = 1)
        run(handle, listOf(GroupJob(item.name, item.dir, item.disk) { Planned.Go(item) }), group = false,
            root = item.helper != null, done = done)
    }

    /**
     * Только главный поток. Удаление группы: ТОТ ЖЕ путь, что [delete], — объекты по очереди на
     * [io], общий [deleting], диалог и «Стоп». Каждый шаг [jobs] решается на io к своему началу
     * (по имени в живом дереве, с повторной проверкой запрета) и пропускается, если нельзя.
     * «Стоп» прерывает текущий объект, остальные не начинаются; su отказал ([root]) — остальные тоже.
     * [name] — имя папки, [names] — её путь, [total] — сумма items, [disk] — сумма размеров.
     */
    fun deleteGroup(handle: Long, jobs: List<GroupJob>, root: Boolean, done: (Int) -> Unit = {},
                    name: String = "", total: Long = 1L, disk: Long = 0L, names: List<ByteArray> = emptyList()) {
        checkMain("Holder.deleteGroup")
        check(!deleting) { "a delete is already running" }
        begin(name, total, disk, names, dir = true, root = root, count = jobs.size)
        run(handle, jobs, group = true, root = root, done = done)
    }

    private fun begin(name: String, total: Long, disk: Long, names: List<ByteArray>, dir: Boolean, root: Boolean, count: Int) {
        deleting = true
        delName = name; delTotal = DeleteProgress.total(total)
        delStartMs = SystemClock.elapsedRealtime(); delStopping = false; delRoot = root
        delDisk = disk; delNames = names; delDir = dir; delCount = count; delResults = emptyList()
        synchronized(delLock) { delHandle = 0L; delStopAsked = false; delRows = 0L; delNative = 0L; delDone = 0L; delBase = 0L }
        lastBulkRows = 0L
        // Идущий фоновый скан и непоказанное дерево могли увидеть удаляемое — пересканировать.
        BgScan.deleteStarted()
    }

    /** На io: объекты по очереди; итог — на главный поток (и при исключении: deleting не останется true). */
    private fun run(handle: Long, jobs: List<GroupJob>, group: Boolean, root: Boolean, done: (Int) -> Unit) {
        io.execute {
            var r = -1
            val results = ArrayList<ItemResult>()
            try {
                for ((k, job) in jobs.withIndex()) {
                    if (group) {
                        runCatching { beforeItem?.invoke(k) }
                        // «Стоп» между объектами: остальные не начинаются (и не ищутся в дереве).
                        if (synchronized(delLock) { delStopAsked }) {
                            for (rest in jobs.subList(k, jobs.size)) results += skipped(rest, -DeleteProgress.EINTR)
                            break
                        }
                    }
                    when (val plan = job.plan()) {
                        is Planned.Skip -> results += plan.result
                        is Planned.Go -> {
                            val it = plan.item
                            val (code, n) = runItem(handle, it)
                            results += ItemResult(it.name, it.dir, it.disk, code, n)
                            // su отказал: следующие тоже спросили бы su — не начинаются («не начато»).
                            if (group && it.helper != null && DeletePolicy.nothingDeleted(code, true) && k + 1 < jobs.size) {
                                for (rest in jobs.subList(k + 1, jobs.size)) results += skipped(rest, code)
                                break
                            }
                        }
                    }
                }
                r = if (group) GroupResult.code(results, root) else results.single().r
            } finally {
                main.post {
                    deleting = false
                    deletes++
                    // Размеры предков удалённого изменились: Δ — заново (до того — короткое окно устаревших Δ папок).
                    Growth.recompute()
                    delResults = results
                    if (group) delDir = GroupResult.needsRefresh(results, root)
                    // Сначала обновление дерева (r ≠ 0): экраны в слушателях уже видят BgScan.active.
                    BgScan.deleteFinished(r)
                    for (l in deleteListeners.toList()) l(r)
                    done(r)
                }
            }
        }
    }

    /** Не начатый объект группы: attempted = false, без запрета — причина «не начато» (GroupResult.fail). */
    private fun skipped(j: GroupJob, r: Int) = ItemResult(j.name, j.dir, j.disk, r, 0L, attempted = false)

    /** На io: один объект — массовый шаг, затем ядро (DeleteSteps). Код и сколько записей удалено им. */
    private fun runItem(handle: Long, item: DeleteItem): Pair<Int, Long> {
        var r = -1
        var nativeRan = false
        var itemDone = 0L
        synchronized(delLock) { delHandle = 0L; delRows = 0L; delNative = 0L }
        try {
            r = DeleteSteps.run(
                bulk = item.bulk?.let { b -> {
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
                    if (item.media && item.helper != null) Native.deleteMedia(handle, item.node, item.helper)
                    else Native.delete(handle, item.node, item.helper)
                },
                onBulkError = { Log.w("ancdu", "bulk delete failed, rm_tree continues", it) },
                bulkRows = { synchronized(delLock) { delRows } },
                // На io, ядро не вызывалось и deleteProgress/deleteStop ядро не трогают (delHandle 0).
                markPartial = { runCatching { Native.markErr(handle, item.node) } })
        } finally {
            synchronized(delLock) {
                // Итог берётся здесь, на io, пока free этого дескриптора не мог начаться.
                // Ядро не вызывалось — его счётчик относится к прошлому удалению, не берём.
                if (nativeRan) delNative = runCatching { Native.deleteProgress(handle) }.getOrDefault(delNative)
                delHandle = 0L
                itemDone = DeleteSteps.done(delRows, delNative)
                lastBulkRows = DeleteSteps.done(lastBulkRows, delRows)
                delBase = DeleteSteps.done(delBase, itemDone)
                delRows = 0L; delNative = 0L
                delDone = delBase
            }
            if (item.afterIo != null && !DeleteProgress.isCancelled(r, itemDone)) runCatching { item.afterIo.invoke(r) }
        }
        return r to itemDone
    }

    fun progress(): LongArray = LongArray(6).also { if (h != 0L) Native.progress(h, it) }

    fun cacheFile(ctx: Context, root: String, viaRoot: Boolean): File =
        File(ctx.filesDir, "last-" + (if (viaRoot) "su" else "app") + root.replace('/', '_') + ".ancdu")
}
