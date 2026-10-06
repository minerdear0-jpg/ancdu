package dev.ancdu

/** Окружение канарейки MediaClean: в приложении — MediaStore и файлы, в JVM-тестах — подделка. */
interface CanaryEnv {
    /** Создаёт свой новый (mkdtemp) каталог канарейки; абсолютный путь или null. */
    fun makeDir(): String?
    /** Пишет файл-канарейку в [dir]; его абсолютный путь. */
    fun writeFile(dir: String): String
    /** Ждёт (ограниченно) строку MediaStore ровно этого пути; её _id или null. */
    fun awaitRow(path: String): Long?
    /** Удаляет строку [id] пути [path] режимом «только строки» (deletedata=false); число удалённых. */
    fun deleteRowOnly(id: Long, path: String): Int
    fun fileExists(path: String): Boolean
    fun rowExists(path: String): Boolean
    /** Убирает каталог канарейки [dir] (и её файл, если он есть) по абсолютному пути. */
    fun cleanup(dir: String)
    /** Каталог, в котором создаются канарейки (после [makeDir]), или null. */
    fun base(): String?
    /** _data строк MediaStore строго внутри [base] (для уборки строк прошлых канареек). */
    fun rowPaths(base: String): List<String>
    /** _id строки ровно этого пути или null. */
    fun rowId(path: String): Long?
    /** scanFile пути: сканер сам уберёт строку отсутствующего пути, файлов не удаляет. */
    fun scan(path: String)
}

/**
 * Решения MediaClean. Чистый Kotlin (JVM-тесты).
 * «Только строки» (deletedata=false) — скрытый параметр MediaProvider; на него можно опираться,
 * только если канарейка доказала: строка ушла, а файл остался. Иначе — scanFile (никогда не unlink).
 */
object CleanCanary {
    /** Канарейка: true — режим «только строки» работает именно так. Любой сбой — false. */
    fun rowsOnlyWorks(env: CanaryEnv): Boolean {
        val dir = try { env.makeDir() } catch (e: Exception) { null } ?: return false
        var ok = false
        // Всё после создания каталога — под finally: каталог убирается при любом исходе.
        try {
            val path = env.writeFile(dir)
            val id = env.awaitRow(path) ?: return false
            val n = env.deleteRowOnly(id, path)
            ok = n == 1 && env.fileExists(path) && !env.rowExists(path)
            return ok
        } catch (e: Exception) {
            return false
        } finally {
            try { env.cleanup(dir) } catch (e: Exception) { /* уборка — по возможности */ }
            // Каталог удалён через FUSE, а его строка остаётся — убрать и её, и строки прошлых канареек.
            try { dropDirRow(env, dir, ok) } catch (e: Exception) { }
            try { sweep(env, dir, ok) } catch (e: Exception) { }
        }
    }

    /**
     * [path] — каталог прошлой канарейки: ровно `<base>/canary-<что-то>`, без вложенных
     * компонентов. Только такие строки уборка трогает.
     */
    fun isCanaryDir(path: String, base: String): Boolean {
        val pre = "$base/canary-"
        return base.startsWith("/") && !base.endsWith("/") && path.startsWith(pre) &&
            path.length > pre.length && path.indexOf('/', pre.length) < 0
    }

    /**
     * Строка отсутствующего каталога канарейки [dir]: доказан «только строки» ([rowsOnly]) —
     * удаляется ровно она (_id, _data = dir, deletedata=false); иначе — scanFile. Каталог есть — ничего.
     */
    private fun dropDirRow(env: CanaryEnv, dir: String, rowsOnly: Boolean) {
        if (env.fileExists(dir)) return
        if (rowsOnly) env.rowId(dir)?.let { env.deleteRowOnly(it, dir) } else env.scan(dir)
    }

    /** Строки прошлых канареек под тем же base, чьих каталогов нет. */
    private fun sweep(env: CanaryEnv, current: String, rowsOnly: Boolean) {
        val base = env.base() ?: return
        for (p in env.rowPaths(base)) {
            if (p == current || !isCanaryDir(p, base)) continue
            try { dropDirRow(env, p, rowsOnly) } catch (e: Exception) { }
        }
    }

    enum class Mode { ROWS_ONLY, SCAN }

    /**
     * Как чистить строки удалённого пути. Без доказанного «только строк» — [Mode.SCAN].
     * Путь снова существует — тоже SCAN (оптимизация: пусть сканер сверит; безопасность от
     * этой проверки не зависит — «только строки» файлов не удаляет).
     */
    fun mode(rowsOnlyOk: Boolean, pathExists: Boolean): Mode =
        if (rowsOnlyOk && !pathExists) Mode.ROWS_ONLY else Mode.SCAN

    /**
     * После очистки «только строк» досверить сканером, если строки могли остаться: прервана,
     * сбой (в т.ч. страница без продвижения), ничего не удалено или удалено меньше найденного.
     */
    fun needsScanAfter(out: MediaBulk.Outcome): Boolean =
        out.stopped || out.error != null || out.deleted <= 0 || out.deleted < out.matched
}
