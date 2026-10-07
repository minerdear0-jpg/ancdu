/* Родитель вершины удаления (rm_open_parent, см. rmtree.h): openat2 без ссылок, обход по
 * компонентам от «/», проба seccomp. */
#include "rmtree.h"

#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdatomic.h>
#include <stdint.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef SYS_openat2
#define SYS_openat2 437
#endif
#define RM_RESOLVE_NO_MAGICLINKS 0x02
#define RM_RESOLVE_NO_SYMLINKS 0x04

struct rm_open_how {
  uint64_t flags, mode, resolve;
};

/* openat2: 0 — не проверяли, 1 — работает, -1 — нет (старое ядро или seccomp-фильтр). */
static _Atomic int openat2_state;

/* Под seccomp-фильтром (процесс приложения Android) неизвестный фильтру syscall — это SIGSYS,
 * а не ENOSYS: openat2 пробуем только без фильтра (хелпер под su, хост). */
static int seccomp_filtered(void) {
  int fd = open("/proc/self/status", O_RDONLY | O_CLOEXEC);
  if (fd < 0) return 1;
  char buf[4096];
  ssize_t n = read(fd, buf, sizeof buf - 1);
  close(fd);
  if (n <= 0) return 1;
  buf[n] = 0;
  const char *p = strstr(buf, "\nSeccomp:");
  if (!p) return 0; /* ядро без seccomp */
  p += 9;
  while (*p == ' ' || *p == '\t') p++;
  return *p != '0';
}

/* Родитель целиком через openat2(RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS); -ENOSYS —
 * openat2 нет, вызывающий идёт по компонентам. */
static int open_parent_openat2(const char *parent) {
  int st = atomic_load(&openat2_state);
  if (st < 0) return -ENOSYS;
  if (st == 0 && seccomp_filtered()) {
    atomic_store(&openat2_state, -1);
    return -ENOSYS;
  }
  struct rm_open_how how = {O_PATH | O_DIRECTORY | O_CLOEXEC, 0,
                            RM_RESOLVE_NO_SYMLINKS | RM_RESOLVE_NO_MAGICLINKS};
  long fd = syscall(SYS_openat2, AT_FDCWD, parent, &how, sizeof how);
  if (fd >= 0) {
    atomic_store(&openat2_state, 1);
    return (int)fd;
  }
  int e = errno;
  if (e == ENOSYS || e == EPERM || e == E2BIG || (e == EINVAL && st == 0)) {
    atomic_store(&openat2_state, -1);
    return -ENOSYS;
  }
  atomic_store(&openat2_state, 1);
  return -e;
}

/* Родитель по компонентам от «/»: openat(O_PATH|O_NOFOLLOW|O_DIRECTORY) каждого
 * относительно fd предыдущего. Ссылка (с O_PATH|O_NOFOLLOW она открылась бы сама, с
 * O_DIRECTORY — ENOTDIR) — -ELOOP. */
static int open_parent_chain(const char *parent) {
  int fd = open("/", O_PATH | O_DIRECTORY | O_CLOEXEC);
  if (fd < 0) return -errno;
  for (const char *c = parent + 1; *c;) {
    const char *e = strchr(c, '/');
    size_t k = e ? (size_t)(e - c) : strlen(c);
    char comp[NAME_MAX + 1];
    if (k > NAME_MAX) {
      close(fd);
      return -ENAMETOOLONG;
    }
    memcpy(comp, c, k);
    comp[k] = 0;
    int nfd = openat(fd, comp, O_PATH | O_NOFOLLOW | O_DIRECTORY | O_CLOEXEC);
    if (nfd < 0) {
      int err = errno;
      struct stat st;
      if ((err == ENOTDIR || err == ELOOP) && fstatat(fd, comp, &st, AT_SYMLINK_NOFOLLOW) == 0 &&
          S_ISLNK(st.st_mode))
        err = ELOOP;
      close(fd);
      return -err;
    }
    close(fd);
    fd = nfd;
    if (!e) break;
    c = e + 1;
  }
  return fd;
}

int rm_open_parent(const char *path, int nofollow, char *last, size_t cap) {
  char buf[PATH_MAX];
  int v = rm_tree_target(path, buf, sizeof buf);
  if (v) return v;
  if (buf[0] != '/') return -EINVAL; /* относительный путь зависел бы от cwd */
  char *slash = strrchr(buf, '/');
  size_t ln = strlen(slash + 1);
  if (ln > NAME_MAX) return -ENAMETOOLONG;
  if (ln + 1 > cap) return -ENAMETOOLONG;
  memcpy(last, slash + 1, ln + 1);
  if (slash == buf) slash[1] = 0; /* родитель — «/» */
  else *slash = 0;
  if (!nofollow) {
    int fd = open(buf, O_PATH | O_DIRECTORY | O_CLOEXEC);
    return fd < 0 ? -errno : fd;
  }
  /* Без ссылок — и без «.», «..», пустых компонентов: такой путь не нормализован (их нет в
   * путях дерева), «..» вывел бы из проверенного префикса. */
  for (const char *c = buf + 1; *c;) {
    const char *e = strchr(c, '/');
    size_t k = e ? (size_t)(e - c) : strlen(c);
    if (k == 0 || (k == 1 && c[0] == '.') || (k == 2 && c[0] == '.' && c[1] == '.')) return -ELOOP;
    if (!e) break;
    c = e + 1;
  }
  int fd = open_parent_openat2(buf);
  return fd == -ENOSYS ? open_parent_chain(buf) : fd;
}
