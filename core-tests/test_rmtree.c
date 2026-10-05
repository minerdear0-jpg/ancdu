#include "rmtree.h"
#include "test.h"

#include <errno.h>

int main(void) {
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
  CHECK(rm_tree(pj(t, "victim")) == 0);
  CHECK(access(pj(t, "victim"), F_OK) != 0);
  CHECK(access(pj(t, "keep/important"), F_OK) == 0);

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

  CHECK(rm_tree(t) == 0);
  CHECK(access(t, F_OK) != 0);
  TEST_END();
}
