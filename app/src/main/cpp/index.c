#include "index.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

struct index_builder {
  arena *a;
  name_chunk ck;
  uint32_t *slots; /* узел + 1; 0 — пусто */
  size_t cap, used;
  char *last_dir;
  uint32_t last_node;
};

static uint64_t hname(uint32_t parent, const char *s, size_t n) {
  uint64_t h = 1469598103934665603ULL ^ parent;
  for (size_t i = 0; i < n; i++) {
    h ^= (unsigned char)s[i];
    h *= 1099511628211ULL;
  }
  return h;
}

static int grow(index_builder *b) {
  size_t nc = b->cap ? b->cap * 2 : 1024;
  uint32_t *ns = calloc(nc, sizeof *ns);
  if (!ns) return -ENOMEM;
  for (size_t i = 0; i < b->cap; i++) {
    uint32_t x = b->slots[i];
    if (!x) continue;
    x--;
    size_t j = hname(b->a->parent[x], arena_name(b->a, x), b->a->name_len[x]) & (nc - 1);
    while (ns[j]) j = (j + 1) & (nc - 1);
    ns[j] = x + 1;
  }
  free(b->slots);
  b->slots = ns;
  b->cap = nc;
  return 0;
}

/* Найти или создать каталог name под parent. ANCDU_NONE — нет места/памяти. */
static uint32_t dir_child(index_builder *b, uint32_t parent, const char *s, size_t n) {
  if ((b->used + 1) * 2 > b->cap && grow(b)) return ANCDU_NONE;
  arena *a = b->a;
  size_t m = b->cap - 1, j = hname(parent, s, n) & m;
  for (; b->slots[j]; j = (j + 1) & m) {
    uint32_t x = b->slots[j] - 1;
    if (a->parent[x] == parent && a->name_len[x] == n && memcmp(arena_name(a, x), s, n) == 0)
      return x;
  }
  uint32_t x = arena_new_node(a, &b->ck, parent, s, n, F_DIR);
  if (x == ANCDU_NONE) return x;
  b->slots[j] = x + 1;
  b->used++;
  return x;
}

index_builder *index_begin(arena *a) {
  index_builder *b = calloc(1, sizeof *b);
  if (!b) return NULL;
  b->a = a;
  if (arena_new_node(a, &b->ck, ANCDU_NONE, "", 0, F_DIR) == ANCDU_NONE) {
    free(b);
    return NULL;
  }
  return b;
}

int index_add(index_builder *b, const char *rel_dir, const char *name, uint64_t size) {
  arena *a = b->a;
  uint32_t dir;
  if (b->last_dir && strcmp(b->last_dir, rel_dir) == 0) {
    dir = b->last_node;
  } else {
    dir = 0;
    const char *p = rel_dir;
    for (;;) {
      const char *q = strchr(p, '/');
      size_t n = q ? (size_t)(q - p) : strlen(p);
      if (n && !(n == 1 && p[0] == '.') && !(n == 2 && p[0] == '.' && p[1] == '.')) {
        dir = dir_child(b, dir, p, n);
        if (dir == ANCDU_NONE) return atomic_load(&a->h->cancel) ? -ENOSPC : -ENOMEM;
      }
      if (!q) break;
      p = q + 1;
    }
    char *copy = strdup(rel_dir);
    if (!copy) return -ENOMEM;
    free(b->last_dir);
    b->last_dir = copy;
    b->last_node = dir;
  }
  uint32_t f = arena_new_node(a, &b->ck, dir, name, strlen(name), 0);
  if (f == ANCDU_NONE) return -ENOSPC;
  a->apparent[f] = size;
  a->disk[f] = (size + 4095) & ~(uint64_t)4095;
  atomic_fetch_add_explicit(&a->h->files, 1, memory_order_relaxed);
  atomic_fetch_add_explicit(&a->h->bytes, a->disk[f], memory_order_relaxed);
  return 0;
}

void index_end(index_builder *b) {
  if (!b) return;
  atomic_store(&b->a->h->finished_ns, ancdu_now_ns());
  free(b->slots);
  free(b->last_dir);
  free(b);
}
