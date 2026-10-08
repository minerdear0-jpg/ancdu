/* delta_compute: Δ нового дерева против базы (кэш-файл прошлого скана). Только чтение деревьев.
 * Фикстура — в mk_tmp(); единственное удаление (каталог b/, «ушло») — через sandbox_guard на
 * абсолютном пути внутри песочницы. Песочницу убирает run_all.sh. */
#include "arena.h"
#include "csr.h"
#include "delta.h"
#include "session.h"
#include "test.h"

#include <errno.h>

#define CHECK_EQ_I(a, b)                                                                  \
  do {                                                                                    \
    long long _a = (long long)(a), _b = (long long)(b);                                   \
    if (_a != _b) {                                                                       \
      fprintf(stderr, "%s:%d: %s == %lld, expected %lld\n", __FILE__, __LINE__, #a, _a, _b); \
      t_fail++;                                                                           \
    }                                                                                     \
  } while (0)

static uint32_t N(arena *a, name_chunk *ck, uint32_t p, const char *nm, uint8_t fl, uint64_t dk,
                  uint64_t ap) {
  uint32_t i = arena_new_node(a, ck, p, nm, strlen(nm), fl);
  a->disk[i] = dk;
  a->apparent[i] = ap;
  return i;
}

/* Узел по пути «a/b/c» от корня (через CSR); ANCDU_NONE — нет (удалённые не считаются). */
static uint32_t at(const arena *a, const char *path) {
  uint32_t cur = 0;
  const char *p = path;
  while (*p) {
    const char *e = strchr(p, '/');
    size_t k = e ? (size_t)(e - p) : strlen(p);
    uint32_t s = a->child_start[cur], c = a->child_count[cur], hit = ANCDU_NONE;
    for (uint32_t j = 0; j < c; j++) {
      uint32_t x = a->order[s + j];
      if (a->flags[x] & F_DELETED) continue;
      if (a->name_len[x] == k && memcmp(arena_name(a, x), p, k) == 0) { hit = x; break; }
    }
    if (hit == ANCDU_NONE) return ANCDU_NONE;
    cur = hit;
    p = e ? e + 1 : p + k;
  }
  return cur;
}

static const delta_gone *gone_of(const delta_gone *g, uint32_t n, uint32_t node) {
  for (uint32_t i = 0; i < n; i++)
    if (g[i].node == node) return &g[i];
  return NULL;
}

typedef struct {
  int64_t *disk, *app;
  uint8_t *st;
  delta_gone *gone;
  uint32_t gone_n;
} res;

static int run(const arena *cur, const arena *base, res *r) {
  uint64_t n = atomic_load(&cur->h->count);
  r->disk = calloc(n ? n : 1, 8);
  r->app = calloc(n ? n : 1, 8);
  r->st = calloc(n ? n : 1, 1);
  return delta_compute(cur, base, r->disk, r->app, r->st, &r->gone, &r->gone_n);
}

static void done(res *r) {
  free(r->disk);
  free(r->app);
  free(r->st);
  free(r->gone);
  memset(r, 0, sizeof *r);
}

/*
 * База:                         Новое:
 * root                          root
 *  ├ a/ ├ a1 100 ├ a2 1000       ├ a/ ├ a1 100 ├ a2 5000 └ a3 700 (NEW)
 *  ├ b/ └ b1 3000                ├ c 50           (сжался)
 *  ├ c 400                       ├ e/ └ d1 10 (NEW — переименованный d/)
 *  ├ d/ └ d1 10                  ├ x/ └ x1 20 (NEW: был файлом x)
 *  ├ x 60                        └ keep 9
 *  └ keep 9
 * Ушло у корня: b/ (3000+dir), d/ (10+dir), x (файл 60) — 3 объекта.
 * Каталоги — 8 байт сами по себе (disk = apparent для простоты чисел).
 */
static void build_base(arena *b) {
  CHECK(arena_alloc_anon(b, 64, 4096, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(b, &ck, ANCDU_NONE, "", F_DIR, 8, 8);
  uint32_t a = N(b, &ck, 0, "a", F_DIR, 8, 8);
  uint32_t bb = N(b, &ck, 0, "b", F_DIR, 8, 8);
  N(b, &ck, 0, "c", 0, 400, 400);
  uint32_t d = N(b, &ck, 0, "d", F_DIR, 8, 8);
  N(b, &ck, 0, "x", 0, 60, 60);
  N(b, &ck, 0, "keep", 0, 9, 9);
  N(b, &ck, a, "a1", 0, 100, 100);
  N(b, &ck, a, "a2", 0, 1000, 1000);
  N(b, &ck, bb, "b1", 0, 3000, 3000);
  N(b, &ck, d, "d1", 0, 10, 10);
  atomic_store(&b->h->state, ST_DONE);
  CHECK(post_process(b, 1) == 0);
}

static void build_cur(arena *c) {
  CHECK(arena_alloc_anon(c, 64, 4096, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(c, &ck, ANCDU_NONE, "", F_DIR, 8, 8);
  uint32_t a = N(c, &ck, 0, "a", F_DIR, 8, 8);
  N(c, &ck, 0, "c", 0, 50, 50);
  uint32_t e = N(c, &ck, 0, "e", F_DIR, 8, 8);
  uint32_t x = N(c, &ck, 0, "x", F_DIR, 8, 8);
  N(c, &ck, 0, "keep", 0, 9, 9);
  N(c, &ck, a, "a2", 0, 5000, 4900); /* disk и apparent различаются — считаются отдельно */
  N(c, &ck, a, "a1", 0, 100, 100);
  N(c, &ck, a, "a3", 0, 700, 700);
  N(c, &ck, e, "d1", 0, 10, 10);
  N(c, &ck, x, "x1", 0, 20, 20);
  atomic_store(&c->h->state, ST_DONE);
  CHECK(post_process(c, 1) == 0);
}

static void test_synthetic(void) {
  arena b, c;
  build_base(&b);
  build_cur(&c);
  res r;
  CHECK(run(&c, &b, &r) == 0);
  /* корень: новое 8+8+5000+100+700+50+8+10+8+20+9 = 5921; база 8+8+100+1000+8+3000+400+8+10+60+9 = 4611 */
  CHECK_EQ_U(c.disk[0], 5921);
  CHECK_EQ_U(b.disk[0], 4611);
  CHECK_EQ_I(r.disk[0], 5921 - 4611);
  CHECK_EQ_I(r.app[0], (int64_t)c.apparent[0] - (int64_t)b.apparent[0]);
  CHECK_EQ_U(r.st[0], 0);
  uint32_t a = at(&c, "a"), a1 = at(&c, "a/a1"), a2 = at(&c, "a/a2"), a3 = at(&c, "a/a3");
  CHECK_EQ_I(r.disk[a], (8 + 5000 + 100 + 700) - (8 + 100 + 1000));
  CHECK_EQ_I(r.disk[a1], 0);
  CHECK_EQ_U(r.st[a1], 0);
  CHECK_EQ_I(r.disk[a2], 4000);
  CHECK_EQ_I(r.app[a2], 3900);
  CHECK_EQ_U(r.st[a2], 0);
  CHECK_EQ_I(r.disk[a3], 700); /* NEW: Δ = свой размер */
  CHECK_EQ_U(r.st[a3], DELTA_NEW);
  CHECK_EQ_I(r.disk[at(&c, "c")], -350);
  CHECK_EQ_U(r.st[at(&c, "c")], 0);
  CHECK_EQ_I(r.disk[at(&c, "keep")], 0);
  /* переименование = ушло + новое */
  CHECK_EQ_U(r.st[at(&c, "e")], DELTA_NEW);
  CHECK_EQ_U(r.st[at(&c, "e/d1")], DELTA_NEW);
  CHECK_EQ_I(r.disk[at(&c, "e")], 18);
  /* файл стал каталогом = ушло + новое (поддерево целиком новое) */
  CHECK_EQ_U(r.st[at(&c, "x")], DELTA_NEW);
  CHECK_EQ_U(r.st[at(&c, "x/x1")], DELTA_NEW);
  CHECK_EQ_I(r.disk[at(&c, "x")], 28);
  /* ушло: только у корня, 3 объекта, их поддеревья базы */
  CHECK_EQ_U(r.gone_n, 1);
  const delta_gone *g = gone_of(r.gone, r.gone_n, 0);
  CHECK(g != NULL);
  if (g) {
    CHECK_EQ_U(g->count, 3);
    CHECK_EQ_U(g->disk, (8 + 3000) + (8 + 10) + 60);
    CHECK_EQ_U(g->apparent, (8 + 3000) + (8 + 10) + 60);
  }
  CHECK(gone_of(r.gone, r.gone_n, a) == NULL);
  /* сумма Δ детей + Δ самого каталога + ушедшее = Δ каталога (размеры — итоги поддеревьев) */
  int64_t sum = 0;
  uint32_t s = c.child_start[0], cc = c.child_count[0];
  for (uint32_t j = 0; j < cc; j++) sum += r.disk[c.order[s + j]];
  CHECK_EQ_I(sum - (int64_t)g->disk, r.disk[0]); /* свой размер корня не менялся (8 → 8) */
  done(&r);

  /* удалённое в новом дереве: само и всё под ним — DEAD (Δ не показывается), в родителе — ушло */
  CHECK(csr_remove(&c, a) == 0);
  CHECK(run(&c, &b, &r) == 0);
  CHECK_EQ_U(r.st[a], DELTA_DEAD);
  CHECK_EQ_U(r.st[a2], DELTA_DEAD);
  CHECK_EQ_U(r.st[a3], DELTA_DEAD);
  CHECK_EQ_I(r.disk[a3], 0);
  g = gone_of(r.gone, r.gone_n, 0);
  CHECK(g != NULL);
  if (g) {
    CHECK_EQ_U(g->count, 4);
    CHECK_EQ_U(g->disk, (8 + 3000) + (8 + 10) + 60 + (8 + 100 + 1000));
  }
  CHECK_EQ_I(r.disk[0], (int64_t)c.disk[0] - (int64_t)b.disk[0]);
  done(&r);

  /* база против самой себя: всё ±0, ничего нового, ничего не ушло */
  CHECK(run(&b, &b, &r) == 0);
  uint64_t bn = atomic_load(&b.h->count);
  int nz = 0;
  for (uint64_t i = 0; i < bn; i++) nz += r.disk[i] != 0 || r.app[i] != 0 || r.st[i] != 0;
  CHECK_EQ_U(nz, 0);
  CHECK_EQ_U(r.gone_n, 0);
  done(&r);
  arena_unmap(&b);
  arena_unmap(&c);
}

/* Каталог шире начальной хеш-таблицы, имена с общим префиксом: каждое находится. */
static void test_wide_dir(void) {
  enum { W = 5000 };
  arena b, c;
  CHECK(arena_alloc_anon(&b, W + 8, 1u << 20, "/r", SRC_SCAN) == 0);
  CHECK(arena_alloc_anon(&c, W + 8, 1u << 20, "/r", SRC_SCAN) == 0);
  name_chunk kb = {0, 0}, kc = {0, 0};
  N(&b, &kb, ANCDU_NONE, "", F_DIR, 0, 0);
  N(&c, &kc, ANCDU_NONE, "", F_DIR, 0, 0);
  char nm[32];
  for (int i = 0; i < W; i++) {
    snprintf(nm, sizeof nm, "file-%05d", i);
    N(&b, &kb, 0, nm, 0, 10, 10);
    /* новое: те же имена в обратном порядке, чётные выросли, каждое сотое — ушло */
    snprintf(nm, sizeof nm, "file-%05d", W - 1 - i);
    if ((W - 1 - i) % 100 == 0) continue;
    N(&c, &kc, 0, nm, 0, (W - 1 - i) % 2 ? 10 : 30, 10);
  }
  atomic_store(&b.h->state, ST_DONE);
  atomic_store(&c.h->state, ST_DONE);
  CHECK(post_process(&b, 1) == 0);
  CHECK(post_process(&c, 1) == 0);
  res r;
  CHECK(run(&c, &b, &r) == 0);
  uint64_t n = atomic_load(&c.h->count);
  int news = 0, grown = 0;
  for (uint64_t i = 1; i < n; i++) {
    news += r.st[i] == DELTA_NEW;
    grown += r.disk[i] == 20;
  }
  CHECK_EQ_U(news, 0);
  CHECK_EQ_U(grown, W / 2 - W / 100);
  CHECK_EQ_U(r.gone_n, 1);
  if (r.gone_n) {
    CHECK_EQ_U(r.gone[0].count, W / 100);
    CHECK_EQ_U(r.gone[0].disk, 10 * (W / 100));
  }
  done(&r);
  arena_unmap(&b);
  arena_unmap(&c);
}

/* Настоящий скан, сохранение базы, правки (добавить, удалить, вырастить, сжать, переименовать),
 * второй скан и Δ против файла базы. */
static void test_scan_file(void) {
  char T[4096], C[4096];
  snprintf(T, sizeof T, "%s", mk_tmp());
  snprintf(C, sizeof C, "%s", mk_tmp());
  char R[4096];
  snprintf(R, sizeof R, "%s", pj(T, "tree"));
  mk_dir(R);
  mk_dir(pj(R, "a"));
  write_file(pj(R, "a/a1"), 1000);
  write_file(pj(R, "a/a2"), 2000);
  mk_dir(pj(R, "b"));
  write_file(pj(R, "b/b1"), 30000);
  write_file(pj(R, "b/b2"), 7);
  write_file(pj(R, "c"), 90000);
  mk_dir(pj(R, "d"));
  write_file(pj(R, "d/d1"), 500);
  write_file(pj(R, "keep"), 123);
  int err = 1;
  session *s = sess_scan_start(R, 1, 2, &err);
  CHECK(s && err == 0);
  CHECK_EQ_U(sess_wait(s), ST_DONE);
  char base[4096];
  snprintf(base, sizeof base, "%s", pj(C, "last.ancdu.base-a"));
  CHECK(sess_save_cache(s, base) == 0);
  sess_free(s);

  /* правки */
  write_file(pj(R, "a/a2"), 200000);
  write_file(pj(R, "a/a3"), 4000);
  write_file(pj(R, "c"), 100);
  CHECK(rename(pj(R, "d"), pj(R, "e")) == 0);
  {
    const char *victim = pj(R, "b");
    char v[4096];
    snprintf(v, sizeof v, "%s", victim);
    sandbox_guard(T, v);
    rm_dir_tree_for_tests(v);
  }
  mk_dir(pj(R, "f"));
  write_file(pj(R, "f/f1"), 64);

  session *s2 = sess_scan_start(R, 1, 2, &err);
  CHECK(s2 && err == 0);
  CHECK_EQ_U(sess_wait(s2), ST_DONE);
  arena *cur = sess_arena(s2);
  arena old;
  CHECK(arena_open_file(&old, base) == 0);
  uint64_t n = atomic_load(&cur->h->count);
  res r;
  r.disk = calloc(n, 8);
  r.app = calloc(n, 8);
  r.st = calloc(n, 1);
  CHECK(sess_delta(s2, base, r.disk, r.app, r.st, &r.gone, &r.gone_n) == 0);
  uint32_t a2 = at(cur, "a/a2"), oa2 = at(&old, "a/a2");
  CHECK_EQ_I(r.app[a2], 200000 - 2000);
  CHECK_EQ_I(r.disk[a2], (int64_t)cur->disk[a2] - (int64_t)old.disk[oa2]);
  CHECK(r.disk[a2] > 0);
  CHECK_EQ_U(r.st[at(cur, "a/a3")], DELTA_NEW);
  CHECK_EQ_I(r.app[at(cur, "a/a3")], 4000);
  CHECK_EQ_I(r.app[at(cur, "c")], 100 - 90000);
  CHECK(r.disk[at(cur, "c")] < 0);
  CHECK_EQ_I(r.app[at(cur, "keep")], 0);
  CHECK_EQ_I(r.disk[at(cur, "keep")], 0);
  CHECK_EQ_U(r.st[at(cur, "a/a1")], 0);
  CHECK_EQ_U(r.st[at(cur, "e")], DELTA_NEW);
  CHECK_EQ_U(r.st[at(cur, "e/d1")], DELTA_NEW);
  CHECK_EQ_U(r.st[at(cur, "f")], DELTA_NEW);
  CHECK_EQ_U(r.st[at(cur, "f/f1")], DELTA_NEW);
  CHECK_EQ_I(r.disk[0], (int64_t)cur->disk[0] - (int64_t)old.disk[0]);
  CHECK_EQ_I(r.app[0], (int64_t)cur->apparent[0] - (int64_t)old.apparent[0]);
  const delta_gone *g = gone_of(r.gone, r.gone_n, 0);
  CHECK(g != NULL);
  if (g) {
    CHECK_EQ_U(g->count, 2); /* b/ и d/ */
    CHECK_EQ_U(g->disk, old.disk[at(&old, "b")] + old.disk[at(&old, "d")]);
    CHECK_EQ_U(g->apparent, old.apparent[at(&old, "b")] + old.apparent[at(&old, "d")]);
  }
  CHECK_EQ_U(r.gone_n, 1);
  done(&r);
  arena_unmap(&old);

  /* отказы: нет файла, другая версия, чужой файл, другой корень — ничего не записано */
  int64_t one[1];
  uint8_t st1[1];
  delta_gone *gg = (delta_gone *)1;
  uint32_t gn = 7;
  CHECK(sess_delta(s2, pj(C, "missing"), one, one, st1, &gg, &gn) == -ENOENT);
  CHECK(gg == NULL && gn == 0);
  char path[4096];
  snprintf(path, sizeof path, "%s", pj(C, "v2.ancdu"));
  {
    CHECK(sess_save_cache(s2, path) == 0);
    int fd = open(path, O_WRONLY);
    char v = 2;
    CHECK(pwrite(fd, &v, 1, 7) == 1); /* «ANCDU\0\0\2» */
    close(fd);
  }
  CHECK(sess_delta(s2, path, one, one, st1, &gg, &gn) == -ENOEXEC);
  snprintf(path, sizeof path, "%s", pj(C, "junk"));
  write_file(path, 8192);
  CHECK(sess_delta(s2, path, one, one, st1, &gg, &gn) == -EINVAL);
  /* база другого корня */
  session *s3 = sess_scan_start(pj(R, "a"), 1, 1, &err);
  CHECK(s3 && err == 0);
  CHECK_EQ_U(sess_wait(s3), ST_DONE);
  snprintf(path, sizeof path, "%s", pj(C, "other.ancdu"));
  CHECK(sess_save_cache(s3, path) == 0);
  sess_free(s3);
  CHECK(sess_delta(s2, path, one, one, st1, &gg, &gn) == -EXDEV);
  CHECK(gg == NULL && gn == 0);
  sess_free(s2);
}

/* ~70 000 узлов: 700 каталогов × 99 файлов; время Δ и размер файла базы (доп. место на дерево). */
static void test_bench(void) {
  enum { D = 700, F = 99 };
  uint64_t cap = 1 + D * (F + 1) + 64;
  arena b, c;
  CHECK(arena_alloc_anon(&b, cap, cap * 24, "/storage/emulated/0", SRC_SCAN) == 0);
  CHECK(arena_alloc_anon(&c, cap, cap * 24, "/storage/emulated/0", SRC_SCAN) == 0);
  name_chunk kb = {0, 0}, kc = {0, 0};
  N(&b, &kb, ANCDU_NONE, "", F_DIR, 4096, 4096);
  N(&c, &kc, ANCDU_NONE, "", F_DIR, 4096, 4096);
  uint32_t seed = 7;
  char nm[48];
  for (int d = 0; d < D; d++) {
    snprintf(nm, sizeof nm, "dir-%04d", d);
    uint32_t pb = N(&b, &kb, 0, nm, F_DIR, 4096, 4096);
    uint32_t pc = N(&c, &kc, 0, nm, F_DIR, 4096, 4096);
    for (int f = 0; f < F; f++) {
      seed = seed * 1103515245u + 12345u;
      uint64_t sz = 4096ull * ((seed >> 10) % 64 + 1);
      snprintf(nm, sizeof nm, "IMG_%04d_%05d.jpg", d, f);
      N(&b, &kb, pb, nm, 0, sz, sz - 100);
      /* ~10%: выросли, ~2%: ушли (и столько же новых под другим именем) */
      int r = (seed >> 3) % 100;
      if (r < 2) {
        snprintf(nm, sizeof nm, "IMG_%04d_%05d_new.jpg", d, f);
        N(&c, &kc, pc, nm, 0, sz, sz);
      } else {
        N(&c, &kc, pc, nm, 0, r < 12 ? sz * 2 : sz, sz - 100);
      }
    }
  }
  atomic_store(&b.h->state, ST_DONE);
  atomic_store(&c.h->state, ST_DONE);
  CHECK(post_process(&b, 2) == 0);
  CHECK(post_process(&c, 2) == 0);
  char C[4096];
  snprintf(C, sizeof C, "%s", mk_tmp());
  const char *path = pj(C, "bench.ancdu.base-a");
  CHECK(arena_save_file(&b, path) == 0);
  struct stat st;
  CHECK(stat(path, &st) == 0);
  arena_unmap(&b);
  uint64_t n = atomic_load(&c.h->count);
  res r = {calloc(n, 8), calloc(n, 8), calloc(n, 1), NULL, 0};
  int64_t best = INT64_MAX;
  for (int k = 0; k < 5; k++) {
    free(r.gone);
    r.gone = NULL;
    int64_t t0 = ancdu_now_ns();
    CHECK(delta_compute_file(&c, path, r.disk, r.app, r.st, &r.gone, &r.gone_n) == 0);
    int64_t t = ancdu_now_ns() - t0;
    if (t < best) best = t;
  }
  CHECK(r.gone_n > 0);
  CHECK(r.disk[0] > 0); /* ~10% файлов удвоились */
  printf("bench: nodes=%llu delta_ms=%.2f (best of 5, incl. open+validate) base_file_bytes=%lld\n",
         (unsigned long long)n, best / 1e6, (long long)st.st_size);
  done(&r);
  arena_unmap(&c);
}

int main(void) {
  test_synthetic();
  test_wide_dir();
  test_scan_file();
  test_bench();
  TEST_END();
}
