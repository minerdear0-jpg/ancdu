package dev.ancdu

import android.content.ContentResolver
import android.os.Bundle
import android.provider.MediaStore

/**
 * [MediaRows] поверх ContentResolver: таблица files всех внешних томов.
 * [keepFiles] — удалять только строки индекса (скрытый параметр MediaProvider deletedata=false:
 * в android14 он пропускает deleteIfAllowed, файл не трогается). Параметр не публичный —
 * перед использованием MediaClean проверяет его канарейкой (CleanCanary).
 */
class ResolverRows(private val cr: ContentResolver, keepFiles: Boolean = false) : MediaRows {
    private val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
    private val deleteUri = if (!keepFiles) uri
        else uri.buildUpon().appendQueryParameter("deletedata", "false").build()

    override fun page(where: String, args: Array<String>, limit: Int): List<MediaRow> {
        val q = selection(where, args).apply {
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns._ID} ASC")
            putString(ContentResolver.QUERY_ARG_SQL_LIMIT, limit.toString())
        }
        @Suppress("DEPRECATION") // _data: единственный столбец с абсолютным путём
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        val c = cr.query(uri, proj, q, null) ?: throw IllegalStateException("MediaStore unavailable")
        return c.use { cur ->
            val out = ArrayList<MediaRow>(cur.count.coerceAtLeast(0))
            while (cur.moveToNext()) out += MediaRow(cur.getLong(0), if (cur.isNull(1)) null else cur.getString(1))
            out
        }
    }

    /**
     * _id — числа из [page], не ввод пользователя: литералы в IN безопасны и не тратят аргументы.
     * [where] повторяется — строка, сменившая _data после [page], не удаляется.
     */
    override fun delete(ids: LongArray, where: String, args: Array<String>): Int =
        if (ids.isEmpty()) 0
        else cr.delete(deleteUri,
            selection("${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",")}) AND ($where)", args))

    /**
     * Выборка для query и delete с теми же правилами видимости. Без MediaMatch MediaProvider
     * (не FUSE-вызов) молча исключает is_pending/is_trashed строки — у файлов, созданных через
     * FUSE другими процессами, is_pending часто = 1, и массовый шаг не видел почти ничего.
     */
    private fun selection(where: String, args: Array<String>) = Bundle().apply {
        putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
        putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
        MediaMatch.put { k, v -> putInt(k, v) }
    }
}

/** Видимость строк для массовых шагов: и ожидающие (is_pending), и в корзине (is_trashed). */
object MediaMatch {
    fun put(put: (String, Int) -> Unit) {
        put(MediaStore.QUERY_ARG_MATCH_PENDING, MediaStore.MATCH_INCLUDE)
        put(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }
}
