/* Детерминированный стоп: unlinkat перехвачен на этапе компиляции (rmtree.c включается
 * сюда), после N удачных удалений перехватчик взводит stop. Последовательно счёт точен:
 * done == N, остальное на месте. Только абсолютные пути внутри собственного mkdtemp. */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <stdatomic.h>
#include <stdint.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static _Atomic int hook_stop;
static _Atomic int hook_left = -1; /* сколько удачных удалений до стопа; <0 — без стопа */

static int hook_unlinkat(int dfd, const char *name, int flags) {
  int r = unlinkat(dfd, name, flags);
  if (r == 0 && atomic_fetch_sub(&hook_left, 1) == 1) atomic_store(&hook_stop, 1);
  return r;
}

#define unlinkat hook_unlinkat
#include "rmtree.c"
#undef unlinkat

#include "test.h"

static uint64_t entries(const char *p) {
  struct stat st;
  if (lstat(p, &st) != 0) return 0;
  uint64_t n = 1;
  if (!S_ISDIR(st.st_mode)) return n;
  DIR *d = opendir(p);
  struct dirent *e;
  while (d && (e = readdir(d))) {
    if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
    char c[4400]; /* не pj: его кольцо затирается рекурсией */
    snprintf(c, sizeof c, "%s/%s", p, e->d_name);
    n += entries(c);
  }
  if (d) closedir(d);
  return n;
}

static uint64_t build(const char *r) {
  mk_dir(r);
  uint64_t n = 1;
  for (int i = 0; i < 10; i++) {
    char d[4300];
    snprintf(d, sizeof d, "%s/d%d", r, i);
    mk_dir(d);
    n++;
    for (int j = 0; j < 50; j++) {
      char f[32];
      snprintf(f, sizeof f, "f%d", j);
      write_file(pj(d, f), 1);
      n++;
    }
  }
  return n;
}

int main(void) {
  char t[4096];
  snprintf(t, sizeof t, "%s", mk_tmp());
  if (t[0] != '/') { fprintf(stderr, "relative mk_tmp: %s\n", t); return 1; }

  for (int th = 1; th <= 4; th += 3) {
    char r[4200];
    snprintf(r, sizeof r, "%s/t%d", t, th);
    uint64_t n = build(r);
    _Atomic uint64_t done = 0;
    atomic_store(&hook_stop, 0);
    atomic_store(&hook_left, 120);
    CHECK(rm_tree_ex(r, th, &done, &hook_stop) == -EINTR);
    uint64_t dn = atomic_load(&done);
    if (th == 1) CHECK_EQ_U(dn, 120);
    else CHECK(dn >= 120 && dn < n);
    CHECK_EQ_U(entries(r), n - dn);
    atomic_store(&hook_left, -1);
    CHECK(rm_tree(r) == 0);
  }
  CHECK(rm_tree(t) == 0);
  CHECK(access(t, F_OK) != 0);
  TEST_END();
}
