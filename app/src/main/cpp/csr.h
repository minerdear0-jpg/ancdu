#pragma once
#include "arena.h"

enum { SORT_SIZE = 0, SORT_NAME = 1, SORT_ITEMS = 2 };

/* Агрегация размеров снизу вверх + CSR-раскладка детей + сортировка
 * диапазонов по disk (убыв.). Вызывать ровно один раз. 0, или -EINVAL — parent[] битый
 * (parent[0] не ANCDU_NONE, у узла i > 0 родитель >= count или он сам): арена не тронута. */
int post_process(arena *a, int threads);

/* Дети узла без F_DELETED в порядке key (SORT_SIZE по disk либо apparent).
 * cap должен быть >= child_count[node]. Возвращает число записанных. */
uint32_t csr_children(const arena *a, uint32_t node, int key, int apparent,
                      uint32_t *out, uint32_t cap);

/* Помечает узел F_DELETED, вычитает его размеры у всех предков,
 * пересортировывает затронутые диапазоны. 0 или -EINVAL. */
int csr_remove(arena *a, uint32_t node);
