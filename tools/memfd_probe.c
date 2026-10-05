/* Спайк: процесс без root создаёт memfd-арену, хелпер под su сканирует в неё. */
#include <stdio.h>
#include <stdlib.h>
#include <sys/mman.h>
#include <unistd.h>

#include "arena.h"

int main(int argc, char **argv) {
  if (argc < 3) {
    fprintf(stderr, "usage: %s HELPER ROOT\n", argv[0]);
    return 2;
  }
  const char *helper = argv[1], *root = argv[2];
  uint64_t cap = arena_cap_hint(root), names = arena_names_hint(cap);
  size_t size = arena_bytes(cap, names);
  int fd = memfd_create("ancdu", 0);
  if (fd < 0 || ftruncate(fd, (off_t)size) != 0) { perror("memfd"); return 1; }
  void *base = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (base == MAP_FAILED) { perror("mmap"); return 1; }
  arena_format(base, cap, names, root, SRC_SCAN);
  char cmd[4096];
  snprintf(cmd, sizeof cmd, "su -c '%s --memfd /proc/%d/fd/%d --root %s'", helper,
           getpid(), fd, root);
  int64_t t0 = ancdu_now_ns();
  int rc = system(cmd);
  int64_t t1 = ancdu_now_ns();
  arena a;
  if (arena_attach(&a, base, size) != 0) { puts("attach failed"); return 1; }
  uint32_t st = atomic_load_explicit(&a.h->state, memory_order_acquire);
  uint64_t n = atomic_load(&a.h->count);
  printf("rc=%d state=%u count=%llu disk=%llu ms=%lld\n", rc, st, (unsigned long long)n,
         (unsigned long long)(n ? a.disk[0] : 0), (long long)((t1 - t0) / 1000000));
  return st == ST_DONE ? 0 : 1;
}
