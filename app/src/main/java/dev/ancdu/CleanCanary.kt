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
        // Всё после создания каталога — под finally: каталог убирается при любом исходе.
        try {
            val path = env.writeFile(dir)
            val id = env.awaitRow(path) ?: return false
            val n = env.deleteRowOnly(id, path)
            return n == 1 && env.fileExists(path) && !env.rowExists(path)
        } catch (e: Exception) {
            return false
        } finally {
            try { env.cleanup(dir) } catch (e: Exception) { /* уборка — по возможности */ }
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
