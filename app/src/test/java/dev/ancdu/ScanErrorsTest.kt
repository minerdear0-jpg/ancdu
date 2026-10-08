package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ошибки скана: причина по errno повторной попытки, закрытые Android/data|obb, предел строк листа. */
class ScanErrorsTest {
    private val S = "/storage/emulated/0"

    @Test fun errnoToReason() {
        assertEquals(ErrReason.NO_ACCESS, ScanErrors.reason(ScanErrors.EACCES))
        assertEquals(ErrReason.NO_ACCESS, ScanErrors.reason(ScanErrors.EPERM))
        assertEquals(ErrReason.GONE, ScanErrors.reason(ScanErrors.ENOENT))
        assertEquals(ErrReason.SYMLINK, ScanErrors.reason(ScanErrors.ELOOP))
        assertEquals(ErrReason.NOT_DIR, ScanErrors.reason(ScanErrors.ENOTDIR))
        assertEquals(ErrReason.OTHER, ScanErrors.reason(5))     // EIO
        assertEquals(ErrReason.OTHER, ScanErrors.reason(110))   // ETIMEDOUT
        // открылась теперь — данные неполные
        assertEquals(ErrReason.INCOMPLETE, ScanErrors.reason(0))
    }

    @Test fun reasonTexts() {
        assertEquals("no access", ScanErrors.text(EN, ErrReason.NO_ACCESS, null))
        assertEquals("no longer exists", ScanErrors.text(EN, ErrReason.GONE, null))
        assertEquals("symbolic link", ScanErrors.text(EN, ErrReason.SYMLINK, null))
        assertEquals("not a folder", ScanErrors.text(EN, ErrReason.NOT_DIR, null))
        assertEquals("read error (EIO)", ScanErrors.text(EN, ErrReason.OTHER, "EIO"))
        assertEquals("not read during the scan", ScanErrors.text(EN, ErrReason.NOT_READ, null))
        assertEquals("incomplete data — a delete was interrupted or the folder changed during the scan",
            ScanErrors.text(EN, ErrReason.INCOMPLETE, null))
        assertEquals("нет доступа", ScanErrors.text(RU, ErrReason.NO_ACCESS, null))
        assertEquals("папки уже нет", ScanErrors.text(RU, ErrReason.GONE, null))
        assertEquals("символьная ссылка", ScanErrors.text(RU, ErrReason.SYMLINK, null))
        assertEquals("это не папка", ScanErrors.text(RU, ErrReason.NOT_DIR, null))
        assertEquals("ошибка чтения (EIO)", ScanErrors.text(RU, ErrReason.OTHER, "EIO"))
        assertEquals("не прочитано при скане", ScanErrors.text(RU, ErrReason.NOT_READ, null))
        assertEquals("данные неполные — удаление прервано или папка менялась во время скана",
            ScanErrors.text(RU, ErrReason.INCOMPLETE, null))
        // имени errno нет — число
        assertEquals("read error (errno 5)", ScanErrors.text(EN, ErrReason.OTHER, null, errno = 5))
    }

    @Test fun androidPrivateFolders() {
        assertTrue(ScanErrors.androidPrivate("$S/Android/data"))
        assertTrue(ScanErrors.androidPrivate("$S/Android/obb"))
        assertTrue(ScanErrors.androidPrivate("$S/Android/data/com.x/files"))
        assertTrue(ScanErrors.androidPrivate("$S/Android/obb/com.x"))
        assertTrue(ScanErrors.androidPrivate("/storage/emulated/10/Android/data"))
        assertTrue(ScanErrors.androidPrivate("/storage/1234-ABCD/Android/data"))
        assertTrue(ScanErrors.androidPrivate("/sdcard/Android/obb"))
        // FUSE общего хранилища не различает регистр: это та же закрытая папка
        assertTrue(ScanErrors.androidPrivate("$S/android/DATA"))
        // не те сегменты
        assertFalse(ScanErrors.androidPrivate("$S/Android/data2"))
        assertFalse(ScanErrors.androidPrivate("$S/Android/obbx"))
        assertFalse(ScanErrors.androidPrivate("$S/Android/media"))
        assertFalse(ScanErrors.androidPrivate("$S/Android"))
        assertFalse(ScanErrors.androidPrivate("$S/Documents/Android/data"))
        assertFalse(ScanErrors.androidPrivate("$S/xAndroid/data"))
        assertFalse(ScanErrors.androidPrivate("/storage/emulated/Android/data"))   // нет номера пользователя
        assertFalse(ScanErrors.androidPrivate("/storage/self/Android/data"))
        assertFalse(ScanErrors.androidPrivate("/data/media/0/Android/data"))       // это путь root-скана
        assertFalse(ScanErrors.androidPrivate("/Android/data"))
        assertFalse(ScanErrors.androidPrivate(""))
    }

    @Test fun androidNoteOnlyOutsideRoot() {
        assertTrue(ScanErrors.androidNote("$S/Android/data", viaRoot = false))
        assertFalse(ScanErrors.androidNote("$S/Android/data", viaRoot = true))
        assertFalse(ScanErrors.androidNote("$S/Download", viaRoot = false))
        // под заметкой о закрытой папке — «нет доступа», даже если открылась теперь (пусто)
        assertEquals(ErrReason.NO_ACCESS, ScanErrors.shown(ErrReason.INCOMPLETE, note = true))
        assertEquals(ErrReason.NO_ACCESS, ScanErrors.shown(ErrReason.NO_ACCESS, note = true))
        assertEquals(ErrReason.GONE, ScanErrors.shown(ErrReason.GONE, note = true))
        assertEquals(ErrReason.INCOMPLETE, ScanErrors.shown(ErrReason.INCOMPLETE, note = false))
        assertEquals("Android 11+ closes this folder to other apps — its size is visible only in a root scan",
            EN.s(R.string.err_android_private))
        assertEquals("Android 11+ закрывает эту папку для других приложений — её размер виден только при скане от root",
            RU.s(R.string.err_android_private))
    }

    /** Узел-файл (F_ERR после частичного удаления): слова про файл, не про папку. */
    @Test fun fileReasonTexts() {
        assertEquals("no longer exists", ScanErrors.text(EN, ErrReason.GONE, null, dir = false))
        assertEquals("файла уже нет", ScanErrors.text(RU, ErrReason.GONE, null, dir = false))
        assertEquals("incomplete data — a delete was interrupted or the file changed",
            ScanErrors.text(EN, ErrReason.INCOMPLETE, null, dir = false))
        assertEquals("данные неполные — удаление прервано или файл менялся",
            ScanErrors.text(RU, ErrReason.INCOMPLETE, null, dir = false))
        // нейтральные причины — те же
        assertEquals("нет доступа", ScanErrors.text(RU, ErrReason.NO_ACCESS, null, dir = false))
        assertEquals("символьная ссылка", ScanErrors.text(RU, ErrReason.SYMLINK, null, dir = false))
        assertEquals("ошибка чтения (EIO)", ScanErrors.text(RU, ErrReason.OTHER, "EIO", dir = false))
        assertEquals("не прочитано при скане", ScanErrors.text(RU, ErrReason.NOT_READ, null, dir = false))
        // каталог — по-прежнему про папку
        assertEquals("папки уже нет", ScanErrors.text(RU, ErrReason.GONE, null, dir = true))
    }

    /** «СКАНИРОВАТЬ ОТ ROOT» (/data/media) покрывает только внутреннюю память, не съёмные тома. */
    @Test fun rootScanCovers() {
        assertTrue(ScanErrors.rootScanCovers("$S/Android/data"))
        assertTrue(ScanErrors.rootScanCovers("/storage/emulated/10/Android/obb/com.x"))
        assertTrue(ScanErrors.rootScanCovers("/sdcard/Android/data"))
        assertFalse(ScanErrors.rootScanCovers("/storage/1234-ABCD/Android/data"))
        assertFalse(ScanErrors.rootScanCovers("/storage/emulated/Android/data"))
        assertFalse(ScanErrors.rootScanCovers("/storage/self/primary/Android/data"))
        assertFalse(ScanErrors.rootScanCovers("/data/media/0/Android/data"))
        assertFalse(ScanErrors.rootScanCovers(""))
    }

    @Test fun capText() {
        assertEquals(200, ScanErrors.CAP)
        assertNull(ScanErrors.more(EN, total = 0, shown = 0))
        assertNull(ScanErrors.more(EN, total = 200, shown = 200))
        assertEquals("…1 more", ScanErrors.more(EN, total = 201, shown = 200))
        assertEquals("…1,300 more", ScanErrors.more(EN, total = 1500, shown = 200))
        assertEquals("…ещё 1", ScanErrors.more(RU, total = 201, shown = 200))
        assertEquals("…ещё " + Fmt.count(1300, RU.locale), ScanErrors.more(RU, total = 1500, shown = 200))
    }

    @Test fun relativePath() {
        assertEquals("Android/data", ScanErrors.relative("$S/Android/data", S, "Internal storage"))
        assertEquals("Internal storage", ScanErrors.relative(S, S, "Internal storage"))
        assertEquals("data/x", ScanErrors.relative("/data/x", "/", "/"))
        // не под корнем (не бывает, но без мусора) — полный путь
        assertEquals("/other/x", ScanErrors.relative("/other/x", S, "Internal storage"))
        assertEquals("/storage/emulated/00/a", ScanErrors.relative("/storage/emulated/00/a", S, "Internal storage"))
    }

    @Test fun linkTexts() {
        assertEquals("⚠ 2 errors ›", ScanErrors.link(EN, 2))
        assertEquals("⚠ 1 error ›", ScanErrors.link(EN, 1))
        assertEquals("⚠ 2 ошибки ›", ScanErrors.link(RU, 2))
        assertEquals("⚠ 5 ошибок ›", ScanErrors.link(RU, 5))
        assertEquals("Scan errors: 2. Open list", ScanErrors.linkDesc(EN, 2))
        assertEquals("Ошибки сканирования: 2. Открыть список", ScanErrors.linkDesc(RU, 2))
        assertEquals("Scan errors", EN.s(R.string.scan_errors))
        assertEquals("Ошибки сканирования", RU.s(R.string.scan_errors))
        assertEquals("Scan as root", EN.s(R.string.err_scan_root))
        assertEquals("Сканировать от root", RU.s(R.string.err_scan_root))
    }
}
