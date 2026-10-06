package dev.ancdu

import java.text.SimpleDateFormat
import java.util.Date
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
    /** Дата/время по шаблону-ресурсу [pattern] в языке [t]. */
    fun date(t: Txt, pattern: Int, ms: Long, tz: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat(t.s(pattern), t.locale).apply { timeZone = tz }.format(Date(ms))

    /**
     * Приглушённая строка под «Общее хранилище». [running] — идёт фоновый скан, [live] — сколько
     * файлов он уже насчитал; [cacheMs] — время показанного дерева/кэша (null — нет ни того, ни
     * другого); [scanned] — дерево получено сканом в этом процессе (иначе — из кэша);
     * [blocked] — автоскан не запущен из-за энергосбережения/нагрева; [approx] — показан индекс.
     */
    fun line(t: Txt, running: Boolean, live: Long, cacheMs: Long?, scanned: Boolean, blocked: Boolean,
             approx: Boolean, now: Long, tz: TimeZone = TimeZone.getDefault()): String {
        fun cache(ms: Long) = t.s(R.string.fresh_cache, date(t, R.string.fmt_time, ms, tz))
        val body = when {
            running && cacheMs != null -> cache(cacheMs) + " · " + t.s(R.string.fresh_updating, t.items(live))
            running -> t.s(R.string.fresh_first, t.items(live))
            cacheMs == null -> refresh(t)
            blocked -> cache(cacheMs) + " · " + refresh(t)
            else -> (if (scanned) t.s(R.string.fresh_scan, date(t, R.string.fmt_time, cacheMs, tz)) else cache(cacheMs)) +
                " · " + ago(t, now - cacheMs, cacheMs, tz)
        }
        return if (approx) t.s(R.string.fresh_approx, body) else body
    }

    /** «обновить ›» — строка с ним в конце касается как «скан вручную». */
    fun refresh(t: Txt): String = t.s(R.string.fresh_refresh)

    /**
     * Время показанного дерева для строки свежести: у индекса (Kind.INDEX) времени скана нет —
     * null, чтобы строка оставалась «приблизительно · обновить ›» (ручной повтор) или
     * «приблизительно · N эл. · первый скан». 0 — неизвестно.
     */
    fun treeTime(kind: Kind, time: Long): Long? = if (kind == Kind.INDEX || time <= 0) null else time

    private fun ago(t: Txt, d: Long, at: Long, tz: TimeZone): String = when {
        d < 60_000 -> t.s(R.string.just_now)
        d < 3_600_000 -> t.s(R.string.min_ago, Fmt.count(d / 60_000, t.locale))
        d < 86_400_000 -> t.s(R.string.h_ago, Fmt.count(d / 3_600_000, t.locale))
        else -> date(t, R.string.fmt_day, at, tz)
    }

    /** «±X с прошлого скана»: изменение объёма дерева после замены кэша свежим сканом. */
    fun delta(t: Txt, bytes: Long): String {
        val sign = when { bytes > 0 -> "+"; bytes < 0 -> "−"; else -> "±" }
        return t.s(R.string.delta, sign + Fmt.size(if (bytes < 0) -bytes else bytes, t))
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

    /**
     * Root-скан без действия пользователя (Magisk не должен спрашивать сам): только сразу после
     * root-удаления ([delRoot] — выдача только что использована) или если root выдан.
     */
    fun autoRoot(su: Boolean, delRoot: Boolean, state: RootState): Boolean =
        !su || delRoot || state == RootState.GRANTED

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
