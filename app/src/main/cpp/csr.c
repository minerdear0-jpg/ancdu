#include "csr.h"

#include <errno.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>

typedef struct {
  uint64_t key;
  uint32_t idx;
} kv;

static int cmp_kv_desc(const void *x, const void *y) {
  const kv *a = x, *b = y;
  if (a->key != b->key) return a->key < b->key ? 1 : -1;
  return a->idx < b->idx ? -1 : a->idx > b->idx;
}

/* Сортирует диапазон детей node по disk убыв.; buf — переиспользуемый. */
static void sort_range(arena *a, uint32_t node, kv **buf, size_t *bufcap) {
  uint32_t c = a->child_count[node], s = a->child_start[node];
  if (c < 2) return;
  if (*bufcap < c) {
    kv *nb = realloc(*buf, (size_t)c * sizeof(kv));
    if (!nb) return; /* без памяти диапазон остаётся в порядке создания */
    *buf = nb;
    *bufcap = c;
  }
  kv *t = *buf;
  for (uint32_t j = 0; j < c; j++) {
    uint32_t k = a->order[s + j];
    t[j].key = a->disk[k];
    t[j].idx = k;
  }
  qsort(t, c, sizeof(kv), cmp_kv_desc);
  for (uint32_t j = 0; j < c; j++) a->order[s + j] = t[j].idx;
}

typedef struct {
  arena *a;
  _Atomic uint64_t next;
  uint64_t n;
} sort_job;

#define SORT_BATCH 4096

static void *sort_worker(void *p) {
  sort_job *j = p;
  kv *buf = NULL;
  size_t cap = 0;
  for (;;) {
    uint64_t lo = atomic_fetch_add(&j->next, SORT_BATCH);
    if (lo >= j->n) break;
    uint64_t hi = lo + SORT_BATCH < j->n ? lo + SORT_BATCH : j->n;
    for (uint64_t i = lo; i < hi; i++)
      if (j->a->child_count[i] > 1) sort_range(j->a, (uint32_t)i, &buf, &cap);
  }
  free(buf);
  return NULL;
}

int post_process(arena *a, int threads) {
  uint64_t n = atomic_load(&a->h->count);
  if (n == 0) return 0;
  if (n > a->cap_nodes) return -EINVAL;
  /* 0. Границы до любой записи: parent[i] индексирует массивы ниже. */
  if (a->parent[0] != ANCDU_NONE) return -EINVAL;
  for (uint64_t i = 1; i < n; i++)
    if (a->parent[i] >= n || a->parent[i] == i) return -EINVAL;
  /* 1. Агрегация: родитель < ребёнок, значит обратный проход видит
   *    каждого ребёнка уже полностью просуммированным. */
  for (uint64_t i = n - 1; i >= 1; i--) {
    uint32_t p = a->parent[i];
    a->disk[p] += a->disk[i];
    a->apparent[p] += a->apparent[i];
    a->items[p] += a->items[i];
  }
  /* 2. CSR: подсчёт → префиксные суммы → раскладка (child_count служит
   *    курсором и к концу снова равен числу детей). */
  memset(a->child_count, 0, n * sizeof(uint32_t));
  for (uint64_t i = 1; i < n; i++) a->child_count[a->parent[i]]++;
  uint32_t acc = 0;
  for (uint64_t i = 0; i < n; i++) {
    a->child_start[i] = acc;
    acc += a->child_count[i];
  }
  memset(a->child_count, 0, n * sizeof(uint32_t));
  for (uint64_t i = 1; i < n; i++) {
    uint32_t p = a->parent[i];
    a->order[a->child_start[p] + a->child_count[p]++] = (uint32_t)i;
  }
  /* 3. Параллельная сортировка диапазонов. */
  sort_job job = {.a = a, .n = n};
  atomic_init(&job.next, 0);
  if (threads < 1) threads = 1;
  if (threads > 64) threads = 64;
  pthread_t th[64];
  int started = 0;
  for (int t = 1; t < threads; t++)
    if (pthread_create(&th[started], NULL, sort_worker, &job) == 0) started++;
  sort_worker(&job);
  for (int t = 0; t < started; t++) pthread_join(th[t], NULL);
  return 0;
}

typedef struct {
  const arena *a;
  int key;
  int apparent;
} sort_ctx;

static _Thread_local sort_ctx g_ctx;

static int cmp_children(const void *x, const void *y) {
  uint32_t i = *(const uint32_t *)x, j = *(const uint32_t *)y;
  const arena *a = g_ctx.a;
  if (g_ctx.key == SORT_NAME) {
    int r = strcmp(arena_name(a, i), arena_name(a, j));
    if (r) return r;
  } else if (g_ctx.key == SORT_ITEMS) {
    if (a->items[i] != a->items[j]) return a->items[i] < a->items[j] ? 1 : -1;
  } else {
    const uint64_t *v = g_ctx.apparent ? a->apparent : a->disk;
    if (v[i] != v[j]) return v[i] < v[j] ? 1 : -1;
  }
  return i < j ? -1 : i > j;
}

uint32_t csr_children(const arena *a, uint32_t node, int key, int apparent,
                      uint32_t *out, uint32_t cap) {
  uint32_t s = a->child_start[node], c = a->child_count[node], k = 0;
  for (uint32_t j = 0; j < c && k < cap; j++) {
    uint32_t x = a->order[s + j];
    if (!(a->flags[x] & F_DELETED)) out[k++] = x;
  }
  if (key != SORT_SIZE || apparent) {
    g_ctx = (sort_ctx){a, key, apparent};
    qsort(out, k, sizeof(uint32_t), cmp_children);
  }
  return k;
}

int csr_remove(arena *a, uint32_t node) {
  uint64_t n = atomic_load(&a->h->count);
  if (node == 0 || node >= n) return -EINVAL;
  for (uint32_t x = node; x != ANCDU_NONE; x = a->parent[x])
    if (a->flags[x] & F_DELETED) return -EINVAL; /* сам узел или предок уже вычтен */
  uint64_t d = a->disk[node], ap = a->apparent[node];
  uint32_t it = a->items[node];
  a->flags[node] |= F_DELETED;
  for (uint32_t p = a->parent[node]; p != ANCDU_NONE; p = a->parent[p]) {
    a->disk[p] -= d;
    a->apparent[p] -= ap;
    a->items[p] -= it;
  }
  /* Размер каждого предка изменился — пересортировать диапазон его родителя. */
  kv *buf = NULL;
  size_t cap = 0;
  for (uint32_t x = a->parent[node]; x != 0; x = a->parent[x])
    sort_range(a, a->parent[x], &buf, &cap);
  free(buf);
  return 0;
}
