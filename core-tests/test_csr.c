#include "arena.h"
#include "csr.h"
#include "test.h"

#include <errno.h>

/*
 * root(dir 4096)
 *  ├ a   (dir 4096)  ├ a1 (100 / 4096)  └ a2 (10000 / 12288)
 *  ├ b   (50000 / 53248)
 *  └ c   (dir 4096)  └ c1 (9000 / 12288)
 * Индексы: root0 a1 b2 c3 a1_4 a2_5 c1_6
 */
static uint32_t N(arena *a, name_chunk *ck, uint32_t p, const char *nm,
                  uint8_t fl, uint64_t ap, uint64_t dk) {
  uint32_t i = arena_new_node(a, ck, p, nm, strlen(nm), fl);
  a->apparent[i] = ap;
  a->disk[i] = dk;
  return i;
}

static void build(arena *a) {
  CHECK(arena_alloc_anon(a, 64, 4096, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(a, &ck, ANCDU_NONE, "", F_DIR, 4096, 4096);
  N(a, &ck, 0, "a", F_DIR, 4096, 4096);
  N(a, &ck, 0, "b", 0, 50000, 53248);
  N(a, &ck, 0, "c", F_DIR, 4096, 4096);
  N(a, &ck, 1, "a1", 0, 100, 4096);
  N(a, &ck, 1, "a2", 0, 10000, 12288);
  N(a, &ck, 3, "c1", 0, 9000, 12288);
}

static void test_aggregate_and_sort(int threads) {
  arena a;
  build(&a);
  post_process(&a, threads);
  CHECK_EQ_U(a.disk[0], 94208);
  CHECK_EQ_U(a.apparent[0], 81388);
  CHECK_EQ_U(a.items[0], 7);
  CHECK_EQ_U(a.disk[1], 20480);
  CHECK_EQ_U(a.child_count[0], 3);

  uint32_t out[8];
  CHECK_EQ_U(csr_children(&a, 0, SORT_SIZE, 0, out, 8), 3);
  CHECK(out[0] == 2 && out[1] == 1 && out[2] == 3); /* b a c */
  csr_children(&a, 0, SORT_NAME, 0, out, 8);
  CHECK(out[0] == 1 && out[1] == 2 && out[2] == 3); /* a b c */
  csr_children(&a, 0, SORT_ITEMS, 0, out, 8);
  CHECK(out[0] == 1 && out[1] == 3 && out[2] == 2); /* a(3) c(2) b(1) */
  csr_children(&a, 0, SORT_SIZE, 1, out, 8);
  CHECK(out[0] == 2 && out[1] == 1 && out[2] == 3); /* b 50000, a 14196, c 13096 */
  CHECK_EQ_U(csr_children(&a, 2, SORT_SIZE, 0, out, 8), 0); /* у файла детей нет */
  arena_unmap(&a);
}

static void test_remove(void) {
  arena a;
  build(&a);
  post_process(&a, 1);
  uint32_t out[8];
  CHECK(csr_remove(&a, 5) == 0);              /* a2 */
  CHECK_EQ_U(a.disk[0], 94208 - 12288);
  CHECK_EQ_U(a.disk[1], 8192);
  CHECK_EQ_U(a.items[0], 6);
  CHECK_EQ_U(csr_children(&a, 1, SORT_SIZE, 0, out, 8), 1);
  CHECK_EQ_U(out[0], 4);
  csr_children(&a, 0, SORT_SIZE, 0, out, 8);
  CHECK(out[0] == 2 && out[1] == 3 && out[2] == 1); /* b c(16384) a(8192) — пересортировано */
  CHECK(csr_remove(&a, 5) == -EINVAL);        /* повторно */
  CHECK(csr_remove(&a, 3) == 0);              /* каталог c целиком */
  uint64_t before = a.disk[0];
  CHECK(csr_remove(&a, 6) == -EINVAL);        /* c1 под удалённым c */
  CHECK_EQ_U(a.disk[0], before);
  CHECK(csr_remove(&a, 0) == -EINVAL);        /* корень */
  CHECK(csr_remove(&a, 99) == -EINVAL);       /* вне диапазона */
  arena_unmap(&a);
}

static void test_big_random(void) {
  enum { NN = 200000 };
  arena a;
  CHECK(arena_alloc_anon(&a, NN, NN * 8, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  uint32_t *dirs = malloc(NN * sizeof *dirs);
  uint64_t *own = malloc(NN * sizeof *own);
  size_t nd = 0;
  srand(42);
  dirs[nd++] = N(&a, &ck, ANCDU_NONE, "", F_DIR, 0, 4096);
  own[0] = 4096;
  for (uint32_t i = 1; i < NN; i++) {
    uint32_t p = dirs[(size_t)rand() % nd];
    int isdir = rand() % 10 == 0;
    uint64_t d = (uint64_t)(rand() % 100000) * 512;
    uint32_t x = N(&a, &ck, p, "n", isdir ? F_DIR : 0, d, d);
    own[x] = d;
    if (isdir) dirs[nd++] = x;
  }
  post_process(&a, 4);
  uint64_t total_children = 0;
  for (uint32_t i = 0; i < NN; i++) {
    uint32_t s = a.child_start[i], c = a.child_count[i];
    uint64_t sum = own[i];
    for (uint32_t j = 0; j < c; j++) {
      uint32_t k = a.order[s + j];
      CHECK(a.parent[k] == i);
      if (j) CHECK(a.disk[a.order[s + j - 1]] >= a.disk[k]);
      sum += a.disk[k];
    }
    CHECK_EQ_U(a.disk[i], sum);
    total_children += c;
  }
  CHECK_EQ_U(total_children, NN - 1);
  free(dirs);
  free(own);
  arena_unmap(&a);
}

/* Битый parent[] (недоверенная арена): post_process отказывает -EINVAL и ничего не пишет —
 * ни за пределы массивов, ни в суммы. */
static void test_bad_parent(void) {
  uint32_t bad[] = {7, 1000, ANCDU_NONE, 4}; /* = count, далеко за count, «нет», сам себе */
  for (size_t k = 0; k < sizeof bad / sizeof *bad; k++) {
    arena a;
    build(&a);
    a.parent[4] = bad[k];
    uint64_t d0 = a.disk[0];
    CHECK(post_process(&a, 2) == -EINVAL);
    CHECK_EQ_U(a.disk[0], d0);
    CHECK_EQ_U(a.child_count[0], 0);
    arena_unmap(&a);
  }
  arena a;
  build(&a);
  CHECK(post_process(&a, 2) == 0);
  arena_unmap(&a);
}

int main(void) {
  test_bad_parent();
  test_aggregate_and_sort(1);
  test_aggregate_and_sort(4);
  test_remove();
  test_big_random();
  TEST_END();
}
