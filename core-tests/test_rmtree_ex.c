/* rm_tree_ex: счётчик done, параллельный путь, стоп из другого потока.
 * Только абсолютные пути внутри собственного mkdtemp. */
#include <dirent.h>
#include <errno.h>
#include <pthread.h>
#include <sched.h>

#include "rmtree.h"
#include "test.h"

/* Записей в дереве p, включая сам p (0 — p нет). */
static uint64_t entries(const char *p) {
  struct stat st;
  if (lstat(p, &st) != 0) return 0;
  uint64_t n = 1;
  if (!S_ISDIR(st.st_mode)) return n;
  DIR *d = opendir(p);
  if (!d) return n;
  struct dirent *e;
  while ((e = readdir(d))) {
    if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
    char c[4400]; /* не pj: его кольцо затирается рекурсией */
    snprintf(c, sizeof c, "%s/%s", p, e->d_name);
    n += entries(c);
  }
  closedir(d);
  return n;
}

/* flat файлов в корне, плюс 3 уровня по 4 подкаталога с 5 файлами и ссылкой на keep. */
static uint64_t build(const char *root, int flat, const char *keep) {
  mk_dir(root);
  uint64_t n = 1;
  for (int i = 0; i < flat; i++) {
    char f[32];
    snprintf(f, sizeof f, "f%d", i);
    write_file(pj(root, f), 1);
    n++;
  }
  char a[4300], b[4400], c[4500];
  for (int i = 0; i < 4; i++) {
    snprintf(a, sizeof a, "%s/a%d", root, i);
    mk_dir(a);
    n++;
    for (int j = 0; j < 4; j++) {
      snprintf(b, sizeof b, "%s/b%d", a, j);
      mk_dir(b);
      n++;
      for (int k = 0; k < 4; k++) {
        snprintf(c, sizeof c, "%s/c%d", b, k);
        mk_dir(c);
        n++;
        for (int m = 0; m < 5; m++) {
          char f[32];
          snprintf(f, sizeof f, "g%d", m);
          write_file(pj(c, f), 1);
          n++;
        }
        if (symlink(keep, pj(c, "link")) != 0) { perror("symlink"); exit(2); }
        n++;
      }
    }
  }
  return n;
}

typedef struct {
  _Atomic uint64_t *done;
  _Atomic int *stop;
  _Atomic int ready;
  uint64_t at;
} stopper;

static void *stop_after(void *p) {
  stopper *s = p;
  atomic_store(&s->ready, 1);
  while (atomic_load(s->done) < s->at) sched_yield();
  atomic_store(s->stop, 1);
  return NULL;
}

int main(void) {
  char t[4096];
  snprintf(t, sizeof t, "%s", mk_tmp());
  if (t[0] != '/') { fprintf(stderr, "relative mk_tmp: %s\n", t); return 1; }
  mk_dir(pj(t, "keep"));
  write_file(pj(t, "keep/important"), 10);
  const char *keep = pj(t, "keep");
  char K[4200];
  snprintf(K, sizeof K, "%s", keep);

  /* done считает каждую запись ровно один раз: последовательно и параллельно */
  int ths[] = {1, 4, 16};
  for (size_t i = 0; i < sizeof ths / sizeof *ths; i++) {
    char r[4200];
    snprintf(r, sizeof r, "%s/tree%d", t, ths[i]);
    uint64_t n = build(r, 5000, K);
    CHECK_EQ_U(entries(r), n);
    _Atomic uint64_t done = 0;
    _Atomic int stop = 0;
    CHECK(rm_tree_ex(r, ths[i], &done, &stop) == 0);
    CHECK_EQ_U(atomic_load(&done), n);
    CHECK(access(r, F_OK) != 0);
    CHECK(access(pj(t, "keep/important"), F_OK) == 0);
  }

  /* одиночный файл с потоками: просто unlink, done = 1 */
  write_file(pj(t, "single"), 1);
  _Atomic uint64_t d1 = 0;
  CHECK(rm_tree_ex(pj(t, "single"), 4, &d1, NULL) == 0);
  CHECK_EQ_U(atomic_load(&d1), 1);

  /* стоп до начала: ничего не тронуто, -EINTR */
  {
    char r[4200];
    snprintf(r, sizeof r, "%s/pre", t);
    uint64_t n = build(r, 10, K);
    _Atomic uint64_t done = 0;
    _Atomic int stop = 1;
    CHECK(rm_tree_ex(r, 4, &done, &stop) == -EINTR);
    CHECK_EQ_U(atomic_load(&done), 0);
    CHECK_EQ_U(entries(r), n);
    CHECK(rm_tree(r) == 0);
  }

  /* стоп из другого потока после 100 записей: -EINTR, удалённое удалено, остальное на месте */
  for (int th = 1; th <= 4; th += 3) {
    char r[4200];
    snprintf(r, sizeof r, "%s/stop%d", t, th);
    uint64_t n = build(r, 20000, K);
    _Atomic uint64_t done = 0;
    _Atomic int stop = 0;
    stopper s = {.done = &done, .stop = &stop, .at = 100};
    pthread_t pt;
    CHECK(pthread_create(&pt, NULL, stop_after, &s) == 0);
    while (!atomic_load(&s.ready)) sched_yield();
    int rr = rm_tree_ex(r, th, &done, &stop);
    pthread_join(pt, NULL);
    CHECK(rr == -EINTR);
    uint64_t dn = atomic_load(&done);
    CHECK(dn >= 100 && dn < n);
    CHECK_EQ_U(entries(r), n - dn);
    CHECK(access(r, F_OK) == 0);
    CHECK(rm_tree(r) == 0);
  }

  CHECK(access(pj(t, "keep/important"), F_OK) == 0);
  CHECK(rm_tree(t) == 0);
  CHECK(access(t, F_OK) != 0);
  TEST_END();
}
