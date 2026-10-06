package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FastDeleteTest {
    @Test fun mapsCleanPaths() {
        assertEquals("/data/media/0/DCIM", DeletePolicy.mediaPath("/storage/emulated/0/DCIM"))
        assertEquals("/data/media/10/a b/c\nd", DeletePolicy.mediaPath("/storage/emulated/10/a b/c\nd"))
        assertEquals("/data/media/0/.thumbnails/x", DeletePolicy.mediaPath("/storage/emulated/0/.thumbnails/x"))
        assertEquals("/data/media/0/Android/data/pkg", DeletePolicy.mediaPath("/storage/emulated/0/Android/data/pkg"))
    }

    @Test fun refusesAnythingUnclean() {
        for (p in listOf("/storage/emulated/0", "/storage/emulated/0/", "/storage/emulated/",
                "/storage/emulated//x", "/storage/emulated/a/x", "/storage/emulated/0x/y",
                "/storage/emulated/٣/x", "/storage/emulated/0//x", "/storage/emulated/0/x/",
                "/storage/emulated/0/..", "/storage/emulated/0/x/../y", "/storage/emulated/0/.",
                "/sdcard/x", "/storage/self/primary/x", "storage/emulated/0/x", "/data/media/0/x",
                "/storage/emulated/0/x//y", ""))
            assertNull("mapped: '$p'", DeletePolicy.mediaPath(p))
    }

    @Test fun policyChecksMappedPath() {
        assertNull(DeletePolicy.fastBlockReason("/storage/emulated/0/DCIM"))
        assertNull(DeletePolicy.fastBlockReason("/storage/emulated/0/Android/data/pkg"))
        assertEquals(DeletePolicy.ANDROID_DIR, DeletePolicy.fastBlockReason("/storage/emulated/0/Android"))
        assertEquals(DeletePolicy.NO_FAST, DeletePolicy.fastBlockReason("/storage/emulated/0"))
        assertEquals(DeletePolicy.NO_FAST, DeletePolicy.fastBlockReason("/data/media/0/DCIM"))
        assertEquals(DeletePolicy.NO_FAST, DeletePolicy.fastBlockReason("/storage/emulated/0/x/../.."))
        // сопоставленный путь проходит общий список /data
        assertNull(DeletePolicy.dataBlockReason("/data/media/0/DCIM"))
        assertEquals(DeletePolicy.USER_STORAGE, DeletePolicy.exactBlockReason("/data/media/0"))
        assertEquals(DeletePolicy.ANDROID_DIR, DeletePolicy.exactBlockReason("/data/media/0/Android"))
    }

    @Test fun defaultsOnFromThousandItemsOnlyWhenRootGranted() {
        assertFalse(DeletePolicy.fastByDefault(999, RootState.GRANTED))
        assertTrue(DeletePolicy.fastByDefault(1000, RootState.GRANTED))
        // root не подтверждён или отклонён — по умолчанию выключено
        for (s in listOf(RootState.UNKNOWN, RootState.ASKING, RootState.DENIED))
            assertFalse("$s", DeletePolicy.fastByDefault(100_000, s))
    }
}
