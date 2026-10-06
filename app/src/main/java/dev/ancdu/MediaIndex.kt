package dev.ancdu

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.provider.MediaStore
import java.io.ByteArrayOutputStream

class IndexBatch(private val capacity: Int = 2048) {
    private val rel = ByteArrayOutputStream()
    private val names = ByteArrayOutputStream()
    val sizes = LongArray(capacity)
    var n = 0
        private set

    /** true — пакет заполнен, пора отправлять. */
    fun add(relDir: String, name: String, size: Long): Boolean {
        rel.write(relDir.toByteArray(Charsets.UTF_8)); rel.write(0)
        names.write(name.toByteArray(Charsets.UTF_8)); names.write(0)
        sizes[n++] = size
        return n == capacity
    }

    fun relBytes(): ByteArray = rel.toByteArray()
    fun nameBytes(): ByteArray = names.toByteArray()
    fun clear() { rel.reset(); names.reset(); n = 0 }
}

object MediaIndex {
    const val ROOT = "/storage/emulated/0"
    private const val FORMAT_ASSOCIATION = 12289 // каталоги в таблице files

    /**
     * Ярус 1: дерево общего хранилища из индекса MediaProvider, без обхода FUSE.
     * Можно вызывать с рабочего потока: дескриптор до возврата принадлежит только этому вызову.
     * При любой ошибке создаваемый дескриптор освобождается здесь, наружу — [IllegalStateException].
     */
    fun build(ctx: Context): Long {
        val uri = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val proj = arrayOf(MediaStore.MediaColumns.RELATIVE_PATH, MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE)
        val c = try {
            query(ctx, uri, proj, "format != $FORMAT_ASSOCIATION") ?: query(ctx, uri, proj, null)
        } catch (e: SecurityException) {
            throw IllegalStateException("no access to the MediaStore index: ${e.message}", e)
        } catch (e: Exception) {
            throw IllegalStateException("MediaStore query failed: ${e.message}", e)
        } ?: throw IllegalStateException("MediaStore unavailable")
        c.use { cur ->
            val err = IntArray(1)
            val h = Native.indexBegin(ROOT, cur.count * 2L + 4096, err)
            check(h != 0L) { "index: ${err[0]}" }
            try {
                val b = IndexBatch()
                fun flush() {
                    if (b.n == 0) return
                    val r = Native.indexAdd(h, b.relBytes(), b.nameBytes(), b.sizes, b.n)
                    b.clear()
                    check(r == 0) { "index add: $r" }
                }
                while (cur.moveToNext()) {
                    val rel = cur.getString(0) ?: continue
                    val name = cur.getString(1) ?: continue
                    if (b.add(rel, name, cur.getLong(2))) flush()
                }
                flush()
                val r = Native.indexFinish(h)
                check(r == 0) { "index finish: $r" }
                return h
            } catch (e: Throwable) {
                Native.free(h)
                throw e
            }
        }
    }

    private fun query(ctx: Context, uri: Uri, proj: Array<String>, sel: String?): Cursor? =
        try { ctx.contentResolver.query(uri, proj, sel, null, null) } catch (e: IllegalArgumentException) { null }
}
