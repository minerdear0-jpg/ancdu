package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OwnerTest {
    @Test fun appDataPaths() {
        assertEquals("com.a.b", Owner.packageOf("/data/data/com.a.b"))
        assertEquals("com.a.b", Owner.packageOf("/data/data/com.a.b/cache/x"))
        assertEquals("com.a.b", Owner.packageOf("/data/user/0/com.a.b"))
        assertEquals("com.a.b", Owner.packageOf("/data/user/10/com.a.b/files"))
        assertEquals("com.a.b", Owner.packageOf("/data/user_de/0/com.a.b/code_cache"))
        assertEquals("com.a.b", Owner.packageOf("/storage/emulated/0/Android/data/com.a.b"))
        assertEquals("com.a.b", Owner.packageOf("/storage/emulated/0/Android/data/com.a.b/files/x.bin"))
        assertEquals("com.a.b", Owner.packageOf("/storage/emulated/0/Android/obb/com.a.b/main.1.com.a.b.obb"))
        assertEquals("com.a.b", Owner.packageOf("/data/media/0/Android/data/com.a.b"))
        assertEquals("com.a_b.c9", Owner.packageOf("/mnt/sdcard/Android/data/com.a_b.c9/"))
    }

    @Test fun notOwned() {
        assertNull(Owner.packageOf("/data/data"))
        assertNull(Owner.packageOf("/data/user/0"))
        assertNull(Owner.packageOf("/data/user/x/com.a.b"))
        assertNull(Owner.packageOf("/data/user_de/0"))
        assertNull(Owner.packageOf("/storage/emulated/0/Android/data"))
        assertNull(Owner.packageOf("/storage/emulated/0/Android/data/.nomedia"))
        assertNull(Owner.packageOf("/storage/emulated/0/Android/media/com.a.b"))
        assertNull(Owner.packageOf("/storage/emulated/0/Download/com.a.b"))
        assertNull(Owner.packageOf("/data/data/nodots"))
        assertNull(Owner.packageOf(""))
        assertNull(Owner.packageOf("/"))
    }
}
