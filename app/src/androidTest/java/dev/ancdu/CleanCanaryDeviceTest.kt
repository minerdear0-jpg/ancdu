package dev.ancdu

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Канарейка MediaClean на настоящем MediaProvider: deletedata=false удаляет строку, файл остаётся.
 * Создаёт и убирает только свой mkdtemp-каталог в Android/media/<пакет>/.
 */
@RunWith(AndroidJUnit4::class)
class CleanCanaryDeviceTest {
    @Test fun rowsOnlyDeleteKeepsFile() {
        val ins = InstrumentationRegistry.getInstrumentation()
        AppOps.restoreAfterExit(ins, "MANAGE_EXTERNAL_STORAGE", AppOps.get(ins, "MANAGE_EXTERNAL_STORAGE"))
        AppOps.set(ins, "MANAGE_EXTERNAL_STORAGE", "allow")
        val ctx = ins.targetContext
        @Suppress("DEPRECATION")
        val base = ctx.externalMediaDirs.first { it != null && it.path.startsWith("/storage/emulated/") }
        val before = base.listFiles()?.filter { it.name.startsWith("canary-") }?.toSet() ?: emptySet()
        val ok = CleanCanary.rowsOnlyWorks(MediaClean.Canary(ctx, ctx.contentResolver))
        val after = base.listFiles()?.filter { it.name.startsWith("canary-") }?.toSet() ?: emptySet()
        assertEquals("канарейка не убрана", before, after)
        assertTrue("deletedata=false не подтверждён — MediaClean уйдёт на scanFile", ok)
    }
}
