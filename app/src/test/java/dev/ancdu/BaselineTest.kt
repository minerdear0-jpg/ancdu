package dev.ancdu

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Точка отсчёта «что выросло»: правило ротации A/B (чистое) и файлы A/B рядом с кэшем. Файлы — только
 * в свежем каталоге Files.createTempDirectory (абсолютный путь проверяется); удаляет только сам
 * BaselineFiles (свои .base-a/.base-b/.tmp) и, в конце, тест — свою песочницу.
 */
class BaselineTest {
    private val day = 86_400_000L
    private lateinit var box: File

    @Before fun setUp() {
        box = Files.createTempDirectory("ancdu-baseline-").toFile()
        assertTrue(box.isAbsolute && box.name.startsWith("ancdu-baseline-"))
    }

    @After fun tearDown() {
        val tmp = File(System.getProperty("java.io.tmpdir") ?: return).canonicalFile
        if (box.canonicalFile.parentFile == tmp && box.name.startsWith("ancdu-baseline-")) box.deleteRecursively()
    }

    @Test fun rotationRules() {
        val r = BaselineRules
        assertEquals(BaselineRules.Step.SET_A, r.onSave(hasA = false, hasB = false, ageB = 0))
        assertEquals(BaselineRules.Step.SET_A, r.onSave(hasA = false, hasB = true, ageB = 30 * day))
        assertEquals(BaselineRules.Step.SET_B, r.onSave(hasA = true, hasB = false, ageB = 0))
        assertEquals(BaselineRules.Step.NONE, r.onSave(hasA = true, hasB = true, ageB = 7 * day - 1))
        assertEquals(BaselineRules.Step.SHIFT, r.onSave(hasA = true, hasB = true, ageB = 7 * day))
        assertEquals(BaselineRules.Step.SHIFT, r.onSave(hasA = true, hasB = true, ageB = 40 * day))
        // Часы ушли назад: B «из будущего» — ничего не трогать.
        assertEquals(BaselineRules.Step.NONE, r.onSave(hasA = true, hasB = true, ageB = -day))
    }

    /** Кэш [cache] «сохранён» в момент [t]: новые байты, затем rename, как arena_save_file. */
    private fun save(cache: File, bytes: ByteArray, t: Long) {
        val tmp = File(cache.path + ".tmp")
        tmp.writeBytes(bytes)
        assertTrue(tmp.renameTo(cache))
        assertTrue(cache.setLastModified(t))
    }

    @Test fun rotateThroughTwoWeeks() {
        val cache = File(box, "last-app_storage_emulated_0.ancdu")
        val f = BaselineFiles(box, cache.name)
        assertEquals(File(box, cache.name + ".base-a"), f.a)
        assertEquals(File(box, cache.name + ".base-b"), f.b)
        val t0 = 1_700_000_000_000L
        save(cache, byteArrayOf(1), t0)
        assertEquals(BaselineRules.Step.SET_A, f.onSaved(cache, t0))
        assertArrayEquals(byteArrayOf(1), f.a.readBytes())
        assertFalse(f.b.exists())
        assertEquals(t0, f.time())
        // Тот же день: B — кандидат, A не меняется.
        save(cache, byteArrayOf(2), t0 + 3_600_000)
        assertEquals(BaselineRules.Step.SET_B, f.onSaved(cache, t0 + 3_600_000))
        assertArrayEquals(byteArrayOf(1), f.a.readBytes())
        assertArrayEquals(byteArrayOf(2), f.b.readBytes())
        // Неделя без часа: ничего.
        save(cache, byteArrayOf(3), t0 + 7 * day)
        assertEquals(BaselineRules.Step.NONE, f.onSaved(cache, t0 + 7 * day))
        assertArrayEquals(byteArrayOf(1), f.a.readBytes())
        assertArrayEquals(byteArrayOf(2), f.b.readBytes())
        // B старше недели: A := B, B := этот скан.
        save(cache, byteArrayOf(4), t0 + 8 * day)
        assertEquals(BaselineRules.Step.SHIFT, f.onSaved(cache, t0 + 8 * day))
        assertArrayEquals(byteArrayOf(2), f.a.readBytes())
        assertEquals(t0 + 3_600_000, f.time())
        assertArrayEquals(byteArrayOf(4), f.b.readBytes())
        // Новое сохранение кэша (новый inode) не трогает ни A, ни B (жёсткая ссылка или копия).
        save(cache, byteArrayOf(5), t0 + 9 * day)
        assertArrayEquals(byteArrayOf(2), f.a.readBytes())
        assertArrayEquals(byteArrayOf(4), f.b.readBytes())
        // Не больше двух файлов на ключ, без хвостов .tmp.
        assertEquals(setOf(cache.name, f.a.name, f.b.name), box.list()!!.toSet())
    }

    @Test fun markNowAndForget() {
        val cache = File(box, "last-su_data.ancdu")
        val other = File(box, "last-app_storage_emulated_0.ancdu.base-a").apply { writeBytes(byteArrayOf(9)) }
        val f = BaselineFiles(box, cache.name)
        save(cache, byteArrayOf(1), 1_000_000L)
        f.onSaved(cache, 1_000_000L)
        save(cache, byteArrayOf(2), 2_000_000L)
        f.onSaved(cache, 2_000_000L)
        assertTrue(f.a.exists() && f.b.exists())
        // «Отметить сейчас»: A := текущее дерево (пишет writer), B := нет; время — время дерева.
        val r = f.markFrom(5_000_000L) { path -> File(path).writeBytes(byteArrayOf(7)); 0 }
        assertEquals(0, r)
        assertArrayEquals(byteArrayOf(7), f.a.readBytes())
        assertFalse(f.b.exists())
        assertEquals(5_000_000L, f.time())
        // Ошибка записи: прежняя A цела.
        assertEquals(-28, f.markFrom(6_000_000L) { -28 })
        assertArrayEquals(byteArrayOf(7), f.a.readBytes())
        // Забыть — только свои файлы: кэш и чужой ключ на месте.
        File(f.a.path + ".tmp").writeBytes(byteArrayOf(0))
        f.forget()
        assertFalse(f.a.exists() || f.b.exists() || File(f.a.path + ".tmp").exists())
        assertTrue(cache.exists() && other.exists())
        assertEquals(0L, f.time())
        // Без A ничего не ломается.
        f.forget()
        assertEquals(BaselineRules.Step.SET_A, f.onSaved(cache, 3_000_000L))
    }

    /** Как arena_save_file до исправления: O_TRUNC по «<путь>.tmp», затем rename. */
    private fun truncatingWriter(path: String): Int {
        val tmp = File("$path.tmp")
        java.io.FileOutputStream(tmp).use { it.write(byteArrayOf(42, 42)) }
        return if (tmp.renameTo(File(path))) 0 else -5
    }

    @Test fun markNowNeverWritesThroughALinkToTheCache() {
        val cache = File(box, "last-app_x.ancdu")
        save(cache, byteArrayOf(1, 2, 3), 1_000_000L)
        val f = BaselineFiles(box, cache.name)
        // Стоячий tmp A — жёсткая ссылка на живой кэш (прерванное сохранение/старая версия).
        Files.createLink(File(f.a.path + ".tmp").toPath(), cache.toPath())
        assertEquals(0, f.markFrom(2_000_000L, ::truncatingWriter))
        assertArrayEquals(byteArrayOf(1, 2, 3), cache.readBytes())
        assertArrayEquals(byteArrayOf(42, 42), f.a.readBytes())
        // Временные файлы ссылок — свой суффикс, не «.tmp» сохранения.
        save(cache, byteArrayOf(4), 3_000_000L)
        f.forget()
        f.onSaved(cache, 3_000_000L)
        assertFalse(File(f.a.path + ".tmp").exists() || File(f.a.path + ".link").exists())
        assertEquals(setOf(cache.name, f.a.name), box.list()!!.toSet())
        File(f.a.path + ".link").writeBytes(byteArrayOf(0))
        f.forget()
        assertFalse(File(f.a.path + ".link").exists())
    }

    @Test fun dropAPromotesB() {
        val cache = File(box, "last-app_y.ancdu")
        val f = BaselineFiles(box, cache.name)
        save(cache, byteArrayOf(1), 1_000_000L); f.onSaved(cache, 1_000_000L)
        save(cache, byteArrayOf(2), 2_000_000L); f.onSaved(cache, 2_000_000L)
        // A негодна: на её место — B; кандидата больше нет.
        assertTrue(f.dropA())
        assertArrayEquals(byteArrayOf(2), f.a.readBytes())
        assertFalse(f.b.exists())
        assertEquals(2_000_000L, f.time())
        // Негодна и она: B нет — точки отсчёта нет.
        assertFalse(f.dropA())
        assertFalse(f.a.exists())
        assertTrue(cache.exists())
    }

    @Test fun cacheErrorsThatDropTheBaseline() {
        assertTrue(Baseline.dropOnCacheError(-8))    // -ENOEXEC: другая версия формата
        assertTrue(Baseline.dropOnCacheError(-22))   // -EINVAL: негоден
        assertFalse(Baseline.dropOnCacheError(-12))  // -ENOMEM
        assertFalse(Baseline.dropOnCacheError(-24))  // -EMFILE
        assertFalse(Baseline.dropOnCacheError(-2))   // -ENOENT
        assertFalse(Baseline.dropOnCacheError(0))    // исключение до кода
    }
}
