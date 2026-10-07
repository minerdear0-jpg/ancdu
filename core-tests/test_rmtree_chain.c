/* Родитель удаления под root (rm_open_parent nofollow) — от «/» без ссылок, дважды: через
 * openat2 (если ядро и seccomp позволяют) и по компонентам (openat2_state = -1, как на 4.19/5.4).
 * Ссылка в промежуточном компоненте — -ELOOP, цель ссылки цела; обычные вложенные удаления
 * работают; подмена компонента на ссылку ПОСЛЕ открытия родителя не уводит удаление.
 * rmtree.c включается сюда (доступ к openat2_state). Только абсолютные пути внутри
 * собственного mkdtemp; каждая цель удаления проходит sandbox_guard. */
#include "rmtree.c"

#include "test.h"

static char S[4096];

static int parent_ok(const char *p, char *last, size_t cap) {
  int fd = rm_open_parent(p, 1, last, cap);
  if (fd < 0) return fd;
  close(fd);
  return 0;
}

/* Удаление как у хелпера: родитель без ссылок, затем rm_tree_at относительно него. */
static int rm_nofollow(const char *p) {
  char g[4400];
  size_t n = strlen(p);
  while (n > 1 && p[n - 1] == '/') n--;
  snprintf(g, sizeof g, "%.*s", (int)n, p);
  sandbox_guard(S, g);
  char last[NAME_MAX + 1];
  int fd = rm_open_parent(p, 1, last, sizeof last);
  if (fd < 0) return fd;
  int r = rm_tree_at(fd, last, 1, NULL, NULL, NULL);
  close(fd);
  return r;
}

static void pass(const char *mode) {
  char d[4200], q[4400], last[NAME_MAX + 1];
  snprintf(d, sizeof d, "%s/%s", S, mode);
  mk_dir(d);

  /* обычное вложенное удаление: каталог и файл */
  mk_dir(pj(d, "x"));
  mk_dir(pj(d, "x/y"));
  mk_dir(pj(d, "x/y/z"));
  mk_dir(pj(d, "x/y/z/victim"));
  write_file(pj(d, "x/y/z/victim/f"), 10);
  write_file(pj(d, "x/y/z/file"), 10);
  CHECK(parent_ok(pj(d, "x/y/z/victim"), last, sizeof last) == 0 && !strcmp(last, "victim"));
  CHECK(rm_nofollow(pj(d, "x/y/z/victim")) == 0);
  CHECK(access(pj(d, "x/y/z/victim"), F_OK) != 0);
  snprintf(q, sizeof q, "%s/x/y/z/file//", d);
  CHECK(rm_nofollow(q) == 0); /* завершающие слеши срезаются */
  CHECK(access(pj(d, "x/y/z/file"), F_OK) != 0);
  CHECK(access(pj(d, "x/y/z"), F_OK) == 0);

  /* ссылка в промежуточном компоненте: отказ -ELOOP, цель ссылки цела */
  mk_dir(pj(d, "real"));
  mk_dir(pj(d, "real/b"));
  mk_dir(pj(d, "real/b/victim"));
  write_file(pj(d, "real/b/victim/sentinel"), 10);
  CHECK(symlink(pj(d, "real"), pj(d, "lnk")) == 0);
  CHECK(symlink("real/b", pj(d, "rel_lnk")) == 0); /* относительная ссылка — тоже */
  CHECK(rm_nofollow(pj(d, "lnk/b/victim")) == -ELOOP);
  CHECK(rm_nofollow(pj(d, "rel_lnk/victim")) == -ELOOP);
  /* ссылка — последний компонент родителя (прямо над вершиной) */
  CHECK(symlink(pj(d, "real/b"), pj(d, "real/blink")) == 0);
  CHECK(rm_nofollow(pj(d, "real/blink/victim")) == -ELOOP);
  CHECK(access(pj(d, "real/b/victim/sentinel"), F_OK) == 0);
  /* сама вершина-ссылка — не родитель: удаляется ссылка, цель цела */
  CHECK(rm_nofollow(pj(d, "real/blink")) == 0);
  CHECK(access(pj(d, "real/b/victim/sentinel"), F_OK) == 0);

  /* магическая ссылка: только открытие родителя, без удаления */
  CHECK(parent_ok("/proc/self/cwd/x", last, sizeof last) == -ELOOP);
  snprintf(q, sizeof q, "/proc/%d/cwd/x", (int)getpid()); /* magic link (proc_pid_link) */
  CHECK(parent_ok(q, last, sizeof last) == -ELOOP);

  /* не нормализован — -ELOOP; относительный и пустой/точечный хвост — -EINVAL; без ФС */
  snprintf(q, sizeof q, "%s/x/../x/y", d);
  CHECK(parent_ok(q, last, sizeof last) == -ELOOP);
  snprintf(q, sizeof q, "%s/x//y/z", d);
  CHECK(parent_ok(q, last, sizeof last) == -ELOOP);
  snprintf(q, sizeof q, "%s/./x/y", d);
  CHECK(parent_ok(q, last, sizeof last) == -ELOOP);
  CHECK(parent_ok("x/y", last, sizeof last) == -EINVAL);
  CHECK(parent_ok("/", last, sizeof last) == -EINVAL);
  snprintf(q, sizeof q, "%s/x/..", d);
  CHECK(parent_ok(q, last, sizeof last) == -EINVAL);

  /* нет компонента — -ENOENT; компонент — файл — -ENOTDIR */
  CHECK(parent_ok(pj(d, "nope/a/b"), last, sizeof last) == -ENOENT);
  write_file(pj(d, "plain"), 1);
  CHECK(parent_ok(pj(d, "plain/a"), last, sizeof last) == -ENOTDIR);
  CHECK(parent_ok("/x", last, sizeof last) == 0 && !strcmp(last, "x")); /* родитель — «/» */

  /* check-then-act закрыт: родитель открыт, затем a подменён ссылкой на other — удаляется
   * исходная victim (теперь a.moved/b/victim), а other/b/victim цела */
  mk_dir(pj(d, "a"));
  mk_dir(pj(d, "a/b"));
  mk_dir(pj(d, "a/b/victim"));
  write_file(pj(d, "a/b/victim/orig"), 10);
  mk_dir(pj(d, "other"));
  mk_dir(pj(d, "other/b"));
  mk_dir(pj(d, "other/b/victim"));
  write_file(pj(d, "other/b/victim/keep"), 10);
  sandbox_guard(S, pj(d, "a/b/victim"));
  int fd = rm_open_parent(pj(d, "a/b/victim"), 1, last, sizeof last);
  CHECK(fd >= 0);
  if (fd >= 0) {
    CHECK(rename(pj(d, "a"), pj(d, "a.moved")) == 0);
    CHECK(symlink(pj(d, "other"), pj(d, "a")) == 0);
    CHECK(rm_tree_at(fd, last, 1, NULL, NULL, NULL) == 0);
    close(fd);
  }
  CHECK(access(pj(d, "a.moved/b/victim"), F_OK) != 0);
  CHECK(access(pj(d, "other/b/victim/keep"), F_OK) == 0);

  /* in-process (nofollow = 0): ссылка в родителе допустима, как у cacheDir (/data/user/0) */
  sandbox_guard(S, pj(d, "lnk/b/victim"));
  CHECK(rm_tree(pj(d, "lnk/b/victim")) == 0);
  CHECK(access(pj(d, "real/b/victim"), F_OK) != 0);
  fd = rm_open_parent("rel/x", 0, last, sizeof last); /* относительный — отказ без ФС */
  CHECK(fd == -EINVAL);

  sandbox_guard(S, d);
  CHECK(rm_tree(d) == 0);
}

int main(void) {
  alarm(60);
  snprintf(S, sizeof S, "%s", mk_tmp());
  if (S[0] != '/') { fprintf(stderr, "relative mk_tmp: %s\n", S); return 1; }

  pass("auto");
  if (atomic_load(&openat2_state) == 1) puts("openat2: used");
  else fputs("SKIP openat2: unavailable here, both passes use the chain\n", stderr);
  atomic_store(&openat2_state, -1);
  pass("chain");

  CHECK(rm_tree(S) == 0);
  CHECK(access(S, F_OK) != 0);
  TEST_END();
}
