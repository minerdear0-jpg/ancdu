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

    @JvmStatic external fun scanStart(root: String, oneFs: Boolean, threads: Int, err: IntArray): Long
    @JvmStatic external fun rootStart(helper: String, root: String, oneFs: Boolean, memfd: Boolean, err: IntArray): Long
    @JvmStatic external fun indexBegin(root: String, capNodes: Long, err: IntArray): Long
    @JvmStatic external fun indexAdd(h: Long, rel: ByteArray, names: ByteArray, sizes: LongArray, n: Int): Int
    @JvmStatic external fun indexFinish(h: Long): Int
    @JvmStatic external fun openCache(path: String, err: IntArray): Long
    @JvmStatic external fun saveCache(h: Long, path: String): Int
    @JvmStatic external fun progress(h: Long, out: LongArray): ByteArray
    @JvmStatic external fun liveTop(h: Long, nodes: IntArray, disk: LongArray): Int
    @JvmStatic external fun liveName(h: Long, node: Int): ByteArray
    @JvmStatic external fun error(h: Long): ByteArray
    @JvmStatic external fun cancel(h: Long)
    @JvmStatic external fun childCount(h: Long, node: Int): Int
    @JvmStatic external fun children(h: Long, node: Int, sort: Int, apparent: Boolean, out: IntArray): Int
    @JvmStatic external fun nodeInfo(h: Long, nodes: IntArray, n: Int, out: LongArray)
    @JvmStatic external fun name(h: Long, node: Int): ByteArray
    @JvmStatic external fun path(h: Long, node: Int): ByteArray
    @JvmStatic external fun parent(h: Long, node: Int): Int
    @JvmStatic external fun source(h: Long): Int
    /** 0 — удалено; -EINTR (-4) — остановлено [deleteStop], удалено частично; иное <0 — ошибка. */
    @JvmStatic external fun delete(h: Long, node: Int, rootHelper: String?): Int
    /** Как delete через root ([rootHelper] под su), но узел /storage/emulated/<n>/X удаляется
     *  как /data/media/<n>/X — в обход FUSE. Путь не сопоставляется — -EINVAL, ничего не запущено. */
    @JvmStatic external fun deleteMedia(h: Long, node: Int, rootHelper: String): Int
    /** Сколько записей удалено идущим (или последним) delete. Параллельно с delete — можно. */
    @JvmStatic external fun deleteProgress(h: Long): Long
    /** Просит остановить идущий delete (он вернёт -EINTR). Параллельно с delete — можно. */
    @JvmStatic external fun deleteStop(h: Long)
    @JvmStatic external fun statfs(path: String, out: LongArray): Int
    @JvmStatic external fun free(h: Long)

    fun str(b: ByteArray): String = String(b, Charsets.UTF_8)
}
