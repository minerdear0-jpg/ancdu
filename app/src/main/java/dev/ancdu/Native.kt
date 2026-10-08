package dev.ancdu

const val ST_RUNNING = 0
const val ST_DONE = 1
const val ST_FAILED = 2
const val ST_CANCELLED = 3
const val ST_FULL = 4
const val SORT_SIZE = 0
const val SORT_NAME = 1
const val SORT_ITEMS = 2
const val F_DIR = 1
const val F_ERR = 2
const val F_HLDUP = 4
const val F_OTHERFS = 8
const val F_SYMLINK = 16
const val F_DELETED = 32
const val SRC_SCAN = 0
const val SRC_INDEX = 1

/** Мост к нативному ядру. Дескриптор 0 — ошибка (код в err[0]). Имена и пути — байты.
 *  Потоки: чтения дерева — с главного потока, delete/saveCache/free — на Holder.io, никогда параллельно на одном h.
 *  Единственное исключение: [deleteProgress] и [deleteStop] трогают только атомики удаления и
 *  ДОПУСТИМЫ с любого потока параллельно с идущим [delete] на том же h (дескриптор должен
 *  быть жив — free ещё не вызван; см. Holder.deleteProgress). Ничего другого параллельно с delete. */
object Native {
    init { System.loadLibrary("ancdu") }

    // Пути — байты UTF-8, как имена (символы вне BMP не искажаются modified UTF-8 JNI);
    // String-обёртки ниже. Путь с \u0000 ядро отклоняет (-EINVAL), не усекает.
    @JvmStatic external fun scanStartBytes(root: ByteArray, oneFs: Boolean, threads: Int, err: IntArray): Long
    @JvmStatic external fun rootStartBytes(helper: ByteArray, root: ByteArray, oneFs: Boolean, memfd: Boolean, err: IntArray): Long
    @JvmStatic external fun indexBeginBytes(root: ByteArray, capNodes: Long, err: IntArray): Long
    fun scanStart(root: String, oneFs: Boolean, threads: Int, err: IntArray): Long = scanStartBytes(b(root), oneFs, threads, err)
    fun rootStart(helper: String, root: String, oneFs: Boolean, memfd: Boolean, err: IntArray): Long =
        rootStartBytes(b(helper), b(root), oneFs, memfd, err)
    fun indexBegin(root: String, capNodes: Long, err: IntArray): Long = indexBeginBytes(b(root), capNodes, err)
    @JvmStatic external fun indexAdd(h: Long, rel: ByteArray, names: ByteArray, sizes: LongArray, n: Int): Int
    @JvmStatic external fun indexFinish(h: Long): Int
    @JvmStatic external fun openCacheBytes(path: ByteArray, err: IntArray): Long
    @JvmStatic external fun saveCacheBytes(h: Long, path: ByteArray): Int
    fun openCache(path: String, err: IntArray): Long = openCacheBytes(b(path), err)
    fun saveCache(h: Long, path: String): Int = saveCacheBytes(h, b(path))
    @JvmStatic external fun progress(h: Long, out: LongArray): ByteArray
    @JvmStatic external fun liveTop(h: Long, nodes: IntArray, disk: LongArray): Int
    @JvmStatic external fun liveName(h: Long, node: Int): ByteArray
    @JvmStatic external fun error(h: Long): ByteArray
    @JvmStatic external fun cancel(h: Long)
    @JvmStatic external fun childCount(h: Long, node: Int): Int
    @JvmStatic external fun children(h: Long, node: Int, sort: Int, apparent: Boolean, out: IntArray): Int
    @JvmStatic external fun nodeInfo(h: Long, nodes: IntArray, n: Int, out: LongArray)
    /** Крупнейшие файлы всего дерева: до out.size (≤ 256) id узлов по убыванию disk, без каталогов,
     *  повторных жёстких ссылок (F_HLDUP), удалённого и пустых; число записанных. Только чтение. */
    @JvmStatic external fun topFiles(h: Long, out: IntArray): Int
    /** Узлы с ошибкой скана или частичного удаления (F_ERR, не удалённые), по возрастанию id: первые
     *  min(out.size, [ERROR_NODES_MAX]) в out. Возвращает их общее число; записано ровно
     *  [errorNodesWritten] (total, out.size). Только чтение дерева. */
    @JvmStatic external fun errorNodes(h: Long, out: IntArray): Int
    /** Сколько id записал [errorNodes] при общем числе [total] и буфере [size]. */
    fun errorNodesWritten(total: Int, size: Int): Int = minOf(maxOf(total, 0), size, ERROR_NODES_MAX)
    /** Буфер [errorNodes] — на стеке ядра, не больше стольких id за вызов. */
    const val ERROR_NODES_MAX = 256
    @JvmStatic external fun name(h: Long, node: Int): ByteArray
    @JvmStatic external fun path(h: Long, node: Int): ByteArray
    @JvmStatic external fun parent(h: Long, node: Int): Int
    @JvmStatic external fun source(h: Long): Int
    /** 0 — удалено; -EINTR (-4) — остановлено [deleteStop], удалено частично; -ESTALE (-116) —
     *  узел подменён после скана ([NativeErr.changedSinceScan]), ничего не удалено, узел — F_ERR;
     *  иное <0 — ошибка. */
    @JvmStatic external fun deleteBytes(h: Long, node: Int, rootHelper: ByteArray?): Int
    fun delete(h: Long, node: Int, rootHelper: String?): Int = deleteBytes(h, node, rootHelper?.let(::b))
    /** Как delete через root ([rootHelper] под su), но узел /storage/emulated/<n>/X удаляется
     *  как /data/media/<n>/X — в обход FUSE. Путь не сопоставляется — -EINVAL, ничего не запущено. */
    @JvmStatic external fun deleteMediaBytes(h: Long, node: Int, rootHelper: ByteArray): Int
    fun deleteMedia(h: Long, node: Int, rootHelper: String): Int = deleteMediaBytes(h, node, b(rootHelper))
    /** Сколько записей удалено идущим (или последним) delete. Параллельно с delete — можно. */
    @JvmStatic external fun deleteProgress(h: Long): Long
    /** Просит остановить идущий delete (он вернёт -EINTR). Параллельно с delete — можно. */
    @JvmStatic external fun deleteStop(h: Long)
    /** Узел — F_ERR (массовый шаг MediaStore удалил часть, ядро не вызывалось). На io, как delete. */
    @JvmStatic external fun markErr(h: Long, node: Int): Int
    @JvmStatic external fun statfsBytes(path: ByteArray, out: LongArray): Int
    fun statfs(path: String, out: LongArray): Int = statfsBytes(b(path), out)
    @JvmStatic external fun free(h: Long)

    fun str(b: ByteArray): String = String(b, Charsets.UTF_8)

    private fun b(s: String): ByteArray = s.toByteArray(Charsets.UTF_8)
}
