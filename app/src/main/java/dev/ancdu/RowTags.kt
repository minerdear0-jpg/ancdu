package dev.ancdu

import android.content.Context

/**
 * Метки безопасности строк одной папки браузера ([Tag.of]): лениво по строке, до следующего
 * [reset]. Метка приложения, ещё не известная, в отрисовке не ищется: строка показывает «app»,
 * метка ищется на своём потоке (AppLabels.fetch), затем [onLabel] — экран перерисовывает строки.
 * Главный поток.
 */
class RowTags(private val ctx: Context, private val t: Txt, private val onLabel: () -> Unit) {
    private var tags = arrayOfNulls<TagText>(0)
    private var done = BooleanArray(0)
    private var folder = ""
    private var root = ""
    private var sessionRoot = ""
    private var atRoot = false
    private var kind = Kind.SCAN
    /** Метка самой папки: такие же метки строк не рисуются, сводка называет её один раз. */
    private var ownTag: Tag? = null
    /** [ownTag] для показа (null — у папки метки нет). */
    var own: TagText? = null
        private set

    /**
     * Новая папка: [n] строк, [folderPath] — её путь, [treeRoot] — путь корня дерева, [atRoot] — это
     * корень, [sessionRoot] и [kind] — сессии (для DeletePolicy.blockReason).
     */
    fun reset(n: Int, folderPath: String, treeRoot: String, atRoot: Boolean, sessionRoot: String, kind: Kind,
              folderBlock: Block? = null) {
        tags = arrayOfNulls(n); done = BooleanArray(n)
        folder = folderPath; root = treeRoot; this.atRoot = atRoot; this.sessionRoot = sessionRoot; this.kind = kind
        ownTag = Tag.of(folderPath, F_DIR, Owner.packageOf(folderPath), folderBlock, treeRoot, ctx.packageName)
        own = ownTag?.let { AppLabels.resolve(ctx, t, it) }
    }

    /** Метка строки [i] с именем [nm] и флагами [flags]; null — метки нет. */
    fun at(i: Int, nm: String, flags: Int): TagText? {
        if (i !in done.indices) return null
        if (done[i]) return tags[i]
        done[i] = true
        val path = if (folder.endsWith("/")) folder + nm else "$folder/$nm"
        val tag = Tag.of(path, flags, Owner.packageOf(path), DeletePolicy.blockReason(path, false, atRoot, sessionRoot, flags, kind),
            root, ctx.packageName) ?: return null
        if (Tag.suppressed(tag, ownTag)) return null
        val pkg = tag.pkg
        val shown = if (pkg == null || AppLabels.known(pkg)) tag.resolve(t, pkg?.let(AppLabels::cached))
        else {
            AppLabels.fetch(ctx, pkg) { done.fill(false); onLabel() }
            tag.resolve(t, null)
        }
        return shown.also { tags[i] = it }
    }

    /** Метка пути [path] (флаги [flags], запрет [block]) — для листа удаления; метку приложения ищет сразу. */
    fun of(path: String, flags: Int, block: Block?): TagText? =
        Tag.of(path, flags, Owner.packageOf(path), block, root, ctx.packageName)?.let { AppLabels.resolve(ctx, t, it) }
}
