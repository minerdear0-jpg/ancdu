package dev.ancdu

import dev.ancdu.XmlTxt.Companion.EN
import dev.ancdu.XmlTxt.Companion.RU
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** Карточка быстрого просмотра: вид превью по расширению, текст файла, строки карточки. */
class PeekTest {
    private val N = Fmt.NBSP

    @Test fun kindFromExtension() {
        assertEquals(PeekKind.IMAGE, Peek.kind("jpg", "image/jpeg"))
        assertEquals(PeekKind.VIDEO, Peek.kind("mp4", "video/mp4"))
        assertEquals(PeekKind.TEXT, Peek.kind("TXT", null))
        assertEquals(PeekKind.TEXT, Peek.kind("yaml", null))
        assertEquals(PeekKind.TEXT, Peek.kind("kt", null))
        assertEquals(PeekKind.TEXT, Peek.kind("html", "text/html"))
        assertEquals(PeekKind.APK, Peek.kind("apk", "application/vnd.android.package-archive"))
        assertEquals(PeekKind.NONE, Peek.kind("bin", null))
        assertEquals(PeekKind.NONE, Peek.kind("pdf", "application/pdf"))
        // без расширения (README, .bashrc) — решает проверка содержимого
        assertEquals(PeekKind.TEXT, Peek.kind(null, null))
        for (e in listOf("txt", "log", "md", "json", "xml", "csv", "conf", "ini", "kt", "java", "c", "h", "py", "sh", "yml", "yaml"))
            assertEquals(e, PeekKind.TEXT, Peek.kind(e, null))
    }

    private fun bytes(s: String) = s.toByteArray(Charsets.UTF_8)
    private fun sniff(b: ByteArray) = Peek.sniff(b, b.size)

    @Test fun sniffPlainText() {
        assertEquals("hello\nworld\tok", sniff(bytes("hello\r\nworld\tok")))
        assertEquals("", sniff(ByteArray(0)))
        assertEquals("привет", sniff(bytes("привет")))
    }

    @Test fun sniffStripsControls() {
        assertEquals("[31mred", sniff(bytes("\u001B[31mred")))
        assertEquals("ab", sniff(bytes("a\u0007b")))
        assertEquals("photogpj.exe", sniff(bytes("photo\u202Egpj.exe")))
        assertEquals("a\nb", sniff(bytes("a\r\n\u000Bb")))
    }

    @Test fun sniffBinary() {
        // NUL в первых 512 байтах — двоичный
        assertNull(sniff(byteArrayOf(0x50, 0x4B, 0x03, 0x04, 0x00, 0x00)))
        val late = ByteArray(600) { 'x'.code.toByte() }.also { it[550] = 0 }
        assertEquals("x".repeat(599), sniff(late))
        // меньше 10% U+FFFD — текст, 10% и больше — нет
        val nine = ByteArray(100) { 'a'.code.toByte() }.also { for (i in 0 until 9) it[i * 10] = 0xFF.toByte() }
        assertTrue(sniff(nine)!!.startsWith("�"))
        val ten = ByteArray(100) { 'a'.code.toByte() }.also { for (i in 0 until 10) it[i * 10] = 0xFF.toByte() }
        assertNull(sniff(ten))
    }

    /** Чтение обрывается на 2048 байтах: недочитанный хвост многобайтного символа — не U+FFFD. */
    @Test fun sniffCutMidCharacter() {
        val b = bytes("абв")             // 6 байт
        assertEquals("аб", Peek.sniff(b, 5))
        assertEquals("а", Peek.sniff(b, 3))
        val emoji = bytes("a😀")          // 1 + 4 байта
        for (n in 2..4) assertEquals("n=$n", "a", Peek.sniff(emoji, n))
        assertEquals("a😀", Peek.sniff(emoji, 5))
    }

    @Test fun typeLines() {
        assertEquals("IMAGE · JPEG", Peek.typeLine(EN, PeekKind.IMAGE, "jpg", "image/jpeg", text = false))
        assertEquals("ИЗОБРАЖЕНИЕ · JPEG", Peek.typeLine(RU, PeekKind.IMAGE, "jpg", "image/jpeg", text = false))
        assertEquals("ВИДЕО · MP4", Peek.typeLine(RU, PeekKind.VIDEO, "mp4", "video/mp4", text = false))
        assertEquals("VIDEO · MATROSKA", Peek.typeLine(EN, PeekKind.VIDEO, "mkv", "video/x-matroska", text = false))
        assertEquals("IMAGE · SVG", Peek.typeLine(EN, PeekKind.IMAGE, "svg", "image/svg+xml", text = false))
        assertEquals("IMAGE · HEIC", Peek.typeLine(EN, PeekKind.IMAGE, "heic", null, text = false))
        assertEquals("ТЕКСТ · UTF-8", Peek.typeLine(RU, PeekKind.TEXT, "txt", null, text = false))
        assertEquals("TEXT · UTF-8", Peek.typeLine(EN, PeekKind.TEXT, null, null, text = true))
        assertEquals("FILE", Peek.typeLine(EN, PeekKind.TEXT, null, null, text = false))
        assertEquals("APK", Peek.typeLine(RU, PeekKind.APK, "apk", null, text = false))
        assertEquals("ФАЙЛ · .bin", Peek.typeLine(RU, PeekKind.NONE, "bin", null, text = false))
        assertEquals("FILE · .bin", Peek.typeLine(EN, PeekKind.NONE, "bin", null, text = false))
    }

    @Test fun durations() {
        assertEquals("0:42", Peek.duration(42_000))
        assertEquals("0:00", Peek.duration(-5))
        assertEquals("12:05", Peek.duration(725_400))
        assertEquals("1:02:03", Peek.duration(3_723_000))
    }

    @Test fun metaLine() {
        val utc = TimeZone.getTimeZone("UTC")
        val ms = 1_718_217_000_000L          // 2024-06-12 18:30 UTC
        assertEquals("12.06.2024 18:30", Peek.mtime(RU, ms, utc))
        assertEquals("Jun 12, 2024, 18:30", Peek.mtime(EN, ms, utc))
        assertEquals("—", Peek.mtime(RU, 0, utc))
        assertEquals("1,5${N}МиБ · 4080×3072 · —", Peek.meta(listOf("1,5${N}МиБ", Peek.dims(4080, 3072), "—")))
        assertEquals("10${N}B · —", Peek.meta(listOf("10${N}B", null, "—")))
    }

    @Test fun textNote() {
        val big = (18.4 * (1 shl 20)).toLong()
        assertEquals("показаны первые 2${N}КиБ из 18,4${N}МиБ", Peek.textNote(RU, big))
        assertEquals("showing the first 2${N}KiB of 18.4${N}MiB", Peek.textNote(EN, big))
        assertNull(Peek.textNote(EN, 2048))
        assertNull(Peek.textNote(EN, 10))
    }

    /** Только root читает: метаданные из дерева, содержимое root-хелпером не читается. */
    @Test fun rootOnlyPaths() {
        val me = "dev.ancdu"
        assertFalse(Peek.rootOnly("/data/data/com.x/files/a.db", viaRoot = false, pkg = me))
        assertTrue(Peek.rootOnly("/data/data/com.x/files/a.db", viaRoot = true, pkg = me))
        assertTrue(Peek.rootOnly("/data/media/0/DCIM/a.jpg", viaRoot = true, pkg = me))
        assertFalse(Peek.rootOnly("/storage/emulated/0/DCIM/a.jpg", viaRoot = true, pkg = me))
        assertFalse(Peek.rootOnly("/data/user/0/dev.ancdu/cache/x.txt", viaRoot = true, pkg = me))
        assertFalse(Peek.rootOnly("/data/data/dev.ancdu/files/x", viaRoot = true, pkg = me))
        assertTrue(Peek.rootOnly("/data/data/dev.ancdu.evil/x", viaRoot = true, pkg = me))
    }

    /** Узел дерева: каталог, ссылка, другая ФС — места под превью нет (решается до чтения). */
    @Test fun treeFlagsGate() {
        assertTrue(Peek.treeAllows(0))
        assertTrue(Peek.treeAllows(F_HLDUP))
        assertFalse(Peek.treeAllows(F_SYMLINK))
        assertFalse(Peek.treeAllows(F_DIR))
        assertFalse(Peek.treeAllows(F_OTHERFS))
        assertFalse(Peek.treeAllows(F_SYMLINK or F_HLDUP))
    }

    /**
     * Читается только обычный файл: ссылка — разрешается и цель проверяется ещё раз; FIFO,
     * устройство, сокет, ссылка на них и битая ссылка — null (FileInputStream.read на FIFO
     * висел бы вечно).
     */
    @Test fun regularFileGate() {
        val fs = mapOf(
            "/a/file.txt" to Peek.NodeType.REGULAR,
            "/a/pipe" to Peek.NodeType.OTHER,
            "/a/link-file" to Peek.NodeType.LINK,
            "/a/link-pipe" to Peek.NodeType.LINK,
            "/a/link-link" to Peek.NodeType.LINK,
            "/a/broken" to Peek.NodeType.LINK,
        )
        val real = mapOf("/a/link-file" to "/a/file.txt", "/a/link-pipe" to "/a/pipe",
            "/a/link-link" to "/a/link-file", "/a/broken" to "/a/nowhere")
        val stat: (String) -> Peek.NodeType = { fs[it] ?: Peek.NodeType.MISSING }
        val resolve: (String) -> String? = { real[it] }
        fun gate(p: String) = Peek.regularTarget(p, stat, resolve)
        assertEquals("/a/file.txt", gate("/a/file.txt"))
        assertNull(gate("/a/pipe"))
        assertEquals("/a/file.txt", gate("/a/link-file"))
        assertNull(gate("/a/link-pipe"))
        // разрешённая цель снова оказалась ссылкой (подменили между вызовами) — не доверяем
        assertNull(gate("/a/link-link"))
        assertNull(gate("/a/broken"))
        assertNull(gate("/a/missing"))
        assertNull(Peek.regularTarget("/a/link-file", stat) { null })
    }

    /** Подписи из чужих данных (APK, расширение): без C0 и переводов строк, bidi — видимыми. */
    @Test fun untrustedLabels() {
        assertEquals("EvilApp", Bidi.label("Evil\nApp\u0007"))
        assertEquals("a⟨U+202E⟩b", Bidi.label("a\u202E\r\nb"))
        assertEquals("ab", Bidi.label("a\u2028\u2029\u0085\u007Fb"))
        assertEquals("FILE · .b⟨U+202E⟩n", Peek.typeLine(EN, PeekKind.NONE, "b\u202En", null, text = false))
        assertEquals("IMAGE · J⟨U+202E⟩G", Peek.typeLine(EN, PeekKind.IMAGE, "j\u202Eg", null, text = false))
    }
}
