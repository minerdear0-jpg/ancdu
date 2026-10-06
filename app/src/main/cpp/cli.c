#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#include "arena.h"
#include "csr.h"
#include "helper_proto.h"
#include "rmtree.h"
#include "scan.h"

enum { MODE_SUMMARY, MODE_DUMP, MODE_MEMFD, MODE_RM };

static int usage(void) {
  fputs("usage: libancdu_scan.so (--summary|--dump) --root DIR [--cross-fs] [--threads N]"
        " [--watch-stdin]\n"
        "       libancdu_scan.so --memfd PATH [--root DIR] [--cross-fs] [--threads N]"
        " [--watch-stdin]\n"
        "       libancdu_scan.so --rm PATH\n",
        stderr);
  return 2;
}

static const char *state_name(int st) {
  switch (st) {
    case ST_DONE: return "done";
    case ST_FULL: return "full";
    case ST_CANCELLED: return "cancelled";
    default: return "failed";
  }
}

static int exit_code(int st) {
  switch (st) {
    case ST_DONE: return 0;
    case ST_FULL: return 3;
    case ST_CANCELLED: return 4;
    default: return 1;
  }
}

typedef struct {
  arena *a;
  _Atomic int stop;
} progress_job;

static void *progress(void *p) {
  progress_job *j = p;
  char path[512];
  while (!atomic_load(&j->stop)) {
    usleep(100000);
    arena_cur_path(j->a, path, sizeof path);
    for (char *c = path; *c; c++)
      if (*c == '\n') *c = '?';
    fprintf(stderr, "P %llu %llu %llu %s\n",
            (unsigned long long)atomic_load(&j->a->h->files),
            (unsigned long long)atomic_load(&j->a->h->bytes),
            (unsigned long long)atomic_load(&j->a->h->errors), path);
  }
  return NULL;
}

static void print_summary(const arena *a, int st) {
  printf("state %s\n", state_name(st));
  uint64_t n = atomic_load(&a->h->count);
  if (n == 0) return;
  printf("disk %llu\napparent %llu\nitems %llu\nerrors %llu\nms %lld\n",
         (unsigned long long)a->disk[0], (unsigned long long)a->apparent[0],
         (unsigned long long)a->items[0],
         (unsigned long long)atomic_load(&a->h->errors),
         (long long)((atomic_load(&a->h->finished_ns) - a->h->started_ns) / 1000000));
  uint32_t c = a->child_count[0], show = c < 10 ? c : 10;
  for (uint32_t j = 0; j < show; j++) {
    uint32_t x = a->order[a->child_start[0] + j];
    printf("top %llu %s\n", (unsigned long long)a->disk[x], arena_name(a, x));
  }
}

/* Приложение закрывает наш stdin, чтобы отменить скан (работает и через su). */
static void *watch_stdin(void *p) {
  arena *a = p;
  char buf[64];
  while (read(STDIN_FILENO, buf, sizeof buf) > 0) {
  }
  atomic_store(&a->h->cancel, ANCDU_CANCEL_USER);
  return NULL;
}

int main(int argc, char **argv) {
  const char *root = NULL, *memfd = NULL;
  int mode = -1, one_fs = 1, threads = 0;
  int watch = 0;
  for (int i = 1; i < argc; i++) {
    if (!strcmp(argv[i], "--summary")) mode = MODE_SUMMARY;
    else if (!strcmp(argv[i], "--dump")) mode = MODE_DUMP;
    else if (!strcmp(argv[i], "--memfd") && i + 1 < argc) { mode = MODE_MEMFD; memfd = argv[++i]; }
    else if (!strcmp(argv[i], "--root") && i + 1 < argc) root = argv[++i];
    else if (!strcmp(argv[i], "--cross-fs")) one_fs = 0;
    else if (!strcmp(argv[i], "--threads") && i + 1 < argc) threads = atoi(argv[++i]);
    else if (!strcmp(argv[i], "--rm") && i + 1 < argc) { mode = MODE_RM; root = argv[++i]; }
    else if (!strcmp(argv[i], "--watch-stdin")) watch = 1;
    else return usage();
  }
  if (mode < 0 || (mode != MODE_MEMFD && !root)) return usage();
  if (mode == MODE_RM) {
    int r = rm_tree(root);
    struct stat st;
    if (r == 0 || (lstat(root, &st) != 0 && errno == ENOENT)) return 0;
    fprintf(stderr, "rm: %s\n", strerror(-r));
    return 5;
  }

  arena a;
  if (mode == MODE_MEMFD) {
    int fd = open(memfd, O_RDWR | O_CLOEXEC);
    if (fd < 0) {
      fprintf(stderr, ANCDU_MEMFD_UNAVAILABLE ": open %s: %s\n", memfd, strerror(errno));
      return 1;
    }
    struct stat st;
    if (fstat(fd, &st) != 0) {
      fprintf(stderr, ANCDU_MEMFD_UNAVAILABLE ": fstat: %s\n", strerror(errno));
      return 1;
    }
    void *base = mmap(NULL, (size_t)st.st_size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    int map_errno = errno;
    close(fd);
    if (base == MAP_FAILED) {
      fprintf(stderr, ANCDU_MEMFD_UNAVAILABLE ": mmap: %s\n", strerror(map_errno));
      return 1;
    }
    if (arena_attach(&a, base, (size_t)st.st_size) != 0 || atomic_load(&a.h->count) != 0) {
      fputs("memfd: bad or non-empty arena\n", stderr);
      return 2;
    }
    if (root && strcmp(root, a.h->root_path) != 0) {
      fputs("memfd: --root does not match arena root\n", stderr);
      return 2;
    }
    root = a.h->root_path;
  } else {
    uint64_t cap = arena_cap_hint(root);
    int r = arena_alloc_anon(&a, cap, arena_names_hint(cap), root, SRC_SCAN);
    if (r) { fprintf(stderr, "arena: %s\n", strerror(-r)); return 1; }
  }

  scan_opts o = {.one_fs = one_fs, .threads = threads > 0 ? threads : scan_default_threads(root)};
  progress_job pj = {.a = &a};
  pthread_t pth;
  int have_progress = mode == MODE_DUMP && pthread_create(&pth, NULL, progress, &pj) == 0;

  pthread_t wth;
  if (watch && pthread_create(&wth, NULL, watch_stdin, &a) == 0) pthread_detach(wth);
  int st = scan_run(&a, &o);
  if (st == ST_DONE || st == ST_FULL) post_process(&a, o.threads);
  atomic_store(&a.h->finished_ns, ancdu_now_ns());
  atomic_store_explicit(&a.h->state, (uint32_t)st, memory_order_release);

  if (have_progress) {
    atomic_store(&pj.stop, 1);
    pthread_join(pth, NULL);
  }
  if (mode == MODE_SUMMARY) print_summary(&a, st);
  if (mode == MODE_DUMP) {
    int r = arena_write(&a, STDOUT_FILENO);
    if (r) { fprintf(stderr, "dump: %s\n", strerror(-r)); return 1; }
  }
  return exit_code(st);
}
