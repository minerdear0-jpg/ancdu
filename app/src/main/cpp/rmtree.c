#include "rmtree.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define RM_MAX_DEPTH 4096

/* dev — устройство вершины: всё, что лежит на другом (точка монтирования, bind-файл),
 * не трогается и даёт -EXDEV, то есть частичное удаление. */
static int rm_at(int dfd, const char *name, dev_t dev, int depth) {
  struct stat st;
  if (fstatat(dfd, name, &st, AT_SYMLINK_NOFOLLOW) != 0) return -errno;
  if (st.st_dev != dev) return -EXDEV;
  if (!S_ISDIR(st.st_mode)) return unlinkat(dfd, name, 0) ? -errno : 0;
  if (depth > RM_MAX_DEPTH) return -ELOOP;
  int fd = openat(dfd, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  if (fd < 0) return -errno;
  /* Между fstatat и openat имя могли подменить: открыт должен быть тот же каталог,
   * иначе он пропускается, как чужая ФС (частичное удаление). */
  struct stat ost;
  if (fstat(fd, &ost) != 0 || ost.st_dev != st.st_dev || ost.st_ino != st.st_ino) {
    close(fd);
    return -EXDEV;
  }
  DIR *d = fdopendir(fd);
  if (!d) {
    int e = -errno;
    close(fd);
    return e;
  }
  int err = 0;
  struct dirent *e;
  while ((e = readdir(d))) {
    const char *n = e->d_name;
    if (n[0] == '.' && (n[1] == 0 || (n[1] == '.' && n[2] == 0))) continue;
    int r = rm_at(fd, n, dev, depth + 1);
    if (r && !err) err = r;
  }
  closedir(d);
  if (unlinkat(dfd, name, AT_REMOVEDIR) != 0 && !err) err = -errno;
  return err;
}

int rm_tree_target(const char *path, char *buf, size_t cap) {
  /* "link/" разыменовывается даже с AT_SYMLINK_NOFOLLOW/O_NOFOLLOW —
   * срезаем завершающие слеши, чтобы удалялась сама ссылка. */
  size_t n = strlen(path);
  while (n > 1 && path[n - 1] == '/') n--;
  if (n >= cap) return -ENAMETOOLONG;
  memcpy(buf, path, n);
  buf[n] = 0;
  /* Последний компонент «.» или «..» обходит проверку точки монтирования в rm_tree:
   * у «/mnt/point/.» родитель — сама «/mnt/point», устройство то же. Пустой («/» или
   * одни слеши, «») — корень ФС: родителя нет, удалять нечего и нельзя. */
  const char *slash = strrchr(buf, '/');
  const char *last = slash ? slash + 1 : buf;
  if (!*last || strcmp(last, ".") == 0 || strcmp(last, "..") == 0) return -EINVAL;
  return 0;
}

int rm_tree(const char *path) {
  /* Отказ до любых lstat/open/unlink. */
  char buf[PATH_MAX];
  int v = rm_tree_target(path, buf, sizeof buf);
  if (v) return v;
  const char *slash = strrchr(buf, '/');
  struct stat st, pst;
  if (lstat(buf, &st) != 0) return -errno;
  /* Вершина — точка монтирования (или bind-файл): её устройство отличается от родителя. */
  char parent[PATH_MAX];
  if (!slash) {
    strcpy(parent, ".");
  } else if (slash == buf) {
    strcpy(parent, "/");
  } else {
    memcpy(parent, buf, (size_t)(slash - buf));
    parent[slash - buf] = 0;
  }
  if (stat(parent, &pst) != 0) return -errno;
  if (st.st_dev != pst.st_dev) return -EXDEV;
  return rm_at(AT_FDCWD, buf, st.st_dev, 0);
}
