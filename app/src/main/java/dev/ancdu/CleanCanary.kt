package dev.ancdu

/** Окружение канарейки MediaClean: в приложении — MediaStore и файлы, в JVM-тестах — подделка. */
interface CanaryEnv {
    /** Создаёт файл-канарейку в своём новом каталоге; абсолютный путь или null. */
    fun create(): String?
    /** Ждёт (ограниченно) строку MediaStore ровно этого пути; её _id или null. */
    fun awaitRow(path: String): Long?
    /** Удаляет строку [id] пути [path] режимом «только строки» (deletedata=false); число удалённых. */
    fun deleteRowOnly(id: Long, path: String): Int
    fun fileExists(path: String): Boolean
    fun rowExists(path: String): Boolean
    /** Убирает файл и каталог канарейки по абсолютному пути. */
    fun cleanup(path: String)
}

/**
 * Решения MediaClean. Чистый Kotlin (JVM-тесты).
 * «Только строки» (deletedata=false) — скрытый параметр MediaProvider; на него можно опираться,
 * только если канарейка доказала: строка ушла, а файл остался. Иначе — scanFile (никогда не unlink).
 */
object CleanCanary {
    /** Канарейка: true — режим «только строки» работает именно так. Любой сбой — false. */
    fun rowsOnlyWorks(env: CanaryEnv): Boolean {
        val path = try { env.create() } catch (e: Exception) { null } ?: return false
        try {
            val id = env.awaitRow(path) ?: return false
            val n = env.deleteRowOnly(id, path)
            return n == 1 && env.fileExists(path) && !env.rowExists(path)
        } catch (e: Exception) {
            return false
        } finally {
            try { env.cleanup(path) } catch (e: Exception) { /* уборка — по возможности */ }
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

    /** После очистки «только строк»: прервана или сбой — досверить сканером. */
    fun needsScanAfter(out: MediaBulk.Outcome): Boolean = out.stopped || out.error != null
}
