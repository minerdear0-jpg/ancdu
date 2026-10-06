package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DeletePolicyTest {
    @Test fun systemPaths() {
        for (p in listOf("/data/system", "/data/adb", "/data/app", "/data/misc", "/system", "/vendor",
                "/apex", "/proc", "/sys", "/dev")) {
            assertTrue(p, DeletePolicy.isSystemPath(p))
            assertTrue("$p/", DeletePolicy.isSystemPath("$p/"))
        }
        // выше защищённых путей — тоже нельзя
        assertTrue(DeletePolicy.isSystemPath("/"))
        assertTrue(DeletePolicy.isSystemPath("/data"))
        // внутри защищённых путей — тоже нельзя
        assertTrue(DeletePolicy.isSystemPath("/data/system/packages.xml"))
        assertTrue(DeletePolicy.isSystemPath("/data/app/~~x==/com.a-1"))
        assertTrue(DeletePolicy.isSystemPath("/dev/block"))
        // соседи с общим префиксом — не защищены
        assertFalse(DeletePolicy.isSystemPath("/data/system_ce"))
        assertFalse(DeletePolicy.isSystemPath("/data/apps"))
        assertFalse(DeletePolicy.isSystemPath("/devices"))
        assertFalse(DeletePolicy.isSystemPath("/data/data/com.example"))
        assertFalse(DeletePolicy.isSystemPath("/storage/emulated/0/Download"))
        assertFalse(DeletePolicy.isSystemPath("/data/media/0/DCIM"))
        assertFalse(DeletePolicy.isSystemPath(""))
    }

    @Test fun exactBlockedRoots() {
        val appData = listOf("/data/data", "/data/user", "/data/user/0", "/data/user/10",
            "/data/user_de", "/data/user_de/0", "/data/user_de/150")
        val storage = listOf("/data/media", "/data/media/0", "/data/media/11",
            "/storage/emulated", "/storage/emulated/0", "/storage/emulated/10")
        for (p in appData) for (v in listOf(p, "$p/", "$p//")) {
            assertEquals(v, DeletePolicy.ALL_APP_DATA, DeletePolicy.exactBlockReason(v))
            assertEquals(v, DeletePolicy.ALL_APP_DATA,
                reason(v, sessionRoot = "/data", flags = F_DIR, kind = Kind.ROOT))
        }
        for (p in storage) for (v in listOf(p, "$p/", "$p//")) {
            assertEquals(v, DeletePolicy.USER_STORAGE, DeletePolicy.exactBlockReason(v))
            assertEquals(v, DeletePolicy.USER_STORAGE,
                reason(v, sessionRoot = "/", flags = F_DIR, kind = Kind.ROOT))
        }
        // раньше индексного запрета
        assertEquals(DeletePolicy.USER_STORAGE,
            reason("/storage/emulated/0/", sessionRoot = "/storage", flags = F_DIR, kind = Kind.INDEX))
        // потомки и соседи с общим префиксом — удалять можно
        for (p in listOf("/data/data/com.example", "/data/data/com.example/", "/data/data/123",
                "/data/user/0/com.example", "/data/user/0/com.example/cache", "/data/user_de/0/com.a/",
                "/data/media/0/DCIM", "/data/media/0/DCIM/", "/data/media/obb",
                "/storage/emulated/0/Download", "/storage/emulated/0/DCIM/", "/storage/emulated/obb",
                "/data/user/x", "/data/user/0x", "/data/database", "/data/users", "/data/media0",
                "/storage/emulated0", "/storage/self", "/mnt/media_rw")) {
            assertNull(p, DeletePolicy.exactBlockReason(p))
            assertNull(p, reason(p, sessionRoot = "/", flags = F_DIR, kind = Kind.ROOT))
        }
    }

    private fun reason(path: String, scanRoot: Boolean = false, parentIsRoot: Boolean = false,
                       sessionRoot: String = "/storage/emulated/0", flags: Int = 0, kind: Kind = Kind.SCAN) =
        DeletePolicy.blockReason(path, scanRoot, parentIsRoot, sessionRoot, flags, kind)

    @Test fun reasons() {
        assertNull(reason("/storage/emulated/0/Download/a.bin"))
        assertNull(reason("/storage/emulated/0/Download", flags = F_DIR, parentIsRoot = true))
        assertEquals(DeletePolicy.OTHER_FS, reason("/storage/emulated/0/x", flags = F_DIR or F_OTHERFS))
        assertEquals(DeletePolicy.SYSTEM, reason("/storage/emulated/0", scanRoot = true, flags = F_DIR))
        assertEquals(DeletePolicy.SYSTEM, reason("/data", sessionRoot = "/", parentIsRoot = true, flags = F_DIR))
        assertEquals(DeletePolicy.SYSTEM, reason("/mnt", sessionRoot = "/", parentIsRoot = true, flags = F_DIR))
        assertEquals(DeletePolicy.SYSTEM, reason("/data/app/x", sessionRoot = "/data", flags = F_DIR))
        assertNull(reason("/data/data/com.a", sessionRoot = "/data", flags = F_DIR, kind = Kind.ROOT))
        // индекс: каталоги нельзя, файлы можно
        assertEquals(DeletePolicy.INDEX_DIR, reason("/storage/emulated/0/DCIM", flags = F_DIR, kind = Kind.INDEX))
        assertNull(reason("/storage/emulated/0/DCIM/a.jpg", kind = Kind.INDEX))
        // ↪ сильнее системного пути и индекса
        assertEquals(DeletePolicy.OTHER_FS, reason("/proc", sessionRoot = "/", parentIsRoot = true,
            flags = F_DIR or F_OTHERFS))
    }
}

class DeleteTierTest {
    @Test fun pauseTier() {
        val gib = 1L shl 30
        assertFalse(DeletePolicy.needsPause(viaRoot = false, owned = false, disk = gib - 1))
        assertTrue(DeletePolicy.needsPause(viaRoot = false, owned = false, disk = gib))
        assertFalse(DeletePolicy.needsPause(viaRoot = true, owned = false, disk = 10))
        assertFalse(DeletePolicy.needsPause(viaRoot = false, owned = true, disk = 10))
        assertTrue(DeletePolicy.needsPause(viaRoot = true, owned = true, disk = 10))
    }
}
