package dev.ancdu

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Решение об автоскане общего хранилища при запуске/возврате на главный экран. */
enum class Gate { START, NO_PERM, RUNNING, FRESH, POWER }

/** Чистый Kotlin: когда фоновый скан без root запускается сам. */
object ScanGate {
    /** Кэш моложе этого — не обновляем. */
    const val FRESH_MS = 60_000L
    /** PowerManager.THERMAL_STATUS_MODERATE. */
    const val THERMAL_MODERATE = 2

    /**
     * [perm] — доступ ко всем файлам; [cacheAgeMs] — возраст кэша общего хранилища (null — нет);
     * [powerSave] — PowerManager.isPowerSaveMode; [thermal] — getCurrentThermalStatus();
     * [running] — фоновый скан уже идёт. [Gate.POWER] — карточка предлагает «обновить ›» вручную.
     */
    fun decide(perm: Boolean, cacheAgeMs: Long?, powerSave: Boolean, thermal: Int, running: Boolean): Gate = when {
        !perm -> Gate.NO_PERM
        running -> Gate.RUNNING
        cacheAgeMs != null && cacheAgeMs in 0..FRESH_MS -> Gate.FRESH
        powerSave || thermal >= THERMAL_MODERATE -> Gate.POWER
        else -> Gate.START
    }
}

/** Чистый Kotlin: строки свежести на карточке общего хранилища. */
object Freshness {
    private val RU = Locale.forLanguageTag("ru")

    private fun fmt(pattern: String, ms: Long, tz: TimeZone): String =
        SimpleDateFormat(pattern, RU).apply { timeZone = tz }.format(Date(ms))

    /**
     * Приглушённая строка под «Общее хранилище». [running] — идёт фоновый скан, [live] — сколько
     * файлов он уже насчитал; [cacheMs] — время показанного дерева/кэша (null — нет ни того, ни
     * другого); [scanned] — дерево получено сканом в этом процессе (иначе — из кэша);
     * [blocked] — автоскан не запущен из-за энергосбережения/нагрева; [approx] — показан индекс.
     */
    fun line(running: Boolean, live: Long, cacheMs: Long?, scanned: Boolean, blocked: Boolean,
             approx: Boolean, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        val body = when {
            running && cacheMs != null -> "кэш ${fmt("HH:mm", cacheMs, tz)} · обновляю… ${Fmt.count(live)} эл."
            running -> "${Fmt.count(live)} эл. · первый скан"
            cacheMs == null -> REFRESH
            blocked -> "кэш ${fmt("HH:mm", cacheMs, tz)} · $REFRESH"
            else -> (if (scanned) "скан " else "кэш ") + fmt("HH:mm", cacheMs, tz) + " · " + ago(now - cacheMs, cacheMs, tz)
        }
        return if (approx) "приблизительно · $body" else body
    }

    const val REFRESH = "обновить ›"

    /**
     * Время показанного дерева для строки свежести: у индекса (Kind.INDEX) времени скана нет —
     * null, чтобы строка оставалась «приблизительно · обновить ›» (ручной повтор) или
     * «приблизительно · N эл. · первый скан». 0 — неизвестно.
     */
    fun treeTime(kind: Kind, time: Long): Long? = if (kind == Kind.INDEX || time <= 0) null else time

    private fun ago(d: Long, at: Long, tz: TimeZone): String = when {
        d < 60_000 -> "только что"
        d < 3_600_000 -> "${d / 60_000} мин назад"
        d < 86_400_000 -> "${d / 3_600_000} ч назад"
        else -> fmt("dd.MM", at, tz)
    }

    /** «±X с прошлого скана»: изменение объёма дерева после замены кэша свежим сканом. */
    fun delta(bytes: Long): String {
        val sign = when { bytes > 0 -> "+"; bytes < 0 -> "−"; else -> "±" }
        return sign + Fmt.size(if (bytes < 0) -bytes else bytes) + " с прошлого скана"
    }
}

/** Чистый Kotlin: когда готовое дерево общего хранилища подставляется, а когда ждёт (offer). */
object Swap {
    /**
     * Подставить сразу (Holder.set): экран, который его покажет, на виду ([visible]: главный или
     * ждущий ScanActivity), ни один браузер не держит дескриптор, удаления нет, а в Holder пусто
     * или дерево общего хранилища ([owns]) — чужую root-сессию фоновый скан не вытесняет.
     */
    fun direct(visible: Boolean, browsers: Int, deleting: Boolean, owns: Boolean): Boolean =
        visible && browsers == 0 && !deleting && owns

    /** Ждущее дерево общего хранилища подставляется, как только главный экран снова свободен. */
    fun promoteOnMain(pendingStorage: Boolean, mainResumed: Boolean, browsers: Int, deleting: Boolean,
                      owns: Boolean): Boolean =
        pendingStorage && direct(mainResumed, browsers, deleting, owns)

    /**
     * Ждущее дерево ([pending] != 0) — более новое для показанного: тот же корень И тот же режим
     * su. Скан без root не предлагается поверх root-дерева того же пути — он отнял бы доступ.
     */
    fun newer(pending: Long, pendingRoot: String, pendingViaRoot: Boolean, root: String, viaRoot: Boolean): Boolean =
        pending != 0L && pendingRoot == root && pendingViaRoot == viaRoot

    /** Счётчик закреплений браузерами; [onUnpin] — после каждого реального снятия. */
    class Pins(private val onUnpin: () -> Unit = {}) {
        var count = 0; private set
        fun pin() { count++ }
        fun unpin() {
            if (count == 0) return
            count--
            onUnpin()
        }
    }
}
