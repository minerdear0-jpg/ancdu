package dev.ancdu

/**
 * Курсор «вы были здесь» браузера [a]: строка объекта, на котором был пользователь (после карточки,
 * «назад» из папки, возврата на экран). Объект — по ИМЕНАМ (цепочка от корня), не по id узла:
 * подстановка дерева перенумеровывает узлы. Один на экран; тап по любой строке снимает его.
 * Главный поток.
 */
class BrowserCursor(private val a: BrowserActivity) {
    private var chain: List<ByteArray>? = null
    /** Строка объекта, когда он был найден в последний раз: на её место встаёт сосед, если объекта нет. */
    private var index = -1
    /** Объект пропадёт своим удалением: без заметки «уже нет на диске» (итог уже в подвале). */
    private var quiet = false

    /** Строка курсора в текущем списке; -1 — курсора нет. */
    val row: Int get() = if (chain == null) -1 else index

    /** Состояние для сохранения (переживает пересоздание и смерть процесса). */
    fun state(): BrowserState.Cursor? = chain?.let { BrowserState.Cursor(it, index, quiet) }

    fun clear() {
        chain = null; index = -1; quiet = false
        a.list.cursorRow = -1
    }

    /** Курсор на строке [i] текущего уровня; [flash] — «прибытие» (вспышка 1,5 с, если можно). */
    fun set(i: Int, flash: Boolean, quiet: Boolean = false) {
        if (i !in 0 until a.n) { clear(); return }
        chain = a.pathNames(a.h, a.kids[i]); index = i; this.quiet = quiet
        a.list.cursorRow = i
        if (flash) flash() else a.list.stopFlash()
    }

    /** Сохранённый курсор [c] — по именам в текущем уровне ([relocate]); [flash] — прибытие. */
    fun restore(c: BrowserState.Cursor?, flash: Boolean) {
        if (c == null) return
        chain = c.chain; index = c.index; quiet = c.quiet
        relocate()
        if (flash && chain != null) flash()
    }

    /**
     * load() уровня: строка курсора заново по именам (одним вызовом ядра). Другая папка — курсора нет.
     * Объекта нет — сосед (та же позиция) и «… уже нет на диске», после своего удаления — без заметки.
     */
    fun relocate() {
        val c = chain ?: return
        val node = if (a.giant.on) Native.resolve(a.h, c, dirOnly = false).takeIf { it.exact }?.node ?: -1
            else if (!CursorPlace.inFolder(c, a.pathNames(a.h, a.node))) { clear(); return }
            else Native.childNamed(a.h, a.node, c.last(), false)
        val found = if (node < 0) -1 else a.kids.indexOf(node).takeIf { it in 0 until a.n } ?: -1
        val r = CursorPlace.row(found, index, a.n)
        if (r < 0) { clear(); return }
        if (CursorPlace.noteGone(found, quiet)) a.note(DeleteProgress.gone(a.txt, Native.str(c.last())))
        if (found < 0) { chain = a.pathNames(a.h, a.kids[r]); quiet = false }
        index = r
        a.list.cursorRow = r
    }

    /** Удаление объектов [nodes] начинается: курсор — на месте первого из них по списку, без заметки. */
    fun atDeleted(nodes: IntArray) {
        val i = nodes.map { a.kids.indexOf(it) }.filter { it in 0 until a.n }.minOrNull() ?: return
        set(i, flash = false, quiet = true)
    }

    /** Вернулись на экран: вспышка строки курсора, если он есть. */
    fun arrived() { if (chain != null) flash() }

    /** Вспышка — только с анимациями и если с ней на экране не больше двух амберных акцентов. */
    private fun flash() {
        if (CursorPlace.flash(Motion.on(), a.accentsShown())) a.list.flash(CursorPlace.FLASH_MS) else a.list.stopFlash()
    }

    /** Акцентов стало больше (чип «новее» появился): вспышка не должна сделать их три. */
    fun budget() { if (a.list.flashing && !CursorPlace.flash(true, a.accentsShown())) a.list.stopFlash() }
}
