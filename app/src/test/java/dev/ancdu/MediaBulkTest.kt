package dev.ancdu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Подделка MediaProvider: строки в памяти, выборка вычисляется по правилам SQLite
 * для столбца `_data TEXT COLLATE NOCASE`: LIKE и «=» — без учёта регистра ASCII
 * (с [caseSensitiveLike] — с учётом, для проверки самого шаблона); «_» — один символ, ESCAPE.
 * Понимает ровно те формы WHERE, что строит MediaBulk, иначе падает. Удаление повторяет
 * выборку (`_id IN (…) AND (where)`) по ТЕКУЩЕМУ _data; [beforeDelete] может «переименовать» строку.
 */
class FakeRows(paths: List<String>, private val caseSensitiveLike: Boolean = false) : MediaRows {
    val rows = LinkedHashMap<Long, String>()
    val deleteCalls = ArrayList<LongArray>()
    var pages = 0
    var failDeleteOnCall = -1
    var failPage: Exception? = null
    var onPage: () -> Unit = {}
    var beforeDelete: (FakeRows) -> Unit = {}

    init { paths.forEachIndexed { i, p -> rows[i + 1L] = p } }

    override fun page(where: String, args: Array<String>, limit: Int): List<MediaRow> {
        failPage?.let { throw it }
        pages++
        onPage()
        check(where.endsWith(" AND _id > ?")) { "неожиданный WHERE: $where" }
        val after = args.last().toLong()
        val pred = predicate(where.removeSuffix(" AND _id > ?"), args.copyOf(args.size - 1).requireNoNulls())
        return rows.entries.filter { it.key > after && pred(it.value) }.sortedBy { it.key }
            .take(limit).map { MediaRow(it.key, it.value) }
    }

    private fun predicate(where: String, args: Array<String>): (String) -> Boolean = when (where) {
        "(_data LIKE ? ESCAPE '\\' OR _data = ?)" -> { d ->
            sqliteLike(args[0], d, caseSensitiveLike) || sqlEq(d, args[1], caseSensitiveLike) }
        "_data = ?" -> { d -> sqlEq(d, args[0], caseSensitiveLike) }
        else -> throw AssertionError("неожиданный WHERE: $where")
    }

    override fun delete(ids: LongArray, where: String, args: Array<String>): Int {
        deleteCalls += ids
        if (deleteCalls.size == failDeleteOnCall) throw SecurityException("нет доступа")
        beforeDelete(this)
        val pred = predicate(where, args)
        return ids.count { id -> rows[id]?.let(pred) == true && rows.remove(id) != null }
    }

    companion object {
        /** «=» по COLLATE NOCASE: ASCII-буквы без учёта регистра (с [cs] — точное сравнение). */
        fun sqlEq(a: String, b: String, cs: Boolean = false): Boolean =
            if (cs) a == b else a.length == b.length && a.indices.all { i ->
                val x = a[i]; val y = b[i]
                x == y || x.code < 128 && y.code < 128 && x.lowercaseChar() == y.lowercaseChar()
            }

        /** SQLite LIKE с ESCAPE '\': по кодовым точкам; без [cs] ASCII-буквы сравниваются без регистра. */
        fun sqliteLike(pattern: String, s: String, cs: Boolean = false): Boolean {
            val p = pattern.codePoints().toArray()
            val t = s.codePoints().toArray()
            fun eq(a: Int, b: Int) = a == b || !cs && a < 128 && b < 128 &&
                Character.toLowerCase(a) == Character.toLowerCase(b)
            fun m(i: Int, j: Int): Boolean {
                if (i == p.size) return j == t.size
                return when (p[i]) {
                    '%'.code -> (j..t.size).any { m(i + 1, it) }
                    '_'.code -> j < t.size && m(i + 1, j + 1)
                    '\\'.code -> {
                        check(i + 1 < p.size) { "висящий escape в $pattern" }
                        j < t.size && eq(p[i + 1], t[j]) && m(i + 2, j + 1)
                    }
                    else -> j < t.size && eq(p[i], t[j]) && m(i + 1, j + 1)
                }
            }
            return m(0, 0)
        }
    }
}

class MediaBulkTest {
    private val base = "/storage/emulated/0"
    private fun noStop() = false

    @Test fun escapesLikeMetacharacters() {
        assertEquals("/a/b/%", MediaBulk.likePrefix("/a/b"))
        assertEquals("/x/50\\%\\_off\\\\y/%", MediaBulk.likePrefix("/x/50%_off\\y"))
        assertEquals("/x/it's a dir/%", MediaBulk.likePrefix("/x/it's a dir"))
        assertEquals("/x/日本/%", MediaBulk.likePrefix("/x/日本"))
        assertEquals("\\\\\\\\", MediaBulk.escape("\\\\"))
    }

    @Test fun selectionShapes() {
        val d = MediaBulk.selection("/x/foo", dir = true)
        assertEquals("(_data LIKE ? ESCAPE '\\' OR _data = ?)", d.where)
        assertArrayEquals(arrayOf("/x/foo/%", "/x/foo"), d.args)
        val f = MediaBulk.selection("/x/foo.jpg", dir = false)
        assertEquals("_data = ?", f.where)
        assertArrayEquals(arrayOf("/x/foo.jpg"), f.args)
        val pg = MediaBulk.pageSelection(d, 42)
        assertEquals("(_data LIKE ? ESCAPE '\\' OR _data = ?) AND _id > ?", pg.where)
        assertArrayEquals(arrayOf("/x/foo/%", "/x/foo", "42"), pg.args)
    }

    @Test fun likeNeverMatchesSiblingsSmoke() {
        val pat = MediaBulk.likePrefix("/x/foo")
        assertTrue(FakeRows.sqliteLike(pat, "/x/foo/a"))
        assertTrue(FakeRows.sqliteLike(pat, "/x/foo/a/b"))
        for (s in listOf("/x/foo", "/x/foo2/a", "/x/foo2", "/x/foo_/a", "/x/fo/a", "/x/foobar/a", "/x/fooo/a", "/x/foo%/a"))
            assertFalse(s, FakeRows.sqliteLike(pat, s))
        // Неэкранированные % и _ задели бы соседей — экранирование обязано это исключить.
        assertTrue(FakeRows.sqliteLike("/x/a_b/%", "/x/aXb/1"))
        assertFalse(FakeRows.sqliteLike(MediaBulk.likePrefix("/x/a_b"), "/x/aXb/1"))
        assertTrue(FakeRows.sqliteLike("/x/a%/%", "/x/abc/1"))
        assertFalse(FakeRows.sqliteLike(MediaBulk.likePrefix("/x/a%"), "/x/abc/1"))
        assertFalse(FakeRows.sqliteLike(MediaBulk.likePrefix("/x/a\\"), "/x/a/1"))
        assertTrue(FakeRows.sqliteLike(MediaBulk.likePrefix("/x/a\\"), "/x/a\\/1"))
    }

    @Test fun insideIsExactAndCaseSensitive() {
        assertTrue(MediaBulk.inside("/x/foo", "/x/foo", true))
        assertTrue(MediaBulk.inside("/x/foo/a", "/x/foo", true))
        assertFalse(MediaBulk.inside("/x/foo/", "/x/foo", true)) // пустой компонент — не наш
        assertFalse(MediaBulk.inside("/x/foo2/a", "/x/foo", true))
        assertFalse(MediaBulk.inside("/x/FOO/a", "/x/foo", true))
        assertFalse(MediaBulk.inside("/x/fo", "/x/foo", true))
        assertFalse(MediaBulk.inside(null, "/x/foo", true))
        assertTrue(MediaBulk.inside("/x/f.jpg", "/x/f.jpg", false))
        assertFalse(MediaBulk.inside("/x/f.jpg/a", "/x/f.jpg", false))
    }

    /** Имена из «трудного» алфавита: метасимволы LIKE, кавычка, пробел, регистр, юникод, U+FFFD. */
    private val alphabet = listOf("a", "A", "2", "%", "_", "\\", "'", " ", ".", "é", "É", "日", "�", "😀")
    private val names: List<String> = alphabet + alphabet.flatMap { x -> alphabet.map { y -> x + y } }

    /** Вселенная путей: каждый узел, его дети (в т.ч. с метасимволами) и вложенный уровень. */
    private val universe: List<String> = names.flatMap { n ->
        listOf("$base/$n", "$base/$n/x", "$base/$n/%", "$base/$n/_/deep", "$base/$n.jpg")
    }.plus(listOf(base, "$base/", "/storage/emulated/10/a/x", "/x")).distinct()

    private fun expectedInside(c: String, d: String, dir: Boolean) = c == d || dir && c.startsWith("$d/")

    @Test fun selectionPlusCheckMatchesExactlyTheSubtreeExhaustive() {
        for (cs in listOf(true, false)) for (n in names) for (dir in listOf(true, false)) {
            val d = "$base/$n"
            val sel = MediaBulk.selection(d, dir)
            for (c in universe) {
                val sqlHit = if (dir) FakeRows.sqliteLike(sel.args[0], c, cs) || FakeRows.sqlEq(c, sel.args[1], cs)
                    else FakeRows.sqlEq(c, sel.args[0], cs)
                val exp = expectedInside(c, d, dir)
                // Без учёта регистра LIKE не теряет своих (надмножество)…
                if (exp) assertTrue("LIKE потерял '$c' для '$d'", sqlHit)
                // …а с учётом регистра — ровно поддерево; без него лишнее отсекает inside().
                if (cs) assertEquals("LIKE cs '$c' для '$d'", exp, sqlHit)
                assertEquals("'$c' для '$d'", exp, sqlHit && MediaBulk.inside(c, d, dir))
            }
        }
    }

    @Test fun runDeletesOnlyTheSubtreeExhaustive() {
        for (n in names) for (dir in listOf(true, false)) {
            val d = "$base/$n"
            val fake = FakeRows(universe)
            val got = ArrayList<Long>()
            val out = MediaBulk.run(fake, d, dir, chunk = 3, stopped = ::noStop) { got += it }
            assertNull(out.error)
            assertFalse(out.stopped)
            val gone = universe.filter { c -> c !in fake.rows.values }.toSet()
            val exp = universe.filter { expectedInside(it, d, dir) }.toSet()
            assertEquals("'$d' dir=$dir", exp, gone)
            assertEquals(exp.size.toLong(), out.deleted)
            assertEquals(out.deleted, got.sum())
        }
    }

    @Test fun caseVariantsAreFetchedButNeverDeleted() {
        val fake = FakeRows(listOf("$base/Foo/a", "$base/foo/a", "$base/FOO", "$base/foo", "$base/foo/b"))
        val out = MediaBulk.run(fake, "$base/foo", true, stopped = ::noStop) {}
        assertEquals(3L, out.deleted)
        assertEquals(listOf("$base/Foo/a", "$base/FOO"), fake.rows.values.toList())
    }

    @Test fun chunksPagesAndDeletesSelfRowLast() {
        val d = "$base/big"
        val paths = listOf(d) + (0 until 1234).map { "$d/f$it" } + listOf("$base/big2/keep", "$base/bigx")
        val fake = FakeRows(paths)
        val got = ArrayList<Long>()
        val out = MediaBulk.run(fake, d, true, chunk = 500, stopped = ::noStop) { got += it }
        assertNull(out.error)
        assertEquals(1235L, out.deleted)
        // Пачки ≤ 500; собственная строка каталога (_id 1) — отдельной последней пачкой.
        assertEquals(listOf(499, 500, 235, 1), fake.deleteCalls.map { it.size })
        assertArrayEquals(longArrayOf(1L), fake.deleteCalls.last())
        assertEquals(listOf(499L, 500L, 235L, 1L), got)
        assertEquals(listOf("$base/big2/keep", "$base/bigx"), fake.rows.values.toList())
    }

    @Test fun singleFileUsesExactRowOnly() {
        val fake = FakeRows(listOf("$base/a.jpg", "$base/a.jpg2", "$base/a.jpg/x", "$base/A.jpg"))
        // COLLATE NOCASE: «=» выбирает и A.jpg — отсекает его только inside().
        val sel = MediaBulk.pageSelection(MediaBulk.selection("$base/a.jpg", false), Long.MIN_VALUE)
        assertEquals(listOf("$base/a.jpg", "$base/A.jpg"), fake.page(sel.where, sel.args, 10).map { it.data })
        val out = MediaBulk.run(fake, "$base/a.jpg", false, stopped = ::noStop) {}
        assertEquals(1L, out.deleted)
        assertEquals(listOf("$base/a.jpg2", "$base/a.jpg/x", "$base/A.jpg"), fake.rows.values.toList())
    }

    @Test fun stopIsCheckedBetweenChunksAndKeepsSelfRow() {
        val d = "$base/s"
        val fake = FakeRows(listOf(d) + (0 until 20).map { "$d/f$it" })
        var calls = 0
        val out = MediaBulk.run(fake, d, true, chunk = 5, stopped = { calls++ >= 2 }) {}
        assertTrue(out.stopped)
        assertNull(out.error)
        assertEquals(2, fake.pages)
        assertEquals(9L, out.deleted) // 4 детей (вместе со строкой каталога в первой странице) + 5
        assertTrue(d in fake.rows.values)
    }

    @Test fun stopBeforeStartTouchesNothing() {
        val fake = FakeRows(listOf("$base/s", "$base/s/a"))
        val out = MediaBulk.run(fake, "$base/s", true, stopped = { true }) {}
        assertTrue(out.stopped)
        assertEquals(0L, out.deleted)
        assertEquals(0, fake.pages)
        assertEquals(2, fake.rows.size)
    }

    @Test fun stopAfterLastPageStillKeepsSelfRow() {
        val fake = FakeRows(listOf("$base/s", "$base/s/a"))
        var calls = 0
        val out = MediaBulk.run(fake, "$base/s", true, stopped = { calls++ >= 1 }) {}
        assertTrue(out.stopped)
        assertEquals(1L, out.deleted)
        assertEquals(listOf("$base/s"), fake.rows.values.toList())
    }

    @Test fun errorsAreCaughtWithProgressSoFar() {
        val d = "$base/e"
        val fake = FakeRows((0 until 12).map { "$d/f$it" }).apply { failDeleteOnCall = 2 }
        val got = ArrayList<Long>()
        val out = MediaBulk.run(fake, d, true, chunk = 5, stopped = ::noStop) { got += it }
        assertTrue(out.error is SecurityException)
        assertFalse(out.stopped)
        assertEquals(5L, out.deleted)
        assertEquals(listOf(5L), got)

        for (e in listOf(SecurityException("x"), IllegalArgumentException("y"), IllegalStateException("z"))) {
            val f2 = FakeRows(listOf("$d/a")).apply { failPage = e }
            val o2 = MediaBulk.run(f2, d, true, stopped = ::noStop) {}
            assertEquals(e, o2.error)
            assertEquals(0L, o2.deleted)
        }
    }

    @Test fun rowsThatDoNotDeleteDoNotLoopForever() {
        val d = "$base/r"
        val fake = object : MediaRows {
            val inner = FakeRows((0 until 7).map { "$d/f$it" })
            var deletes = 0
            override fun page(where: String, args: Array<String>, limit: Int) = inner.page(where, args, limit)
            override fun delete(ids: LongArray, where: String, args: Array<String>): Int { deletes++; return 0 }
        }
        val out = MediaBulk.run(fake, d, true, chunk = 3, stopped = ::noStop) {}
        assertNull(out.error)
        assertEquals(0L, out.deleted)
        assertEquals(3, fake.deletes)
    }

    @Test fun nonAdvancingPageIsAnError() {
        val fake = object : MediaRows {
            override fun page(where: String, args: Array<String>, limit: Int) =
                listOf(MediaRow(Long.MIN_VALUE, "$base/z/a"))
            override fun delete(ids: LongArray, where: String, args: Array<String>) = 0
        }
        val out = MediaBulk.run(fake, "$base/z", true, chunk = 1, stopped = ::noStop) {}
        assertNotNull(out.error)
    }

    @Test fun refusesBadPaths() {
        for (p in listOf("", "/", "rel/x", "$base/x/")) {
            val fake = FakeRows(listOf("$base/x/a", "/a"))
            val out = MediaBulk.run(fake, p, true, stopped = ::noStop) {}
            assertTrue(p, out.error is IllegalArgumentException)
            assertEquals(0, fake.pages)
            assertEquals(2, fake.rows.size)
        }
    }

    @Test fun exactPathRejectsInvalidUtf8() {
        assertEquals("$base/a b", MediaBulk.exactPath("$base/a b".toByteArray()))
        assertEquals("$base/日本/😀", MediaBulk.exactPath("$base/日本/😀".toByteArray()))
        // Настоящий U+FFFD в имени (EF BF BD) — валидный UTF-8, путь точный.
        assertEquals("$base/�", MediaBulk.exactPath("$base/�".toByteArray()))
        val bad = "$base/".toByteArray() + byteArrayOf(0xff.toByte(), 'x'.code.toByte())
        assertNull(MediaBulk.exactPath(bad))
        assertNull(MediaBulk.exactPath("$base/".toByteArray() + byteArrayOf(0xc3.toByte()))) // обрыв
        assertNull(MediaBulk.exactPath("$base/".toByteArray() + byteArrayOf(0xed.toByte(), 0xa0.toByte(), 0x80.toByte()))) // суррогат
        assertNull(MediaBulk.exactPath("$base/".toByteArray() + byteArrayOf(0xc0.toByte(), 0xaf.toByte()))) // overlong «/»
        assertNull(MediaBulk.exactPath(ByteArray(0)))
        // Нестрогое декодирование (Native.str) дало бы U+FFFD — и не нашло бы строку точно.
        assertEquals("$base/�x", Native_strLike(bad))
    }

    private fun Native_strLike(b: ByteArray) = String(b, Charsets.UTF_8)

    @Test fun policyOnlyForSharedStorageWithoutRoot() {
        val ok = "$base/DCIM/x".toByteArray()
        assertEquals("$base/DCIM/x", MediaBulk.target(ok, viaRoot = false, fast = false))
        assertNull(MediaBulk.target(ok, viaRoot = true, fast = false))
        assertNull(MediaBulk.target(ok, viaRoot = false, fast = true))
        for (p in listOf(base, "$base/", "/data/media/0/x", "/sdcard/x", "/storage/ABCD-1234/x",
                "/data/user/0/dev.ancdu/cache/x", "/storage/emulated/0/x/../y", "/storage/emulated/0//x"))
            assertNull(p, MediaBulk.target(p.toByteArray(), viaRoot = false, fast = false))
        assertNull(MediaBulk.target("$base/".toByteArray() + byteArrayOf(0xff.toByte()), false, false))
        assertEquals("/storage/emulated/10/a", MediaBulk.cleanable("/storage/emulated/10/a".toByteArray()))
    }

    @Test fun rowRenamedBetweenPageAndDeleteSurvives() {
        val d = "$base/r"
        val fake = FakeRows(listOf("$d/a", "$d/b", "$d/c"))
        // FUSE-переименование между запросом и удалением: _id тот же, _data — вне узла.
        fake.beforeDelete = { f -> if (f.rows[2L] == "$d/b") f.rows[2L] = "$base/elsewhere/b" }
        val out = MediaBulk.run(fake, d, true, stopped = ::noStop) {}
        assertNull(out.error)
        assertEquals(2L, out.deleted)
        assertArrayEquals(longArrayOf(1L, 2L, 3L), fake.deleteCalls.single()) // id был в пачке…
        assertEquals(mapOf(2L to "$base/elsewhere/b"), fake.rows.toMap())  // …но строка (и файл) целы
    }

    @Test fun renamedIntoSiblingPrefixSurvivesForFileToo() {
        val f = "$base/x.jpg"
        val fake = FakeRows(listOf(f))
        fake.beforeDelete = { it.rows[1L] = "$base/x.jpg2" }
        val out = MediaBulk.run(fake, f, false, stopped = ::noStop) {}
        assertEquals(0L, out.deleted)
        assertEquals(listOf("$base/x.jpg2"), fake.rows.values.toList())
    }

    @Test fun deleteRepeatsTheSelection() {
        val fake = FakeRows(listOf("$base/s/a"))
        var seen: Pair<String, List<String>>? = null
        val rows = object : MediaRows {
            override fun page(where: String, args: Array<String>, limit: Int) = fake.page(where, args, limit)
            override fun delete(ids: LongArray, where: String, args: Array<String>): Int {
                seen = where to args.toList(); return fake.delete(ids, where, args)
            }
        }
        MediaBulk.run(rows, "$base/s", true, stopped = ::noStop) {}
        assertEquals("(_data LIKE ? ESCAPE '\\' OR _data = ?)" to listOf("$base/s/%", "$base/s"), seen)
    }

    /** Дерево-подделка: id → (дети, флаги детей). */
    private fun tree(vararg edges: Triple<Int, Int, Int>): (Int) -> Pair<IntArray, IntArray> = { nd ->
        val e = edges.filter { it.first == nd }
        e.map { it.second }.toIntArray() to e.map { it.third }.toIntArray()
    }

    @Test fun symlinkAnywhereInSubtreeIsFound() {
        val t = tree(Triple(1, 2, F_DIR), Triple(1, 3, 0), Triple(2, 4, F_DIR), Triple(4, 5, F_SYMLINK),
            Triple(9, 10, F_SYMLINK))
        assertTrue(MediaBulk.subtreeHas(1, F_DIR, F_SYMLINK, t))
        assertTrue(MediaBulk.subtreeHas(4, F_DIR, F_SYMLINK, t))
        assertFalse(MediaBulk.subtreeHas(2 + 1, 0, F_SYMLINK, t)) // файл
        val clean = tree(Triple(1, 2, F_DIR), Triple(2, 3, 0), Triple(1, 4, F_HLDUP))
        assertFalse(MediaBulk.subtreeHas(1, F_DIR, F_SYMLINK, clean))
        // сам узел — ссылка
        assertTrue(MediaBulk.subtreeHas(7, F_SYMLINK, F_SYMLINK, tree()))
        // у файла детей не спрашивают
        assertFalse(MediaBulk.subtreeHas(8, 0, F_SYMLINK) { throw AssertionError("kids() у файла") })
    }
}
