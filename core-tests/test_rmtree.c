#include "rmtree.h"
#include "test.h"

#include <errno.h>
#include <sched.h>
#include <sys/mount.h>
#include <sys/wait.h>

#define SKIP 77

static int put(const char *path, const char *s) {
  int fd = open(path, O_WRONLY);
  if (fd < 0) return -1;
  ssize_t n = (ssize_t)strlen(s);
  int ok = write(fd, s, (size_t)n) == n;
  close(fd);
  return ok ? 0 : -1;
}

/* Дочерний процесс: свои user+mount namespaces, настоящие tmpfs внутри дерева.
 * Возвращает 0 — все проверки прошли, SKIP — namespaces недоступны, иначе 1. */
static int cross_fs_child(const char *t) {
  char m[64];
  uid_t uid = getuid();
  gid_t gid = getgid();
  if (unshare(CLONE_NEWUSER | CLONE_NEWNS) != 0) return SKIP;
  put("/proc/self/setgroups", "deny");
  snprintf(m, sizeof m, "0 %u 1", (unsigned)uid);
  if (put("/proc/self/uid_map", m) != 0) return SKIP;
  snprintf(m, sizeof m, "0 %u 1", (unsigned)gid);
  if (put("/proc/self/gid_map", m) != 0) return SKIP;
  if (mount("none", "/", NULL, MS_REC | MS_PRIVATE, NULL) != 0) return SKIP;

  mk_dir(pj(t, "x"));
  mk_dir(pj(t, "x/sub"));
  write_file(pj(t, "x/sub/f"), 10);
  write_file(pj(t, "x/a"), 10);
  mk_dir(pj(t, "x/mnt"));
  if (mount("none", pj(t, "x/mnt"), "tmpfs", 0, NULL) != 0) return SKIP;
  write_file(pj(t, "x/mnt/inside"), 10);
  /* файл, примонтированный bind'ом с другой ФС */
  mk_dir(pj(t, "other"));
  if (mount("none", pj(t, "other"), "tmpfs", 0, NULL) != 0) return SKIP;
  write_file(pj(t, "other/src"), 10);
  write_file(pj(t, "x/bf"), 1);
  if (mount(pj(t, "other/src"), pj(t, "x/bf"), NULL, MS_BIND, NULL) != 0) return SKIP;

  /* вершина — точка монтирования: отказ, ничего не тронуто */
  CHECK(rm_tree(pj(t, "x/mnt")) == -EXDEV);
  CHECK(access(pj(t, "x/mnt/inside"), F_OK) == 0);
  CHECK(rm_tree(pj(t, "other")) == -EXDEV);
  CHECK(access(pj(t, "other/src"), F_OK) == 0);
  CHECK(rm_tree(pj(t, "x/bf")) == -EXDEV);

  /* внутри дерева: чужая ФС пропускается, остальное удаляется, итог — частичный (-EXDEV) */
  CHECK(rm_tree(pj(t, "x")) == -EXDEV);
  CHECK(access(pj(t, "x/a"), F_OK) != 0);
  CHECK(access(pj(t, "x/sub"), F_OK) != 0);
  CHECK(access(pj(t, "x/mnt/inside"), F_OK) == 0);
  CHECK(access(pj(t, "x/bf"), F_OK) == 0);
  CHECK(access(pj(t, "other/src"), F_OK) == 0);

  /* то же параллельно (4 рабочих): bind-файл по быстрому пути d_type, tmpfs-каталог */
  mk_dir(pj(t, "y"));
  for (int i = 0; i < 300; i++) {
    char fn[32];
    snprintf(fn, sizeof fn, "y/f%d", i);
    write_file(pj(t, fn), 1);
  }
  mk_dir(pj(t, "y/mnt"));
  if (mount("none", pj(t, "y/mnt"), "tmpfs", 0, NULL) != 0) return 1;
  write_file(pj(t, "y/mnt/inside"), 10);
  write_file(pj(t, "y/bf"), 1);
  if (mount(pj(t, "other/src"), pj(t, "y/bf"), NULL, MS_BIND, NULL) != 0) return 1;
  _Atomic uint64_t done = 0;
  CHECK(rm_tree_ex(pj(t, "y"), 4, &done, NULL) == -EXDEV);
  CHECK_EQ_U(atomic_load(&done), 300);
  CHECK(access(pj(t, "y/f0"), F_OK) != 0);
  CHECK(access(pj(t, "y/mnt/inside"), F_OK) == 0);
  CHECK(access(pj(t, "y/bf"), F_OK) == 0);
  CHECK(access(pj(t, "other/src"), F_OK) == 0);
  CHECK(umount(pj(t, "y/bf")) == 0);
  CHECK(umount(pj(t, "y/mnt")) == 0);
  CHECK(rm_tree(pj(t, "y")) == 0);

  CHECK(umount(pj(t, "x/bf")) == 0);
  CHECK(umount(pj(t, "x/mnt")) == 0);
  CHECK(umount(pj(t, "other")) == 0);
  CHECK(rm_tree(pj(t, "x")) == 0);
  CHECK(rm_tree(pj(t, "other")) == 0);

  /* вершина-точка монтирования прямо под «/» (ветка «родитель — корень»), с завершающим
   * слешем. «/» здесь — каталог теста: chroot(t), подтверждённый по st_dev/st_ino до вызова.
   * Без подтверждения rm_tree не вызывается. tmpfs исчезает вместе с namespace. */
  mk_dir(pj(t, "top"));
  if (mount("none", pj(t, "top"), "tmpfs", 0, NULL) != 0) return 1;
  write_file(pj(t, "top/inside"), 10);
  struct stat ts, rs;
  if (stat(t, &ts) != 0 || chroot(t) != 0 || chdir("/") != 0) return 1;
  if (stat("/", &rs) != 0 || rs.st_dev != ts.st_dev || rs.st_ino != ts.st_ino) return 1;
  CHECK(rm_tree("/top/") == -EXDEV);
  CHECK(access("/top/inside", F_OK) == 0);
  return t_fail ? 1 : 0;
}

static void cross_fs(const char *t) {
  fflush(NULL);
  pid_t pid = fork();
  if (pid == 0) _exit(cross_fs_child(t));
  int st = 0;
  CHECK(pid > 0 && waitpid(pid, &st, 0) == pid && WIFEXITED(st));
  if (WIFEXITED(st) && WEXITSTATUS(st) == SKIP) {
    fprintf(stderr, "SKIP cross-fs: user/mount namespaces unavailable\n");
    rm_tree(pj(t, "x"));
    rm_tree(pj(t, "other"));
  } else {
    CHECK(WIFEXITED(st) && WEXITSTATUS(st) == 0);
  }
}

/* Только проверка пути, без ФС: rm_tree здесь не вызывается. «/» и одни слеши — пустой
 * последний компонент, отказ -EINVAL (rm_tree делает эту проверку до любых lstat/unlink). */
static void target_cases(void) {
  char b[64];
  const char *bad[] = {"/", "//", "///", "", ".", "..", "./", "a/.", "/x/..//"};
  for (size_t i = 0; i < sizeof bad / sizeof *bad; i++) {
    int r = rm_tree_target(bad[i], b, sizeof b);
    if (r != -EINVAL) {
      fprintf(stderr, "path not refused (%d): '%s'\n", r, bad[i]);
      t_fail++;
    }
  }
  CHECK(rm_tree_target("/x", b, sizeof b) == 0 && strcmp(b, "/x") == 0);
  CHECK(rm_tree_target("/x//", b, sizeof b) == 0 && strcmp(b, "/x") == 0);
  CHECK(rm_tree_target("/a/.b", b, sizeof b) == 0 && strcmp(b, "/a/.b") == 0);
  CHECK(rm_tree_target("/a/...", b, sizeof b) == 0);
  CHECK(rm_tree_target("rel", b, sizeof b) == 0 && strcmp(b, "rel") == 0);
  CHECK(rm_tree_target("/0123456789", b, 8) == -ENAMETOOLONG);
}

int main(void) {
  target_cases();
  const char *T = mk_tmp();
  char t[4096];
  snprintf(t, sizeof t, "%s", T);

  /* цель симлинка должна выжить */
  mk_dir(pj(t, "keep"));
  write_file(pj(t, "keep/important"), 10);
  mk_dir(pj(t, "victim"));
  mk_dir(pj(t, "victim/deep"));
  write_file(pj(t, "victim/deep/f"), 100);
  CHECK(symlink(pj(t, "keep"), pj(t, "victim/link_to_keep")) == 0);
  /* быстрый путь по d_type: ссылка на файл и FIFO удаляются как записи, без перехода */
  CHECK(symlink(pj(t, "keep/important"), pj(t, "victim/deep/link_to_file")) == 0);
  CHECK(mkfifo(pj(t, "victim/deep/fifo"), 0644) == 0);
  CHECK(rm_tree(pj(t, "victim")) == 0);
  CHECK(access(pj(t, "victim"), F_OK) != 0);
  CHECK(access(pj(t, "keep/important"), F_OK) == 0);
  struct stat kst;
  CHECK(stat(pj(t, "keep/important"), &kst) == 0 && kst.st_size == 10);

  /* симлинк как корень удаления */
  CHECK(symlink(pj(t, "keep"), pj(t, "lnk")) == 0);
  CHECK(rm_tree(pj(t, "lnk")) == 0);
  CHECK(access(pj(t, "keep/important"), F_OK) == 0);

  /* симлинк с завершающим слешем: удаляется ссылка, цель цела */
  mk_dir(pj(t, "target"));
  mk_dir(pj(t, "target/sub"));
  write_file(pj(t, "target/sub/keep"), 1);
  CHECK(symlink(pj(t, "target"), pj(t, "slink")) == 0);
  char ts[4200];
  snprintf(ts, sizeof ts, "%s/slink//", t);
  CHECK(rm_tree(ts) == 0);
  CHECK(access(pj(t, "target/sub/keep"), F_OK) == 0);
  struct stat lst;
  CHECK(lstat(pj(t, "slink"), &lst) != 0);

  /* одиночный файл и отсутствующий путь */
  write_file(pj(t, "single"), 1);
  CHECK(rm_tree(pj(t, "single")) == 0);
  CHECK(rm_tree(pj(t, "missing")) == -ENOENT);

  /* ошибка в середине: остальное удаляется, возвращается первая ошибка */
  if (geteuid() != 0) {
    mk_dir(pj(t, "mix"));
    mk_dir(pj(t, "mix/locked"));
    write_file(pj(t, "mix/locked/x"), 1);
    write_file(pj(t, "mix/free"), 1);
    chmod(pj(t, "mix/locked"), 0500); /* читать можно, удалять внутри нельзя */
    CHECK(rm_tree(pj(t, "mix")) == -EACCES);
    CHECK(access(pj(t, "mix/free"), F_OK) != 0);
    chmod(pj(t, "mix/locked"), 0755);
  }

  /* последний компонент «.» / «..» — отказ -EINVAL, ничего не тронуто.
   * Только абсолютные пути внутри своего mkdtemp: «..» здесь — это t/outer, не t. */
  if (t[0] == '/') {
    mk_dir(pj(t, "outer"));
    mk_dir(pj(t, "outer/dots"));
    write_file(pj(t, "outer/dots/sentinel"), 10);
    write_file(pj(t, "outer/sibling"), 10);
    const char *tails[] = {"outer/dots/.",   "outer/dots/..",          "outer/dots/./",
                           "outer/dots/..//", "outer/dots/sentinel/..", "outer/."};
    for (size_t i = 0; i < sizeof tails / sizeof *tails; i++) {
      char dp[4200];
      snprintf(dp, sizeof dp, "%s/%s", t, tails[i]);
      int r = rm_tree(dp);
      if (r != -EINVAL) {
        fprintf(stderr, "dot path not refused (%d): %s\n", r, dp);
        t_fail++;
      }
      CHECK(access(pj(t, "outer/dots/sentinel"), F_OK) == 0);
      CHECK(access(pj(t, "outer/sibling"), F_OK) == 0);
    }
    CHECK(rm_tree(pj(t, "outer")) == 0);
  } else {
    CHECK(!"mk_tmp returned a relative path; dot-path cases not run");
  }

  /* другая ФС: реальные tmpfs в собственном mount namespace */
  cross_fs(t);

  CHECK(rm_tree(t) == 0);
  CHECK(access(t, F_OK) != 0);
  TEST_END();
}
