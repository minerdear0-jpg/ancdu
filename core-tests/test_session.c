#include "session.h"
#include "test.h"

#include <errno.h>

static void fixture(char *T) {
  snprintf(T, 4096, "%s", mk_tmp());
  mk_dir(pj(T, "d1"));
  write_file(pj(T, "d1/f1"), 1000);
  write_file(pj(T, "d1/f2"), 5000);
  mk_dir(pj(T, "d2"));
  write_file(pj(T, "d2/f3"), 300000);
  write_file(pj(T, "top"), 7);
}

int main(void) {
  char T[4096], C[4096];
  fixture(T);
  snprintf(C, sizeof C, "%s", mk_tmp());
  int err = 1;

  /* скан в процессе */
  session *s = sess_scan_start(T, 1, 2, &err);
  CHECK(s != NULL && err == 0);
  CHECK_EQ_U(sess_wait(s), ST_DONE);
  arena *a = sess_arena(s);
  CHECK(a != NULL);
  int64_t pr[6];
  char path[256];
  sess_progress(s, pr, path, sizeof path);
  CHECK_EQ_U(pr[0], ST_DONE);
  CHECK_EQ_U(pr[1], 6); /* d1 f1 f2 d2 f3 top */
  CHECK(pr[4] >= 0);
  CHECK_EQ_U(pr[5], 0);
  uint32_t nodes[8];
  uint64_t disk[8], sum = 0;
  int k = sess_live_top(s, nodes, disk, 8);
  CHECK_EQ_U(k, 3);
  for (int i = 0; i < k; i++) sum += disk[i];
  struct stat rs;
  stat(T, &rs);
  CHECK_EQ_U(sum + (uint64_t)rs.st_blocks * 512, a->disk[0]);
  size_t nl = 0;
  int seen_d1 = 0;
  for (int i = 0; i < k; i++) {
    const char *nm = sess_live_name(s, nodes[i], &nl);
    CHECK(nm != NULL);
    if (nm && nl == 2 && memcmp(nm, "d1", 2) == 0) seen_d1 = 1;
  }
  CHECK(seen_d1);
  CHECK(sess_live_name(s, 0, &nl) == NULL);
  CHECK(sess_live_name(s, 99, &nl) == NULL);
  uint64_t total = a->disk[0];

  /* sess_mark_err: только флаг узла, границы проверяются */
  CHECK(sess_mark_err(s, 0) == -EINVAL);
  CHECK(sess_mark_err(s, (uint32_t)atomic_load(&a->h->count)) == -EINVAL);
  CHECK(sess_mark_err(s, UINT32_MAX) == -EINVAL);
  CHECK((a->flags[1] & F_ERR) == 0);
  uint64_t d1 = a->disk[1];
  CHECK(sess_mark_err(s, 1) == 0);
  CHECK(a->flags[1] & F_ERR);
  CHECK_EQ_U(a->disk[1], d1);
  CHECK_EQ_U(a->disk[0], total);
  CHECK(sess_mark_err(s, 1) == 0); /* повторно — то же */

  /* кэш */
  const char *cp = pj(C, "last.ancdu");
  CHECK(sess_save_cache(s, cp) == 0);
  session *c = sess_open_cache(cp, &err);
  CHECK(c != NULL && err == 0);
  CHECK_EQ_U(sess_arena(c)->disk[0], total);
  sess_progress(c, pr, NULL, 0);
  CHECK_EQ_U(pr[0], ST_DONE);
  sess_free(c);
  CHECK(sess_open_cache(pj(C, "missing"), &err) == NULL);
  CHECK(err == -ENOENT);
  sess_free(s);

  /* корень не существует */
  s = sess_scan_start(pj(T, "missing"), 1, 2, &err);
  CHECK(s != NULL);
  CHECK_EQ_U(sess_wait(s), ST_FAILED);
  char eb[256];
  CHECK(sess_error(s, eb, sizeof eb) > 0);
  CHECK(sess_arena(s) == NULL);
  CHECK(sess_save_cache(s, cp) == -EBUSY);
  sess_free(s);

  /* индекс */
  session *x = sess_index_begin("/storage/emulated/0", 100, &err);
  CHECK(x != NULL && err == 0);
  CHECK(sess_index_add(x, "DCIM/", "a.jpg", 5000) == 0);
  CHECK(sess_arena(x) == NULL); /* ещё строится */
  CHECK(sess_mark_err(x, 1) == -EINVAL);
  CHECK(sess_index_finish(x) == 0);
  CHECK(sess_arena(x) != NULL);
  CHECK_EQ_U(sess_arena(x)->apparent[0], 5000);
  CHECK(sess_index_add(x, "DCIM/", "b.jpg", 1) == -EINVAL); /* уже закрыт */
  sess_free(x);

  /* освобождение незавершённых сессий без утечек (ASan) */
  session *y = sess_index_begin("/s", 100, &err);
  sess_index_add(y, "A/", "b", 1);
  sess_free(y);
  session *z = sess_scan_start(T, 1, 2, &err);
  sess_cancel(z);
  sess_free(z);

  rm_dir_tree_for_tests(T);
  rm_dir_tree_for_tests(C);
  TEST_END();
}
