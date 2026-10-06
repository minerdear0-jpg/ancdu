package dev.ancdu

import android.provider.MediaStore
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Регрессия (DUT, release): без MATCH_INCLUDE MediaProvider исключал строки is_pending = 1
 * (файлы, созданные через FUSE другими процессами) — массовый шаг находил 1 строку из 2 001,
 * и всё удалял пофайловый rm_tree через FUSE.
 */
class MediaMatchTest {
    @Test fun bulkQueriesIncludePendingAndTrashedRows() {
        val got = LinkedHashMap<String, Int>()
        MediaMatch.put { k, v -> got[k] = v }
        assertEquals(mapOf(
            MediaStore.QUERY_ARG_MATCH_PENDING to MediaStore.MATCH_INCLUDE,
            MediaStore.QUERY_ARG_MATCH_TRASHED to MediaStore.MATCH_INCLUDE), got)
    }
}
