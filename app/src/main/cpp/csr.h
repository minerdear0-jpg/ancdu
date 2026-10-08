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

/* Узлы с F_ERR (каталог не открылся или не дочитался при скане; частичное удаление) без F_DELETED
 * и не под удалённым предком, по возрастанию id (родитель раньше детей). Пишет первые cap в out,
 * в *total (если не NULL) — сколько их всего. Только чтение арены; O(n), плюс O(n) байт памяти,
 * только если в дереве есть удалённое. Возвращает число записанных (≤ cap). */
uint32_t csr_error_nodes(const arena *a, uint32_t cap, uint32_t *out, uint64_t *total);
