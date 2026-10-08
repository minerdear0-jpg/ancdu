/* csr_giants: все файлы дерева не меньше порога (disk убыв., id возр.), не больше max_count, плюс
 * общее число совпадений. Ничего не удаляет: синтетические арены (csr_remove — только флаг в арене)
 * и один скан фикстуры в mk_tmp() (её убирает run_all.sh вместе с песочницей). */
#include "arena.h"
#include "csr.h"
#include "session.h"
#include "test.h"

#include <errno.h>

static uint32_t N(arena *a, name_chunk *ck, uint32_t p, const char *nm, uint8_t fl, uint64_t dk) {
  uint32_t i = arena_new_node(a, ck, p, nm, strlen(nm), fl);
  a->disk[i] = dk;
  a->apparent[i] = dk;
  return i;
}

/*
 * root
 *  ├ a/         ├ a1 4096   ├ a2 12288   └ deep/ └ d1 50000
 *  ├ b 53248
 *  ├ c/         └ c1 12288 (равен a2)
 *  ├ hl 0, F_HLDUP (вторая ссылка на b)
 *  ├ z 0 (пустой файл)
 *  └ e 8192
 * Индексы: root0 a1 b2 c3 hl4 z5 e6 a1_7 a2_8 deep9 c1_10 d1_11
 */
static void build(arena *a) {
  CHECK(arena_alloc_anon(a, 64, 4096, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(a, &ck, ANCDU_NONE, "", F_DIR, 0);
  N(a, &ck, 0, "a", F_DIR, 4096);
  N(a, &ck, 0, "b", 0, 53248);
  N(a, &ck, 0, "c", F_DIR, 4096);
  N(a, &ck, 0, "hl", F_HLDUP, 0);
  N(a, &ck, 0, "z", 0, 0);
  N(a, &ck, 0, "e", 0, 8192);
  N(a, &ck, 1, "a1", 0, 4096);
  N(a, &ck, 1, "a2", 0, 12288);
  N(a, &ck, 1, "deep", F_DIR, 4096);
  N(a, &ck, 3, "c1", 0, 12288);
  N(a, &ck, 9, "d1", 0, 50000);
  CHECK(post_process(a, 1) == 0);
}

static void test_threshold(void) {
  arena a;
  build(&a);
  uint32_t out[16];
  uint64_t total = 99;
  /* ≥ 12288: b, d1, a2 = c1 (меньший id первым); каталоги (a = 4096+…) не в счёт */
  CHECK_EQ_U(csr_giants(&a, 12288, 16, out, &total), 4);
  CHECK_EQ_U(total, 4);
  CHECK(out[0] == 2 && out[1] == 11 && out[2] == 8 && out[3] == 10);
  /* порог ровно по границе: равный входит, на единицу больше — нет */
  CHECK_EQ_U(csr_giants(&a, 50000, 16, out, &total), 2);
  CHECK_EQ_U(total, 2);
  CHECK_EQ_U(csr_giants(&a, 50001, 16, out, &total), 1);
  CHECK_EQ_U(total, 1);
  CHECK_EQ_U(out[0], 2);
  /* выше всех — пусто */
  CHECK_EQ_U(csr_giants(&a, 1u << 30, 16, out, &total), 0);
  CHECK_EQ_U(total, 0);
  /* порог 0: все файлы с disk > 0 (без пустого z и без F_HLDUP), a1 последним */
  CHECK_EQ_U(csr_giants(&a, 0, 16, out, &total), 6);
  CHECK_EQ_U(total, 6);
  for (int i = 0; i < 6; i++) {
    CHECK(!(a.flags[out[i]] & (F_DIR | F_HLDUP)));
    CHECK(out[i] != 4 && out[i] != 5);
  }
  CHECK_EQ_U(out[5], 7);
  /* total = NULL допустим */
  CHECK_EQ_U(csr_giants(&a, 0, 16, out, NULL), 6);
  arena_unmap(&a);
}

static void test_cap_vs_total(void) {
  arena a;
  build(&a);
  uint32_t out[16] = {0};
  uint64_t total = 0;
  /* cap 3 из 6: самые крупные, а total — все */
  CHECK_EQ_U(csr_giants(&a, 1, 3, out, &total), 3);
  CHECK_EQ_U(total, 6);
  CHECK(out[0] == 2 && out[1] == 11 && out[2] == 8);
  /* граница cap на паре равных: остаётся меньший id */
  CHECK_EQ_U(csr_giants(&a, 1, 3, out, &total), 3);
  CHECK_EQ_U(out[2], 8);
  /* cap 0: ничего не пишет, total всё равно считается */
  out[0] = 77;
  CHECK_EQ_U(csr_giants(&a, 1, 0, out, &total), 0);
  CHECK_EQ_U(total, 6);
  CHECK_EQ_U(out[0], 77);
  /* за пределы cap не пишет */
  uint32_t guard[5] = {0, 0, 0, 0xdeadbeef, 0xdeadbeef};
  CHECK_EQ_U(csr_giants(&a, 1, 3, guard, &total), 3);
  CHECK(guard[3] == 0xdeadbeef && guard[4] == 0xdeadbeef);
  arena_unmap(&a);
}

static void test_deleted(void) {
  arena a;
  build(&a);
  uint32_t out[8];
  uint64_t total = 0;
  CHECK(csr_remove(&a, 2) == 0); /* b */
  CHECK(csr_remove(&a, 9) == 0); /* каталог deep: d1 под удалённым предком */
  CHECK_EQ_U(csr_giants(&a, 1, 8, out, &total), 4);
  CHECK_EQ_U(total, 4);
  CHECK(out[0] == 8 && out[1] == 10 && out[2] == 6 && out[3] == 7);
  /* cap меньше: total не считает мёртвых */
  CHECK_EQ_U(csr_giants(&a, 1, 1, out, &total), 1);
  CHECK_EQ_U(total, 4);
  CHECK_EQ_U(out[0], 8);
  arena_unmap(&a);
}

static void test_empty(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 8, 256, "/r", SRC_SCAN) == 0);
  uint32_t out[4] = {99, 99, 99, 99};
  uint64_t total = 5;
  CHECK_EQ_U(csr_giants(&a, 0, 4, out, &total), 0); /* узлов нет совсем */
  CHECK_EQ_U(total, 0);
  name_chunk ck = {0, 0};
  N(&a, &ck, ANCDU_NONE, "", F_DIR, 0);
  N(&a, &ck, 0, "d", F_DIR, 4096);
  CHECK(post_process(&a, 1) == 0);
  total = 5;
  CHECK_EQ_U(csr_giants(&a, 0, 4, out, &total), 0); /* одни каталоги */
  CHECK_EQ_U(total, 0);
  CHECK_EQ_U(out[0], 99);
  arena_unmap(&a);
}

/* Много узлов, много равных: совпадает с полной сортировкой; total — с прямым подсчётом. */
static void test_big(void) {
  enum { M = 20000, K = 500 };
  arena a;
  CHECK(arena_alloc_anon(&a, M + 1, 1u << 20, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(&a, &ck, ANCDU_NONE, "", F_DIR, 0);
  uint32_t seed = 12345;
  for (int i = 1; i <= M; i++) {
    seed = seed * 1103515245u + 12345u;
    char nm[16];
    snprintf(nm, sizeof nm, "f%d", i);
    uint64_t d = (seed >> 8) % 1000;
    N(&a, &ck, i > 10 ? (uint32_t)(i % 10) : 0, nm, i <= 10 ? F_DIR : 0, d);
  }
  CHECK(post_process(&a, 2) == 0);
  const uint64_t MIN = 900;
  uint64_t want = 0;
  for (uint32_t i = 11; i <= M; i++) want += a.disk[i] >= MIN;
  CHECK(want > K); /* cap действительно режет */
  uint32_t out[K];
  uint64_t total = 0;
  CHECK_EQ_U(csr_giants(&a, MIN, K, out, &total), K);
  CHECK_EQ_U(total, want);
  uint32_t prev = ANCDU_NONE;
  for (int j = 0; j < K; j++) {
    uint32_t best = ANCDU_NONE;
    for (uint32_t i = 11; i <= M; i++) {
      if (a.disk[i] < MIN) continue;
      if (prev != ANCDU_NONE && (a.disk[i] > a.disk[prev] || (a.disk[i] == a.disk[prev] && i <= prev)))
        continue;
      if (best == ANCDU_NONE || a.disk[i] > a.disk[best] || (a.disk[i] == a.disk[best] && i < best))
        best = i;
    }
    CHECK_EQ_U(out[j], best);
    prev = best;
  }
  arena_unmap(&a);
}

/* Настоящий скан песочницы: жёсткая ссылка — один раз, каталоги не попадают, порог работает. */
static void test_scan(void) {
  const char *T = mk_tmp();
  mk_dir(pj(T, "Download"));
  write_file(pj(T, "Download/big.iso"), 300000);
  mk_dir(pj(T, "DCIM"));
  mk_dir(pj(T, "DCIM/Camera"));
  write_file(pj(T, "DCIM/Camera/v.mp4"), 200000);
  CHECK(link(pj(T, "DCIM/Camera/v.mp4"), pj(T, "Download/v-link.mp4")) == 0);
  write_file(pj(T, "small.txt"), 10);
  int err = 0;
  session *s = sess_scan_start(T, 1, 2, &err);
  CHECK(s && err == 0);
  if (!s) return;
  CHECK_EQ_U(sess_wait(s), ST_DONE);
  arena *a = sess_arena(s);
  CHECK(a != NULL);
  if (a) {
    uint32_t out[8];
    uint64_t total = 0;
    CHECK_EQ_U(csr_giants(a, 100000, 8, out, &total), 2);
    CHECK_EQ_U(total, 2);
    CHECK(strcmp(arena_name(a, out[0]), "big.iso") == 0);
    const char *second = arena_name(a, out[1]);
    CHECK(strcmp(second, "v.mp4") == 0 || strcmp(second, "v-link.mp4") == 0);
  }
  sess_free(s);
}

/* ~70 000 узлов (700 каталогов × 99 файлов), ~3% крупнее порога: время одного запроса. */
static void test_bench(void) {
  enum { D = 700, F = 99, CAP = 2000 };
  uint64_t cap = 1 + D * (F + 1) + 64;
  arena a;
  CHECK(arena_alloc_anon(&a, cap, cap * 24, "/storage/emulated/0", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(&a, &ck, ANCDU_NONE, "", F_DIR, 0);
  uint32_t seed = 7;
  char nm[48];
  for (int d = 0; d < D; d++) {
    snprintf(nm, sizeof nm, "dir-%04d", d);
    uint32_t p = N(&a, &ck, 0, nm, F_DIR, 4096);
    for (int f = 0; f < F; f++) {
      seed = seed * 1103515245u + 12345u;
      int r = (seed >> 3) % 100;
      uint64_t sz = r < 3 ? (100ull << 20) + ((seed >> 10) % 4096) * 65536 : 4096ull * ((seed >> 10) % 64 + 1);
      snprintf(nm, sizeof nm, "IMG_%04d_%05d.jpg", d, f);
      N(&a, &ck, p, nm, 0, sz);
    }
  }
  atomic_store(&a.h->state, ST_DONE);
  CHECK(post_process(&a, 2) == 0);
  uint32_t *out = malloc(CAP * sizeof *out);
  uint64_t total = 0;
  uint32_t got = 0;
  int64_t best = INT64_MAX;
  for (int k = 0; k < 5; k++) {
    int64_t t0 = ancdu_now_ns();
    got = csr_giants(&a, 100ull << 20, CAP, out, &total);
    int64_t t = ancdu_now_ns() - t0;
    if (t < best) best = t;
  }
  CHECK(got == CAP && total > CAP); /* ~3% of 69 300 files: the cap really cuts */
  for (uint32_t i = 1; i < got; i++) CHECK(a.disk[out[i - 1]] >= a.disk[out[i]]);
  printf("bench: nodes=%llu giants=%u total=%llu giants_ms=%.3f (best of 5)\n",
         (unsigned long long)atomic_load(&a.h->count), got, (unsigned long long)total, best / 1e6);
  CHECK(best < 5 * 1000 * 1000);
  free(out);
  arena_unmap(&a);
}

int main(void) {
  test_threshold();
  test_cap_vs_total();
  test_deleted();
  test_empty();
  test_big();
  test_scan();
  test_bench();
  TEST_END();
}
