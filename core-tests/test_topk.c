/* csr_top_files: крупнейшие файлы всего дерева (min-куча, O(n log k)). Ничего не удаляет:
 * синтетические арены и один скан фикстуры в mk_tmp() (её убирает run_all.sh вместе с песочницей). */
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

static void test_order_and_ties(void) {
  arena a;
  build(&a);
  uint32_t out[8];
  /* b 53248, d1 50000, a2 12288 = c1 12288 (меньший id первым), e 8192, a1 4096 */
  CHECK_EQ_U(csr_top_files(&a, 5, out), 5);
  CHECK_EQ_U(out[0], 2);
  CHECK_EQ_U(out[1], 11);
  CHECK_EQ_U(out[2], 8);
  CHECK_EQ_U(out[3], 10);
  CHECK_EQ_U(out[4], 6);
  /* k = 3: из пары равных остаётся меньший id */
  CHECK_EQ_U(csr_top_files(&a, 3, out), 3);
  CHECK(out[0] == 2 && out[1] == 11 && out[2] == 8);
  CHECK_EQ_U(csr_top_files(&a, 1, out), 1);
  CHECK_EQ_U(out[0], 2);
  arena_unmap(&a);
}

static void test_k_more_than_files(void) {
  arena a;
  build(&a);
  uint32_t out[16];
  /* 6 файлов с disk > 0: без каталогов, без F_HLDUP и без пустого z */
  CHECK_EQ_U(csr_top_files(&a, 16, out), 6);
  for (int i = 0; i < 6; i++) {
    CHECK(!(a.flags[out[i]] & (F_DIR | F_HLDUP)));
    CHECK(out[i] != 5 && out[i] != 4);
    if (i) CHECK(a.disk[out[i - 1]] >= a.disk[out[i]]);
  }
  CHECK_EQ_U(out[5], 7); /* a1 последним */
  CHECK_EQ_U(csr_top_files(&a, 0, out), 0);
  arena_unmap(&a);
}

static void test_deleted(void) {
  arena a;
  build(&a);
  uint32_t out[8];
  CHECK(csr_remove(&a, 2) == 0); /* b */
  CHECK(csr_remove(&a, 9) == 0); /* каталог deep: d1 под удалённым предком */
  CHECK_EQ_U(csr_top_files(&a, 5, out), 4);
  CHECK(out[0] == 8 && out[1] == 10 && out[2] == 6 && out[3] == 7);
  arena_unmap(&a);
}

static void test_empty(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 8, 256, "/r", SRC_SCAN) == 0);
  uint32_t out[4] = {99, 99, 99, 99};
  CHECK_EQ_U(csr_top_files(&a, 4, out), 0); /* узлов нет совсем */
  name_chunk ck = {0, 0};
  N(&a, &ck, ANCDU_NONE, "", F_DIR, 0);
  N(&a, &ck, 0, "d", F_DIR, 4096);
  CHECK(post_process(&a, 1) == 0);
  CHECK_EQ_U(csr_top_files(&a, 4, out), 0); /* одни каталоги */
  CHECK_EQ_U(out[0], 99);
  arena_unmap(&a);
}

/* Много узлов в случайном порядке: совпадает с полной сортировкой. */
static void test_big(void) {
  enum { M = 20000, K = 37 };
  arena a;
  CHECK(arena_alloc_anon(&a, M + 1, 1u << 20, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(&a, &ck, ANCDU_NONE, "", F_DIR, 0);
  uint32_t seed = 12345;
  for (int i = 1; i <= M; i++) {
    seed = seed * 1103515245u + 12345u;
    char nm[16];
    snprintf(nm, sizeof nm, "f%d", i);
    uint64_t d = (seed >> 8) % 1000; /* много равных */
    N(&a, &ck, i > 10 ? (uint32_t)(i % 10) : 0, nm, i <= 10 ? F_DIR : 0, d);
  }
  CHECK(post_process(&a, 2) == 0);
  uint32_t out[K];
  CHECK_EQ_U(csr_top_files(&a, K, out), K);
  /* эталон: каждый out[j] — j-й по (disk убыв., id возр.) */
  uint32_t prev = ANCDU_NONE;
  for (int j = 0; j < K; j++) {
    uint32_t best = ANCDU_NONE;
    for (uint32_t i = 11; i <= M; i++) {
      if (a.disk[i] == 0) continue;
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

/* Настоящий скан песочницы: жёсткая ссылка считается один раз, каталоги не попадают. */
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
    uint32_t out[5];
    CHECK_EQ_U(csr_top_files(a, 5, out), 3);
    CHECK(strcmp(arena_name(a, out[0]), "big.iso") == 0);
    const char *second = arena_name(a, out[1]);
    CHECK(strcmp(second, "v.mp4") == 0 || strcmp(second, "v-link.mp4") == 0);
    CHECK(strcmp(arena_name(a, out[2]), "small.txt") == 0);
  }
  sess_free(s);
}

int main(void) {
  test_order_and_ties();
  test_k_more_than_files();
  test_deleted();
  test_empty();
  test_big();
  test_scan();
  TEST_END();
}
