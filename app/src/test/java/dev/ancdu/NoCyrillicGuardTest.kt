package dev.ancdu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Весь текст интерфейса — в res/values(-ru): в строковых литералах Kotlin основного кода нет
 * кириллицы (комментарии можно). Лексер понимает //, вложенные /* */, "…", """…""", '…',
 * шаблоны ${…} с вложенными строками и \uXXXX-экранирование (обход через экранирование
 * тоже ловится).
 */
class NoCyrillicGuardTest {
    private fun cyr(c: Char) = c in '\u0400'..'\u052F'

    /** Литералы файла: (строка, текст). */
    private fun literals(src: String): List<Pair<Int, String>> {
        val out = ArrayList<Pair<Int, String>>()
        var i = 0
        var line = 1

        fun advance(n: Int = 1) { repeat(n) { if (src[i] == '\n') line++; i++ } }

        fun skipBlockComment() {
            var depth = 0
            while (i < src.length) {
                if (src.startsWith("/*", i)) { depth++; advance(2) }
                else if (src.startsWith("*/", i)) { depth--; advance(2); if (depth == 0) return }
                else advance()
            }
        }

        lateinit var code: (Boolean) -> Unit

        fun string(raw: Boolean) {
            val start = line
            val sb = StringBuilder()
            advance(if (raw) 3 else 1)
            while (i < src.length) {
                if (raw && src.startsWith("\"\"\"", i)) {
                    // """…"""" — лишние кавычки в конце принадлежат строке.
                    while (src.startsWith("\"\"\"\"", i)) { sb.append('"'); advance() }
                    advance(3); break
                }
                val c = src[i]
                if (!raw && c == '"') { advance(); break }
                if (!raw && c == '\\') {
                    if (src.startsWith("\\u", i) && i + 6 <= src.length) {
                        sb.append(src.substring(i + 2, i + 6).toInt(16).toChar()); advance(6)
                    } else { sb.append(src[i + 1]); advance(2) }
                    continue
                }
                if (c == '$' && i + 1 < src.length && src[i + 1] == '{') { advance(2); code(true); continue }
                sb.append(c); advance()
            }
            out += start to sb.toString()
        }

        code = fun(inTemplate: Boolean) {
            var depth = 0
            while (i < src.length) {
                when {
                    src.startsWith("//", i) -> while (i < src.length && src[i] != '\n') advance()
                    src.startsWith("/*", i) -> skipBlockComment()
                    src.startsWith("\"\"\"", i) -> string(raw = true)
                    src[i] == '"' -> string(raw = false)
                    src[i] == '\'' -> {
                        val start = line
                        advance()
                        val sb = StringBuilder()
                        while (i < src.length && src[i] != '\'') {
                            if (src[i] == '\\') {
                                if (src.startsWith("\\u", i)) { sb.append(src.substring(i + 2, i + 6).toInt(16).toChar()); advance(6) }
                                else { sb.append(src[i + 1]); advance(2) }
                            } else { sb.append(src[i]); advance() }
                        }
                        advance()
                        out += start to sb.toString()
                    }
                    src[i] == '{' -> { depth++; advance() }
                    src[i] == '}' -> { if (inTemplate && depth == 0) { advance(); return }; depth--; advance() }
                    else -> advance()
                }
            }
        }
        code(false)
        return out
    }

    @Test fun lexerFindsLiteralsNotComments() {
        val src = "// \u0434\u0430\n/* \u0434\u0430 /* \u0432\u043b\u043e\u0436\u0435\u043d\u043e */ */ val a = \"ok \${f(\"\u0432\u043d\u0443\u0442\u0440\u0438\")} x\"\n" +
            "val b = \"\"\"raw \${'\u044f'}\"\"\" + \"\\u0416\""
        val lits = literals(src).map { it.second }
        assertEquals(listOf("\u0432\u043d\u0443\u0442\u0440\u0438", "ok  x", "\u044f", "raw ", "\u0416"), lits)
    }

    @Test fun noCyrillicInMainStringLiterals() {
        val root = File("src/main/java")
        assertTrue("нет ${root.absolutePath}", root.isDirectory)
        val bad = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.sortedBy { it.path }.flatMap { f ->
            literals(f.readText()).filter { (_, s) -> s.any(::cyr) }.map { (ln, s) -> "${f.name}:$ln: \"$s\"" }
        }.toList()
        assertTrue("Кириллица в строковых литералах (перенести в res/values*/strings.xml):\n" +
            bad.joinToString("\n"), bad.isEmpty())
    }
}
