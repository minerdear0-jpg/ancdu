#include "delta.h"

#include <errno.h>
#include <stdlib.h>
#include <string.h>

/* FNV-1a по байтам имени. */
static uint32_t name_hash(const char *p, size_t n) {
  uint32_t h = 2166136261u;
  for (size_t i = 0; i < n; i++) {
    h ^= (uint8_t)p[i];
    h *= 16777619u;
  }
  return h;
}

/* Открытая адресация: ячейки — id узлов базы, ANCDU_NONE — пусто. */
typedef struct {
  uint32_t *slot;
  size_t cap;   /* ячеек в работе у текущего каталога, степень двойки */
  size_t alloc; /* выделено (растёт до самого широкого каталога) */
} table;

static int table_reset(table *t, uint32_t want) {
  size_t need = 16;
  while (need < (size_t)want * 2) need <<= 1;
  if (need > t->alloc) {
    uint32_t *s = realloc(t->slot, need * sizeof *s);
    if (!s) return -ENOMEM;
    t->slot = s;
    t->alloc = need;
  }
  /* Только need ячеек: узкий каталог не чистит всю таблицу самого широкого. */
  t->cap = need;
  memset(t->slot, 0xFF, need * sizeof *t->slot);
  return 0;
}

static int dir_bit(const arena *a, uint32_t x) { return (a->flags[x] & F_DIR) != 0; }

static void table_put(table *t, const arena *b, uint32_t x) {
  size_t m = t->cap - 1, i = name_hash(arena_name(b, x), b->name_len[x]) & m;
  while (t->slot[i] != ANCDU_NONE) {
    uint32_t y = t->slot[i];
    /* Одно имя дважды в каталоге (так не бывает): остаётся первое. */
    if (b->name_len[y] == b->name_len[x] &&
        memcmp(arena_name(b, y), arena_name(b, x), b->name_len[x]) == 0)
      return;
    i = (i + 1) & m;
  }
  t->slot[i] = x;
}

static uint32_t table_get(const table *t, const arena *b, const char *p, size_t n) {
  size_t m = t->cap - 1, i = name_hash(p, n) & m;
  for (;;) {
    uint32_t y = t->slot[i];
    if (y == ANCDU_NONE) return ANCDU_NONE;
    if (b->name_len[y] == n && memcmp(arena_name(b, y), p, n) == 0) return y;
    i = (i + 1) & m;
  }
}

/* x — живой ребёнок o в базе (порядок детей проверяется: файл базы недоверенный). */
static int base_child(const arena *b, uint64_t m, uint32_t o, uint32_t x) {
  return x < m && x != 0 && b->parent[x] == o && !(b->flags[x] & F_DELETED);
}

/* om[]: 0 — не встречен, 1 — в таблице своего каталога, 2 — сопоставлен, 3 — учтён как ушедший. */
enum { OM_NONE = 0, OM_LISTED = 1, OM_MATCHED = 2, OM_GONE = 3 };

int delta_compute(const arena *cur, const arena *base, int64_t *d_disk, int64_t *d_app,
                  uint8_t *st, delta_gone **gone, uint32_t *gone_n) {
  *gone = NULL;
  *gone_n = 0;
  uint64_t n = atomic_load(&cur->h->count), m = atomic_load(&base->h->count);
  if (n == 0) return 0;
  uint32_t bs = atomic_load(&base->h->state);
  if (m == 0 || (bs != ST_DONE && bs != ST_FULL)) return -EINVAL;
  if (strncmp(cur->h->root_path, base->h->root_path, sizeof cur->h->root_path) != 0) return -EXDEV;
  uint8_t *om = calloc(m, 1);
  if (!om) return -ENOMEM;
  table t = {NULL, 0, 0};
  delta_gone *g = NULL;
  uint32_t gn = 0, gcap = 0;
  int r = 0;
  /* Пока идёт обход, d_app[i] — id сопоставленного узла базы (-1 — нет); последний проход
   * заменяет его на Δ. Так память — только выходные массивы, om и таблица. */
  for (uint64_t i = 0; i < n; i++) d_app[i] = -1;
  if (!(cur->flags[0] & F_DELETED) && !(base->flags[0] & F_DELETED)) d_app[0] = 0;
  /* parent < ребёнка: каталог обрабатывается раньше своих детей, их пара к ним уже готова. */
  for (uint64_t i = 0; i < n; i++) {
    if (d_app[i] < 0 || !dir_bit(cur, (uint32_t)i)) continue;
    uint32_t o = (uint32_t)d_app[i];
    uint32_t os = base->child_start[o], oc = base->child_count[o];
    uint32_t cs = cur->child_start[i], cc = cur->child_count[i];
    if (oc == 0) continue; /* в базе каталог был пуст: все дети — новые */
    if ((r = table_reset(&t, oc))) goto out;
    for (uint32_t j = 0; j < oc; j++) {
      uint32_t x = base->order[os + j];
      if (!base_child(base, m, o, x) || om[x] != OM_NONE) continue;
      om[x] = OM_LISTED;
      table_put(&t, base, x);
    }
    for (uint32_t j = 0; j < cc; j++) {
      uint32_t c = cur->order[cs + j];
      if (c >= n || c == 0 || cur->parent[c] != i || cur->flags[c] & F_DELETED || d_app[c] >= 0)
        continue;
      uint32_t x = table_get(&t, base, arena_name(cur, c), cur->name_len[c]);
      /* Файл ↔ каталог под тем же именем — не тот же объект: ушло + новое. */
      if (x == ANCDU_NONE || om[x] != OM_LISTED || dir_bit(base, x) != dir_bit(cur, c)) continue;
      om[x] = OM_MATCHED;
      d_app[c] = x;
    }
    uint32_t cnt = 0;
    uint64_t gd = 0, ga = 0;
    for (uint32_t j = 0; j < oc; j++) {
      uint32_t x = base->order[os + j];
      if (!base_child(base, m, o, x) || om[x] != OM_LISTED) continue;
      om[x] = OM_GONE;
      cnt++;
      gd += base->disk[x];
      ga += base->apparent[x];
    }
    if (cnt) {
      if (gn == gcap) {
        uint32_t nc = gcap ? gcap * 2 : 64;
        delta_gone *ng = realloc(g, (size_t)nc * sizeof *ng);
        if (!ng) { r = -ENOMEM; goto out; }
        g = ng;
        gcap = nc;
      }
      g[gn++] = (delta_gone){(uint32_t)i, cnt, gd, ga};
    }
  }
  /* Δ, NEW и DEAD; parent < ребёнка — «под удалённым» наследуется одним проходом. */
  for (uint64_t i = 0; i < n; i++) {
    int dead = (cur->flags[i] & F_DELETED) || (i > 0 && st[cur->parent[i]] == DELTA_DEAD);
    int64_t o = d_app[i];
    if (dead) {
      st[i] = DELTA_DEAD;
      d_disk[i] = d_app[i] = 0;
    } else if (o >= 0) {
      st[i] = DELTA_SAME;
      d_disk[i] = (int64_t)cur->disk[i] - (int64_t)base->disk[o];
      d_app[i] = (int64_t)cur->apparent[i] - (int64_t)base->apparent[o];
    } else {
      st[i] = DELTA_NEW;
      d_disk[i] = (int64_t)cur->disk[i];
      d_app[i] = (int64_t)cur->apparent[i];
    }
  }
out:
  free(t.slot);
  free(om);
  if (r) {
    free(g);
    return r;
  }
  *gone = g;
  *gone_n = gn;
  return 0;
}

int delta_compute_file(const arena *cur, const char *base_path, int64_t *d_disk, int64_t *d_app,
                       uint8_t *st, delta_gone **gone, uint32_t *gone_n) {
  *gone = NULL;
  *gone_n = 0;
  arena b;
  int r = arena_open_file(&b, base_path);
  if (r) return r;
  r = delta_compute(cur, &b, d_disk, d_app, st, gone, gone_n);
  arena_unmap(&b);
  return r;
}
