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
        val q = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, where)
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, args)
            putString(ContentResolver.QUERY_ARG_SQL_SORT_ORDER, "${MediaStore.MediaColumns._ID} ASC")
            putString(ContentResolver.QUERY_ARG_SQL_LIMIT, limit.toString())
        }
        @Suppress("DEPRECATION") // _data: единственный столбец с абсолютным путём
        val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATA)
        val c = cr.query(uri, proj, q, null) ?: throw IllegalStateException("MediaStore недоступен")
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
        else cr.delete(deleteUri, "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",")}) AND ($where)", args)
}
