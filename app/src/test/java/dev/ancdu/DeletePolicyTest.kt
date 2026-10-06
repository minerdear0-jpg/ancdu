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
        // соседи с общим префиксом — не в списке PROTECTED, но под /data их закрывает список
        // разрешённого (dataAllowlist): /data/system_ce больше не удаляется
        for (p in listOf("/data/system_ce", "/data/apps")) {
            assertFalse(p, DeletePolicy.isSystemPath(p))
            assertEquals(p, DeletePolicy.SYSTEM_DIR, reason(p, sessionRoot = "/data", flags = F_DIR, kind = Kind.ROOT))
        }
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
                "/data/media/0/DCIM", "/data/media/0/DCIM/",
                "/storage/emulated/0/Download", "/storage/emulated/0/DCIM/", "/storage/emulated/obb",
                "/storage/emulated0", "/storage/self", "/mnt/media_rw")) {
            assertNull(p, DeletePolicy.exactBlockReason(p))
            assertNull(p, reason(p, sessionRoot = "/", flags = F_DIR, kind = Kind.ROOT))
        }
        // не корни, но под /data вне списка разрешённого
        for (p in listOf("/data/media/obb", "/data/user/x", "/data/user/0x", "/data/database",
                "/data/users", "/data/media0")) {
            assertNull(p, DeletePolicy.exactBlockReason(p))
            assertEquals(p, DeletePolicy.SYSTEM_DIR, reason(p, sessionRoot = "/", flags = F_DIR, kind = Kind.ROOT))
        }
        // папка Android хранилища — только она сама; её содержимое удалять можно
        for (p in listOf("/data/media/0/Android", "/data/media/10/Android/", "/storage/emulated/0/Android",
                "/storage/emulated/0//Android//")) {
            assertEquals(p, DeletePolicy.ANDROID_DIR, DeletePolicy.exactBlockReason(p))
            assertEquals(p, DeletePolicy.ANDROID_DIR, reason(p, flags = F_DIR, kind = Kind.ROOT))
            assertEquals(p, DeletePolicy.ANDROID_DIR, reason(p, flags = F_DIR, kind = Kind.SCAN))
        }
        for (p in listOf("/data/media/0/Android/data/com.a", "/data/media/0/Android/obb",
                "/storage/emulated/0/Android/data", "/storage/emulated/0/Android/data/com.a/cache",
                "/storage/emulated/0/Androidx", "/storage/emulated/0/x/Android")) {
            assertNull(p, DeletePolicy.exactBlockReason(p))
            assertNull(p, reason(p, flags = F_DIR, kind = Kind.ROOT))
        }
    }

    @Test fun dataAllowlist() {
        val allowed = listOf(
            "/data/data/com.a", "/data/data/com.a/", "/data/data/com.a/cache/x.bin",
            "/data/user/0/com.a", "/data/user/0/com.a/files", "/data/user/10/com.b//",
            "/data/user_de/0/com.a", "/data/user_de/150/com.a/code_cache",
            "/data/media/0/DCIM", "/data/media/0/DCIM/a.jpg", "/data/media/11/Download/",
            "/data/local/tmp/x", "/data/local/tmp/x/", "/data/local/tmp/dir/f")
        for (p in allowed) {
            assertNull(p, DeletePolicy.dataBlockReason(p))
            for (k in listOf(Kind.ROOT, Kind.SCAN))
                assertNull("$p $k", reason(p, sessionRoot = "/data", flags = F_DIR, kind = k))
            // файлы — и в индексе/кэше
            for (k in Kind.values()) assertNull("$p $k", reason(p, sessionRoot = "/data", kind = k))
        }
        // всё прочее под /data, включая границы списка
        val blocked = listOf(
            "/data/local", "/data/local/", "/data/local/tmp", "/data/local/tmp/", "/data/local/tmp//",
            "/data/local/tmpX", "/data/local/tmpX/y", "/data/local/other",
            "/data/system_ce", "/data/system_ce/0", "/data/system_de/0/x", "/data/vendor",
            "/data/property", "/data/dalvik-cache/arm64/x.dex", "/data/nfc", "/data/tombstones/t0",
            "/data/user/x/com.a", "/data/user/0x/com.a", "/data/user_de/a/com.a",
            "/data/media/obb/com.a", "/data/media/x/DCIM", "/data/datax/com.a",
            "/data/data/com.a/../../system_ce", "/data/data/..", "/data/local/tmp/.",
            "/data/media/0/../../vendor")
        for (p in blocked) {
            assertEquals(p, DeletePolicy.SYSTEM_DIR, DeletePolicy.dataBlockReason(p))
            for (k in Kind.values()) {
                assertEquals("$p $k", DeletePolicy.SYSTEM_DIR,
                    reason(p, sessionRoot = "/data", flags = F_DIR, kind = k))
                assertEquals("$p $k file", DeletePolicy.SYSTEM_DIR, reason(p, sessionRoot = "/data", kind = k))
            }
        }
        // сами корни пакетов разрешены (это и есть «данные приложения»); их родители — нет
        assertNull(reason("/data/data/com.a", sessionRoot = "/data", flags = F_DIR, kind = Kind.ROOT))
        for (p in listOf("/data/user/0", "/data/user_de/0", "/data/data")) {
            assertEquals(p, DeletePolicy.ALL_APP_DATA, reason(p, sessionRoot = "/data", flags = F_DIR, kind = Kind.ROOT))
        }
        assertEquals(DeletePolicy.USER_STORAGE,
            reason("/data/media/0", sessionRoot = "/data", flags = F_DIR, kind = Kind.ROOT))
        // путь вне /data список не трогает; /data и /data/ сами — системные
        for (p in listOf("/storage/emulated/0/Download", "/mnt/media_rw/x", "/datax", "/sdcard/x"))
            assertNull(p, DeletePolicy.dataBlockReason(p))
        assertEquals(DeletePolicy.SYSTEM_DIR, DeletePolicy.dataBlockReason("/data"))
        assertEquals(DeletePolicy.SYSTEM_DIR, DeletePolicy.dataBlockReason("/data//"))
        // сессия без root (скан общего хранилища таких путей не даёт) — список действует так же
        assertEquals(DeletePolicy.SYSTEM_DIR,
            reason("/data/vendor/x", sessionRoot = "/storage/emulated/0", kind = Kind.SCAN))
        assertNull(reason("/data/data/com.a/f", sessionRoot = "/storage/emulated/0", kind = Kind.SCAN))
    }

    @Test fun cacheDirs() {
        val s = "/storage/emulated/0"
        assertEquals(DeletePolicy.STALE_CACHE, reason("$s/DCIM", flags = F_DIR, kind = Kind.CACHE))
        assertEquals(DeletePolicy.STALE_CACHE,
            reason("$s/Download", parentIsRoot = true, flags = F_DIR, kind = Kind.CACHE))
        assertEquals(DeletePolicy.STALE_CACHE,
            reason("/data/data/com.a", sessionRoot = "/data", flags = F_DIR, kind = Kind.CACHE))
        // файлы в кэше удалять можно
        assertNull(reason("$s/DCIM/a.jpg", kind = Kind.CACHE))
        assertNull(reason("/data/data/com.a/f", sessionRoot = "/data", kind = Kind.CACHE))
        // более сильные причины — раньше
        assertEquals(DeletePolicy.USER_STORAGE, reason(s, sessionRoot = "/storage", flags = F_DIR, kind = Kind.CACHE))
        assertEquals(DeletePolicy.SYSTEM_DIR,
            reason("/data/vendor", sessionRoot = "/data", flags = F_DIR, kind = Kind.CACHE))
        assertEquals(DeletePolicy.OTHER_FS, reason("$s/x", flags = F_DIR or F_OTHERFS, kind = Kind.CACHE))
    }

    @Test fun failureKind() {
        val eperm = -1; val eio = -5; val exdev = -18; val eacces = -13; val enoent = -2
        assertTrue(DeletePolicy.nothingDeleted(eperm, viaRoot = true))
        for (r in listOf(eio, exdev, eacces, enoent)) assertFalse("$r", DeletePolicy.nothingDeleted(r, viaRoot = true))
        // без root -EPERM даёт сам rm_tree — часть могла удалиться
        for (r in listOf(eperm, eio, exdev, eacces)) assertFalse("$r", DeletePolicy.nothingDeleted(r, viaRoot = false))
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

class PendingTreeTest {
    private fun reason(path: String, flags: Int, kind: Kind, pending: Boolean) =
        DeletePolicy.blockReason(path, false, false, "/storage/emulated/0", flags, kind, pending)

    /** Есть новее (pending) при дереве из кэша: ни файлы, ни каталоги не удаляются. */
    @Test fun cacheWithNewerTreeBlocksEverything() {
        assertEquals(DeletePolicy.NEWER, reason("/storage/emulated/0/a.bin", 0, Kind.CACHE, true))
        assertEquals(DeletePolicy.NEWER, reason("/storage/emulated/0/DCIM", F_DIR, Kind.CACHE, true))
        assertEquals("есть новее — обновите", DeletePolicy.NEWER)
        // без pending — прежние правила кэша
        assertNull(reason("/storage/emulated/0/a.bin", 0, Kind.CACHE, false))
        assertEquals(DeletePolicy.STALE_CACHE, reason("/storage/emulated/0/DCIM", F_DIR, Kind.CACHE, false))
        // живой скан с pending — удалять можно (pending станет «грязным» и пересканируется)
        assertNull(reason("/storage/emulated/0/a.bin", 0, Kind.SCAN, true))
        assertNull(reason("/storage/emulated/0/DCIM", F_DIR, Kind.SCAN, true))
        // системные запреты сильнее
        assertEquals(DeletePolicy.ANDROID_DIR, reason("/storage/emulated/0/Android", F_DIR, Kind.CACHE, true))
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
