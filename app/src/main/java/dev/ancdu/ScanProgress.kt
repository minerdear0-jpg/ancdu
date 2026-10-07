package dev.ancdu

/**
 * Чистый Kotlin: полоса и плашка фонового скана — шапка браузера (обновление показанного дерева)
 * и карточка главного экрана.
 */
object ScanProgress {
    /** До конца скана полоса не доходит до края. */
    const val CAP = 0.97f
    /** Счёт на плашке меняется не чаще раза в [BADGE_MS]. */
    const val BADGE_MS = 1000L

    /** Доля [files] от оценки [estimate], не больше [CAP]; null — оценки нет (неопределённая полоса). */
    fun fraction(files: Long, estimate: Long?): Float? {
        if (estimate == null || estimate <= 0) return null
        return (maxOf(files, 0L).toFloat() / estimate).coerceAtMost(CAP)
    }

    /** «обновление · 12 400» / «обновление · ждёт»; NONE — пусто (плашка показывает вид дерева). */
    fun badge(t: Txt, state: ScanState, files: Long): String = when (state) {
        ScanState.RUNNING -> t.s(R.string.refresh_count, Fmt.count(files, t.locale))
        ScanState.QUEUED -> t.s(R.string.refresh_waiting)
        ScanState.NONE -> ""
    }

    /** Пора ли обновить плашку: состояние сменилось — сразу, иначе раз в [BADGE_MS]. */
    fun badgeDue(changed: Boolean, now: Long, last: Long): Boolean = changed || now - last >= BADGE_MS
}
