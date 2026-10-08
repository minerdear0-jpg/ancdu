/* csr_error_nodes: узлы с F_ERR (не удалённые и не под удалённым предком) в порядке id. Ничего не
 * удаляет: синтетическая арена и один скан фикстуры в mk_tmp() с каталогом chmod 000 (права
 * возвращаются сразу после скана; песочницу убирает run_all.sh). */
#include "arena.h"
#include "csr.h"
#include "session.h"
#include "test.h"

static uint32_t N(arena *a, name_chunk *ck, uint32_t p, const char *nm, uint8_t fl) {
  return arena_new_node(a, ck, p, nm, strlen(nm), fl);
}

/*
 * root
 *  ├ a/ (ERR)
 *  ├ b/       └ b1/ (ERR)   └ b2 (ERR, файл: частичное удаление)
 *  ├ c/ (ERR, удалён)
 *  └ d/       └ d1/ (ERR) — d удалён, d1 под удалённым предком
 * Индексы: root0 a1 b2 c3 d4 b1_5 b2_6 d1_7
 */
static void test_synthetic(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 16, 1024, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(&a, &ck, ANCDU_NONE, "", F_DIR);
  N(&a, &ck, 0, "a", F_DIR | F_ERR);
  N(&a, &ck, 0, "b", F_DIR);
  N(&a, &ck, 0, "c", F_DIR | F_ERR);
  N(&a, &ck, 0, "d", F_DIR);
  N(&a, &ck, 2, "b1", F_DIR | F_ERR);
  N(&a, &ck, 2, "b2", F_ERR);
  N(&a, &ck, 4, "d1", F_DIR | F_ERR);
  CHECK(post_process(&a, 1) == 0);

  uint32_t out[8] = {0};
  uint64_t total = 99;
  CHECK_EQ_U(csr_error_nodes(&a, 8, out, &total), 5);
  CHECK_EQ_U(total, 5);
  CHECK(out[0] == 1 && out[1] == 3 && out[2] == 5 && out[3] == 6 && out[4] == 7);

  CHECK(csr_remove(&a, 3) == 0); /* c */
  CHECK(csr_remove(&a, 4) == 0); /* d: d1 под удалённым */
  CHECK_EQ_U(csr_error_nodes(&a, 8, out, &total), 3);
  CHECK_EQ_U(total, 3);
  CHECK(out[0] == 1 && out[1] == 5 && out[2] == 6);

  /* cap меньше числа: пишутся первые cap, total — все */
  uint32_t two[2] = {0, 0};
  CHECK_EQ_U(csr_error_nodes(&a, 2, two, &total), 2);
  CHECK_EQ_U(total, 3);
  CHECK(two[0] == 1 && two[1] == 5);
  /* cap 0: только подсчёт, out не трогается */
  CHECK_EQ_U(csr_error_nodes(&a, 0, NULL, &total), 0);
  CHECK_EQ_U(total, 3);
  arena_unmap(&a);
}

static void test_empty(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 8, 256, "/r", SRC_SCAN) == 0);
  uint32_t out[2] = {77, 77};
  uint64_t total = 99;
  CHECK_EQ_U(csr_error_nodes(&a, 2, out, &total), 0); /* узлов нет */
  CHECK_EQ_U(total, 0);
  name_chunk ck = {0, 0};
  N(&a, &ck, ANCDU_NONE, "", F_DIR);
  N(&a, &ck, 0, "x", F_DIR);
  CHECK(post_process(&a, 1) == 0);
  CHECK_EQ_U(csr_error_nodes(&a, 2, out, &total), 0); /* ошибок нет */
  CHECK_EQ_U(total, 0);
  CHECK_EQ_U(out[0], 77);
  arena_unmap(&a);
}

/* Настоящий скан: два каталога chmod 000 дают ровно два узла с ошибкой. От root права не мешают
 * читать — тогда ошибок нет, и проверка пропускается. */
static void test_scan(void) {
  if (geteuid() == 0) {
    puts("skip test_scan: root reads chmod 000 dirs");
    return;
  }
  const char *T = mk_tmp();
  mk_dir(pj(T, "ok"));
  write_file(pj(T, "ok/f"), 100);
  mk_dir(pj(T, "locked"));
  write_file(pj(T, "locked/hidden"), 100);
  mk_dir(pj(T, "ok/deep"));
  mk_dir(pj(T, "ok/deep/locked2"));
  CHECK(chmod(pj(T, "locked"), 0) == 0);
  CHECK(chmod(pj(T, "ok/deep/locked2"), 0) == 0);
  int err = 0;
  session *s = sess_scan_start(T, 1, 2, &err);
  /* права возвращаются до любых проверок: песочница всегда удаляема */
  if (s) sess_wait(s);
  CHECK(chmod(pj(T, "locked"), 0755) == 0);
  CHECK(chmod(pj(T, "ok/deep/locked2"), 0755) == 0);
  CHECK(s && err == 0);
  if (!s) return;
  arena *a = sess_arena(s);
  CHECK(a != NULL);
  if (a) {
    uint32_t out[4];
    uint64_t total = 0;
    CHECK_EQ_U(csr_error_nodes(a, 4, out, &total), 2);
    CHECK_EQ_U(total, 2);
    CHECK_EQ_U(atomic_load(&a->h->errors), 2);
    int seen_locked = 0, seen_locked2 = 0;
    for (int i = 0; i < 2; i++) {
      CHECK(a->flags[out[i]] & F_DIR);
      if (strcmp(arena_name(a, out[i]), "locked") == 0) seen_locked++;
      if (strcmp(arena_name(a, out[i]), "locked2") == 0) seen_locked2++;
    }
    CHECK(seen_locked == 1 && seen_locked2 == 1);
    CHECK(out[0] < out[1]);
  }
  sess_free(s);
}

int main(void) {
  test_synthetic();
  test_empty();
  test_scan();
  TEST_END();
}
