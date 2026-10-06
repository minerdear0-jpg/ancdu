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
