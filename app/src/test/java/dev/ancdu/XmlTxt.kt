package dev.ancdu

import org.w3c.dom.Element
import java.io.File
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * [Txt] для JVM-тестов: те же res/values/strings.xml и values-ru/strings.xml, что у приложения
 * (рабочий каталог тестов — модуль app). Язык ru накладывается на значения по умолчанию, как у
 * Android; id ↔ имя — через R. Формы plurals — правила CLDR для целых (en: one/other;
 * ru: one/few/many).
 */
class XmlTxt(override val locale: Locale) : Txt {
    private val strings = HashMap<String, String>()
    private val plurals = HashMap<String, Map<String, String>>()

    init {
        load(File("src/main/res/values/strings.xml"))
        if (locale.language == "ru") load(File("src/main/res/values-ru/strings.xml"))
    }

    private fun load(f: File) {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val ss = doc.getElementsByTagName("string")
        for (k in 0 until ss.length) {
            val e = ss.item(k) as Element
            strings[e.getAttribute("name")] = unescape(e.textContent)
        }
        val ps = doc.getElementsByTagName("plurals")
        for (k in 0 until ps.length) {
            val e = ps.item(k) as Element
            val items = e.getElementsByTagName("item")
            plurals[e.getAttribute("name")] = (0 until items.length).associate {
                val it2 = items.item(it) as Element
                it2.getAttribute("quantity") to unescape(it2.textContent)
            }
        }
    }

    /** Правила aapt для строк без кавычек: \' \" \\ \n \t \uXXXX; пробелы схлопываются. */
    private fun unescape(raw: String): String {
        val sb = StringBuilder()
        var i = 0
        val s = raw.trim().replace(Regex("\\s+"), " ")
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (val n = s[i + 1]) {
                    'n' -> { sb.append('\n'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'u' -> { sb.append(s.substring(i + 2, i + 6).toInt(16).toChar()); i += 6 }
                    else -> { sb.append(n); i += 2 }
                }
            } else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    private fun name(cls: String, id: Int): String =
        Class.forName("dev.ancdu.R\$$cls").fields.first { it.getInt(null) == id }.name

    override fun s(id: Int, vararg args: Any): String {
        val raw = strings[name("string", id)] ?: error("no string $id")
        return if (args.isEmpty()) raw else String.format(locale, raw, *args)
    }

    override fun q(id: Int, n: Long, vararg args: Any): String {
        val forms = plurals[name("plurals", id)] ?: error("no plurals $id")
        val raw = forms[quantity(n)] ?: forms.getValue("other")
        return String.format(locale, raw, *args)
    }

    fun quantity(n: Long): String = if (locale.language == "ru") {
        val m10 = n % 10; val m100 = n % 100
        when {
            m10 == 1L && m100 != 11L -> "one"
            m10 in 2..4 && m100 !in 12..14 -> "few"
            else -> "many"
        }
    } else if (n == 1L) "one" else "other"

    companion object {
        val EN = XmlTxt(Locale.ENGLISH)
        val RU = XmlTxt(Locale.forLanguageTag("ru"))
    }
}
