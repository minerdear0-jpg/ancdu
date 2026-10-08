package dev.ancdu

/**
 * Δ уровня браузера (сортировка Δ): Δ показанного дерева на момент load, порядок детей по Δ,
 * строки со знаковым размером, «ушло», сводка и плашка «Δ с …», лист точки отсчёта.
 * Главный поток; живёт столько же, сколько экран [a].
 */
class DeltaLevel(private val a: BrowserActivity) {
    /** Δ показанного дерева (Growth.forTree) на момент load; null — точки отсчёта нет или Δ считается. */
    var delta: Delta? = null
        private set
    /** На этом уровне показана сортировка Δ (выбрана и Δ есть). */
    var shown = false
        private set
    /** Δ строк уровня (в режиме размера: диск или видимый). */
    private var dvals = LongArray(0)
    /** Строка «ушло: …» внизу папки в сортировке Δ; null — её нет. */
    var goneText: String? = null
        private set
    /** Открытый лист точки отсчёта. */
    var sheet: BaselineSheet? = null
        private set

    /**
     * Начало load: Δ дерева и показ Δ. Δ посчитана заранее на io (Growth): здесь только чтения
     * массивов, без работы с базой. Возвращает сортировку: Δ без точки отсчёта (и не считается) — по размеру.
     */
    fun begin(sort: Int): Int {
        delta = Growth.forTree(a.h, a.gen)
        var s = sort
        if (s == SORT_DELTA && delta == null && !Growth.pending(a.h, a.gen)) s = SORT_SIZE
        val d = delta
        shown = s == SORT_DELTA && d != null
        return s
    }

    /** После nodeInfo уровня: порядок по Δ, «ушло» и образец колонки текущего размера. */
    fun order() {
        val d = delta
        if (shown && d != null) orderByDelta(d) else dvals = LongArray(0)
        // «Гиганты» — не папка: строки «ушло» нет.
        goneText = if (shown && d != null && !a.giants) d.gone[a.node]?.let { GrowthText.goneOrNull(a.txt, it.count, it.bytes(a.apparent)) } else null
        // Δ: колонка текущего размера — по самому длинному тексту уровня (строки и кэшируются здесь).
        a.list.rightSample = if (!shown) null else {
            var longest = ""
            for (i in 0 until a.n) {
                val s = Fmt.size(a.value(i), a.txt).also { a.pcts[i] = it }
                if (s.length > longest.length) longest = s
            }
            longest
        }
    }

    /** Дети уровня (и их nodeInfo) — по Δ убыв., при равной — по размеру ([GrowthSort]); Δ строк. */
    private fun orderByDelta(d: Delta) {
        val pos = IntArray(a.n) { it }
        GrowthSort.sort(pos, a.n, { d.of(a.kids[it], a.apparent) }, { a.value(it) })
        val k2 = IntArray(a.kids.size)
        val i2 = LongArray(a.info.size)
        for (j in 0 until a.n) { k2[j] = a.kids[pos[j]]; System.arraycopy(a.info, 4 * pos[j], i2, 4 * j, 4) }
        a.kids = k2; a.info = i2
        dvals = LongArray(a.n) { d.of(a.kids[it], a.apparent) }
    }

    /** Δ-часть строки [index] (Δ показана): знаковый размер (рост — AMBER_TEXT, сжатие и ±0 — MUTED), без полосы, справа — текущий размер. */
    fun bind(index: Int, row: Row, v: Long) {
        val d = delta
        val dv = dvals[index]
        val isNew = d != null && d.isNew(a.kids[index])
        row.size = a.sizes[index] ?: GrowthText.signed(dv, a.txt).also { a.sizes[index] = it }
        row.sizeColor = GrowthText.role(dv).color()
        row.pct = a.pcts[index] ?: Fmt.size(v, a.txt).also { a.pcts[index] = it }
        if (isNew) row.badge = a.txt.s(R.string.new_badge)
    }

    /** Описание строки [index] в сортировке Δ; null — Δ не показана. */
    fun rowDesc(index: Int, nm: String, v: Long, dir: Boolean): String? {
        val d = delta
        if (!shown || d == null) return null
        return GrowthText.rowDesc(a.txt, nm, dvals[index], v, d.baseTime, d.isNew(a.kids[index]), dir)
    }

    /** Сводка папки в сортировке Δ ([parentV] — её размер в показанном режиме); null — Δ не показана. */
    fun summary(parentV: Long): String? {
        val d = delta
        return if (shown && d != null) GrowthText.summary(a.txt, parentV, d.of(a.node, a.apparent), d.baseTime) else null
    }

    /** Плашка «Δ с …» в сортировке Δ; null — Δ не показана. */
    fun badge(): String? {
        val d = delta
        return if (shown && d != null) GrowthText.badge(a.txt, d.baseTime) else null
    }

    /** Тап по плашке «Δ с …»: лист точки отсчёта (дата, возраст, размер; «Отметить сейчас»). */
    fun openBaseline() {
        val d = delta
        if (a.busy || a.h == 0L || !shown || d == null) return
        sheet?.dismiss()
        sheet = BaselineSheet(a, d.baseTime, d.baseBytes,
            onMark = { sheet?.dismiss(); Growth.markNow(a) },
            onClose = { a.refreshPending() }).also { it.show() }
    }

    /** Закрыть лист точки отсчёта (его данные — от старого дерева). */
    fun dismissSheet() { sheet?.dismiss(); sheet = null }

    /** onDestroy экрана. */
    fun dispose() = dismissSheet()
}
