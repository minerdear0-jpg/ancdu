#pragma once
#include "arena.h"

enum { SORT_SIZE = 0, SORT_NAME = 1, SORT_ITEMS = 2 };

/* Агрегация размеров снизу вверх + CSR-раскладка детей + сортировка
 * диапазонов по disk (убыв.). Вызывать ровно один раз. 0, или -EINVAL — parent[] битый
 * (parent[0] не ANCDU_NONE, у узла i > 0 родитель >= i): арена не тронута. */
int post_process(arena *a, int threads);

/* Дети узла без F_DELETED в порядке key (SORT_SIZE по disk либо apparent).
 * cap должен быть >= child_count[node]. Возвращает число записанных. */
uint32_t csr_children(const arena *a, uint32_t node, int key, int apparent,
                      uint32_t *out, uint32_t cap);

/* Помечает узел F_DELETED, вычитает его размеры у всех предков,
 * пересортировывает затронутые диапазоны. 0 или -EINVAL. */
int csr_remove(arena *a, uint32_t node);

/* До k крупнейших по disk ФАЙЛОВ всего дерева (не каталогов) в out, по убыванию disk; при
 * равенстве — меньший id первым. Пропускаются каталоги, F_HLDUP (вторая и дальше ссылки на один
 * inode — у первой весь размер), F_DELETED и всё под удалённым предком, а также disk 0.
 * Min-куча на k: O(n log k), плюс O(n) байт памяти, только если в дереве есть удалённое.
 * Только чтение арены. Возвращает число записанных (≤ k). */
uint32_t csr_top_files(const arena *a, uint32_t k, uint32_t *out);

/* «Гиганты»: ФАЙЛЫ с disk ≥ min_bytes (0 считается как 1: пустые не попадают) по убыванию disk, при
 * равенстве — меньший id первым; в out не больше max_count. В *total (если не NULL) — сколько таких
 * всего, в sums[0] / sums[1] (если не NULL) — их суммы disk и apparent (по ВСЕМ совпавшим, не только
 * записанным; с насыщением). Порог и cap — по disk. Пропуски — как у csr_top_files: каталоги, F_HLDUP,
 * F_DELETED и всё под удалённым предком.
 * O(n) фильтр плюс min-куча на max_count (O(m log max_count) по совпавшим); память — out плюс O(n)
 * байт, только если в дереве есть удалённое. Только чтение арены. Возвращает число записанных. */
uint32_t csr_giants(const arena *a, uint64_t min_bytes, uint32_t max_count, uint32_t *out,
                    uint64_t *total, uint64_t sums[2]);

/* Узлы с F_ERR (каталог не открылся или не дочитался при скане; частичное удаление) без F_DELETED
 * и не под удалённым предком, по возрастанию id (родитель раньше детей). Пишет первые cap в out,
 * в *total (если не NULL) — сколько их всего. Только чтение арены; O(n), плюс O(n) байт памяти,
 * только если в дереве есть удалённое. Возвращает число записанных (≤ cap). */
uint32_t csr_error_nodes(const arena *a, uint32_t cap, uint32_t *out, uint64_t *total);

/* Ребёнок node без F_DELETED с именем ровно name[0..len) (байты, без \0); dir_only — только
 * каталог. ANCDU_NONE — нет (и при len 0, len > ANCDU_MAX_NAME, node вне дерева). O(детей node).
 * Только чтение арены. */
uint32_t csr_child_named(const arena *a, uint32_t node, const char *name, size_t len, int dir_only);

/* Путь по именам от корня: chain[0..len) — имена через \0 (без завершающего; len 0 — корень), как
 * Focus.encode. Каждый шаг — csr_child_named; промежуточные — каталоги, последний — каталог, если
 * dir_only. Пустое имя (два \0 подряд, \0 в начале или в конце) дальше не ищется. Возвращает самый
 * глубокий найденный узел (0 — корень); в *depth (если не NULL) — сколько имён найдено: равно
 * числу имён — найден весь путь. Только чтение арены. */
uint32_t csr_resolve(const arena *a, const char *chain, size_t len, int dir_only, uint32_t *depth);
