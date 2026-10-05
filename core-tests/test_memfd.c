#include "arena.h"
#include "test.h"

#include <errno.h>
#include <sys/mman.h>
#include <sys/wait.h>

/* Протокол memfd на хосте: родитель форматирует, хелпер сканирует. */
int main(void) {
  const char *T = mk_tmp();
  char t[4096];
  snprintf(t, sizeof t, "%s", T);
  mk_dir(pj(t, "a"));
  write_file(pj(t, "a/f"), 12345);
  write_file(pj(t, "g"), 777);

  uint64_t cap = 1 << 16, names = 1 << 22;
  size_t size = arena_bytes(cap, names);
  int fd = memfd_create("ancdu-test", 0);
  CHECK(fd >= 0);
  CHECK(ftruncate(fd, (off_t)size) == 0);
  void *base = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  CHECK(base != MAP_FAILED);
  arena_format(base, cap, names, t, SRC_SCAN);

  char fdpath[64];
  snprintf(fdpath, sizeof fdpath, "/proc/%d/fd/%d", getpid(), fd);
  pid_t pid = fork();
  if (pid == 0) {
    execl(ANCDU_CLI, ANCDU_CLI, "--memfd", fdpath, "--root", t, (char *)NULL);
    _exit(127);
  }
  int ws;
  waitpid(pid, &ws, 0);
  CHECK(WIFEXITED(ws) && WEXITSTATUS(ws) == 0);

  arena a;
  CHECK(arena_attach(&a, base, size) == 0);
  CHECK_EQ_U(atomic_load_explicit(&a.h->state, memory_order_acquire), ST_DONE);
  CHECK_EQ_U(atomic_load(&a.h->count), 4);           /* корень, a, a/f, g */
  CHECK(arena_validate(&a) == 0);
  CHECK(a.apparent[0] >= 12345 + 777);
  CHECK_EQ_U(a.child_count[0], 2);

  /* несовпадающий --root отвергается */
  arena_format(base, cap, names, t, SRC_SCAN);
  pid = fork();
  if (pid == 0) {
    execl(ANCDU_CLI, ANCDU_CLI, "--memfd", fdpath, "--root", "/other", (char *)NULL);
    _exit(127);
  }
  waitpid(pid, &ws, 0);
  CHECK(WIFEXITED(ws) && WEXITSTATUS(ws) == 2);

  munmap(base, size);
  close(fd);
  unlink(pj(t, "a/f"));
  unlink(pj(t, "g"));
  rmdir(pj(t, "a"));
  rmdir(t);
  TEST_END();
}
