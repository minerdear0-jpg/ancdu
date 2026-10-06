package dev.ancdu

import android.content.ContentResolver
import android.os.Bundle
import android.provider.MediaStore

/** [MediaRows] поверх ContentResolver: таблица files всех внешних томов. */
class ResolverRows(private val cr: ContentResolver) : MediaRows {
    private val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)

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

    /** _id — числа из [page], не ввод пользователя: литералы в IN безопасны и не упираются в лимит аргументов. */
    override fun delete(ids: LongArray): Int =
        if (ids.isEmpty()) 0
        else cr.delete(uri, "${MediaStore.MediaColumns._ID} IN (${ids.joinToString(",")})", null)
}
