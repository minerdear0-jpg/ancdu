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
  /* parent[i] < i — тот же инвариант, что у arena_validate: на нём держится однопроходная
   * агрегация ниже (каждый ребёнок просуммирован раньше родителя). */
  for (uint64_t i = 1; i < n; i++)
    if (a->parent[i] >= i) return -EINVAL;
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

/* Порядок кучи: x «меньше» y — x уходит из топа раньше (меньший disk; при равном — больший id). */
static int top_less(const arena *a, uint32_t x, uint32_t y) {
  if (a->disk[x] != a->disk[y]) return a->disk[x] < a->disk[y];
  return x > y;
}

static void sift_down(const arena *a, uint32_t *h, uint32_t n, uint32_t i) {
  for (;;) {
    uint32_t l = 2 * i + 1, r = l + 1, m = i;
    if (l < n && top_less(a, h[l], h[m])) m = l;
    if (r < n && top_less(a, h[r], h[m])) m = r;
    if (m == i) return;
    uint32_t t = h[i];
    h[i] = h[m];
    h[m] = t;
    i = m;
  }
}

static void sift_up(const arena *a, uint32_t *h, uint32_t i) {
  while (i > 0) {
    uint32_t p = (i - 1) / 2;
    if (!top_less(a, h[i], h[p])) return;
    uint32_t t = h[i];
    h[i] = h[p];
    h[p] = t;
    i = p;
  }
}

/* Min-куча h[0..n) → по убыванию (disk убыв., id возр.): наименьший уходит в конец. */
static void heap_to_desc(const arena *a, uint32_t *h, uint32_t n) {
  for (uint32_t end = n; end > 1; end--) {
    uint32_t t = h[0];
    h[0] = h[end - 1];
    h[end - 1] = t;
    sift_down(a, h, end - 1, 0);
  }
}

/* Узел или предок удалён (csr_remove помечает только вершину удалённого). */
static int under_deleted(const arena *a, uint32_t x) {
  for (; x != ANCDU_NONE; x = a->parent[x])
    if (a->flags[x] & F_DELETED) return 1;
  return 0;
}

/* Есть ли в дереве удалённое (*any) и, если есть, «мёртв ли узел» одним проходом (parent < ребёнка).
 * NULL при *any — нет памяти: вызывающий проверяет предков (under_deleted) только у кандидатов. */
static uint8_t *dead_map(const arena *a, uint64_t n, int *any) {
  *any = 0;
  for (uint64_t i = 0; i < n && !*any; i++) *any = (a->flags[i] & F_DELETED) != 0;
  if (!*any) return NULL;
  uint8_t *dead = malloc(n);
  if (dead) {
    dead[0] = (a->flags[0] & F_DELETED) != 0;
    for (uint64_t i = 1; i < n; i++)
      dead[i] = (a->flags[i] & F_DELETED) || dead[a->parent[i]];
  }
  return dead;
}

uint32_t csr_top_files(const arena *a, uint32_t k, uint32_t *out) {
  uint64_t n = atomic_load(&a->h->count);
  if (k == 0 || n < 2) return 0;
  int any = 0;
  uint8_t *dead = dead_map(a, n, &any);
  /* Жёсткие ссылки: размер несёт только первая увиденная ссылка, остальные — F_HLDUP с disk 0.
   * Удалили из дерева именно первую (csr_remove) — файл ещё на диске через другие ссылки, но в
   * топе его нет до пересканирования: дерево в этом месте и так устарело (размер удалённого вычтен
   * у предков), поэтому отдельной поправки нет. */
  uint32_t m = 0; /* out[0..m) — min-куча */
  for (uint64_t i = 1; i < n; i++) {
    uint32_t x = (uint32_t)i;
    if (a->flags[x] & (F_DIR | F_HLDUP | F_DELETED) || a->disk[x] == 0) continue;
    if (m == k && !top_less(a, out[0], x)) continue; /* не крупнее наименьшего в топе */
    if (dead ? dead[x] : any && under_deleted(a, x)) continue;
    if (m < k) {
      out[m] = x;
      sift_up(a, out, m++);
    } else {
      out[0] = x;
      sift_down(a, out, m, 0);
    }
  }
  free(dead);
  heap_to_desc(a, out, m);
  return m;
}

static uint64_t sat_add(uint64_t x, uint64_t y) { return x > UINT64_MAX - y ? UINT64_MAX : x + y; }

uint32_t csr_giants(const arena *a, uint64_t min_bytes, uint32_t max_count, uint32_t *out,
                    uint64_t *total, uint64_t sums[2]) {
  uint64_t n = atomic_load(&a->h->count);
  if (min_bytes == 0) min_bytes = 1; /* disk 0 — никогда не «гигант» */
  int any = 0;
  uint8_t *dead = n > 1 ? dead_map(a, n, &any) : NULL;
  uint64_t t = 0, sd = 0, sa = 0;
  uint32_t m = 0; /* out[0..m) — min-куча, не больше max_count */
  for (uint64_t i = 1; i < n; i++) {
    uint32_t x = (uint32_t)i;
    if (a->disk[x] < min_bytes || a->flags[x] & (F_DIR | F_HLDUP | F_DELETED)) continue;
    if (dead ? dead[x] : any && under_deleted(a, x)) continue;
    t++;
    sd = sat_add(sd, a->disk[x]);
    sa = sat_add(sa, a->apparent[x]);
    if (m < max_count) {
      out[m] = x;
      sift_up(a, out, m++);
    } else if (m && top_less(a, out[0], x)) {
      out[0] = x;
      sift_down(a, out, m, 0);
    }
  }
  free(dead);
  heap_to_desc(a, out, m);
  if (total) *total = t;
  if (sums) { sums[0] = sd; sums[1] = sa; }
  return m;
}

uint32_t csr_error_nodes(const arena *a, uint32_t cap, uint32_t *out, uint64_t *total) {
  uint64_t n = atomic_load(&a->h->count);
  int any = 0;
  uint8_t *dead = dead_map(a, n, &any);
  uint64_t t = 0;
  uint32_t w = 0;
  for (uint64_t i = 0; i < n; i++) {
    uint32_t x = (uint32_t)i;
    if (!(a->flags[x] & F_ERR) || a->flags[x] & F_DELETED) continue;
    if (dead ? dead[x] : any && under_deleted(a, x)) continue;
    if (w < cap) out[w++] = x;
    t++;
  }
  free(dead);
  if (total) *total = t;
  return w;
}

uint32_t csr_child_named(const arena *a, uint32_t node, const char *name, size_t len, int dir_only) {
  uint64_t n = atomic_load(&a->h->count);
  if ((uint64_t)node >= n || len == 0 || len > ANCDU_MAX_NAME) return ANCDU_NONE;
  uint32_t s = a->child_start[node], c = a->child_count[node];
  for (uint32_t j = 0; j < c; j++) {
    uint32_t x = a->order[s + j];
    uint8_t f = a->flags[x];
    if (f & F_DELETED || (dir_only && !(f & F_DIR))) continue;
    if (a->name_len[x] == len && memcmp(arena_name(a, x), name, len) == 0) return x;
  }
  return ANCDU_NONE;
}

uint32_t csr_resolve(const arena *a, const char *chain, size_t len, int dir_only, uint32_t *depth) {
  uint32_t cur = 0, d = 0;
  if (len > 0 && atomic_load(&a->h->count) > 0) {
    size_t start = 0;
    for (;;) {
      const char *z = memchr(chain + start, 0, len - start);
      size_t end = z ? (size_t)(z - chain) : len;
      int last = end == len;
      uint32_t x = csr_child_named(a, cur, chain + start, end - start, dir_only || !last);
      if (x == ANCDU_NONE) break;
      cur = x;
      d++;
      if (last) break;
      start = end + 1;
    }
  }
  if (depth) *depth = d;
  return cur;
}
