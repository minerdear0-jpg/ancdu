#include "rmtree.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

#define RM_MAX_DEPTH 4096

static int rm_at(int dfd, const char *name, int depth) {
  struct stat st;
  if (fstatat(dfd, name, &st, AT_SYMLINK_NOFOLLOW) != 0) return -errno;
  if (!S_ISDIR(st.st_mode)) return unlinkat(dfd, name, 0) ? -errno : 0;
  if (depth > RM_MAX_DEPTH) return -ELOOP;
  int fd = openat(dfd, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  if (fd < 0) return -errno;
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
    int r = rm_at(fd, n, depth + 1);
    if (r && !err) err = r;
  }
  closedir(d);
  if (unlinkat(dfd, name, AT_REMOVEDIR) != 0 && !err) err = -errno;
  return err;
}

int rm_tree(const char *path) { return rm_at(AT_FDCWD, path, 0); }
