package dev.ancdu

/** Какому приложению принадлежит путь (по раскладке Android). Чистый Kotlin, без PackageManager. */
object Owner {
    private val PKG = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    private fun pkg(s: String): String? = s.takeIf { PKG.matches(it) }

    fun packageOf(path: String): String? {
        val s = path.split('/').filter { it.isNotEmpty() }
        if (s.size >= 3 && s[0] == "data" && s[1] == "data") return pkg(s[2])
        if (s.size >= 4 && s[0] == "data" && (s[1] == "user" || s[1] == "user_de") && s[2].all(Char::isDigit))
            return pkg(s[3])
        for (i in 0 until s.size - 2)
            if (s[i] == "Android" && (s[i + 1] == "data" || s[i + 1] == "obb")) return pkg(s[i + 2])
        return null
    }
}
