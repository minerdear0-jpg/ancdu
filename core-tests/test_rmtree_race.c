/* Подмена каталога между fstatat и openat в rm_at: openat перехватывается на этапе
 * компиляции (rmtree.c включается сюда с #define openat), и в момент открытия «d»
 * исходный каталог уводится в сторону, а на его место ставится новый с файлом.
 * rm_tree должен заметить подмену (st_dev/st_ino) и не трогать новый каталог.
 * Только абсолютные пути внутри собственного mkdtemp. */
#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static char swap_from[4200], swap_aside[4200], swap_new_file[4200];
static int swapped;

static int race_openat(int dfd, const char *name, int flags, ...) {
  if (!swapped && strcmp(name, "d") == 0) {
    swapped = 1;
    if (rename(swap_from, swap_aside) == 0 && mkdir(swap_from, 0755) == 0) {
      int fd = open(swap_new_file, O_WRONLY | O_CREAT | O_EXCL | O_CLOEXEC, 0644);
      if (fd >= 0) close(fd);
    }
  }
  return openat(dfd, name, flags);
}

#define openat race_openat
#include "rmtree.c"
#undef openat

#include "test.h"

int main(void) {
  const char *T = mk_tmp();
  char t[4096];
  snprintf(t, sizeof t, "%s", T);
  if (t[0] != '/') {
    fprintf(stderr, "mk_tmp returned a relative path: %s\n", t);
    return 1;
  }

  mk_dir(pj(t, "x"));
  mk_dir(pj(t, "x/d"));
  write_file(pj(t, "x/d/orig"), 10);
  snprintf(swap_from, sizeof swap_from, "%s/x/d", t);
  snprintf(swap_aside, sizeof swap_aside, "%s/aside", t);
  snprintf(swap_new_file, sizeof swap_new_file, "%s/x/d/swapped_in", t);

  CHECK(rm_tree(pj(t, "x")) == -EXDEV);
  CHECK(swapped == 1);
  /* подставленный каталог не тронут, увезённый — тоже */
  CHECK(access(pj(t, "x/d/swapped_in"), F_OK) == 0);
  CHECK(access(pj(t, "aside/orig"), F_OK) == 0);

  CHECK(rm_tree(t) == 0);
  CHECK(access(t, F_OK) != 0);
  TEST_END();
}
