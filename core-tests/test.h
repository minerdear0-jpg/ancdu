/* core-tests/test.h — минимальный набор проверок и помощников фикстур. */
#pragma once
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <unistd.h>

static int t_fail;

#define CHECK(c)                                                            \
  do {                                                                      \
    if (!(c)) {                                                             \
      fprintf(stderr, "%s:%d: CHECK(%s) failed\n", __FILE__, __LINE__, #c); \
      t_fail++;                                                             \
    }                                                                       \
  } while (0)

#define CHECK_EQ_U(a, b)                                                     \
  do {                                                                       \
    unsigned long long _a = (unsigned long long)(a), _b = (unsigned long long)(b); \
    if (_a != _b) {                                                          \
      fprintf(stderr, "%s:%d: %s == %llu, expected %llu\n", __FILE__,        \
              __LINE__, #a, _a, _b);                                         \
      t_fail++;                                                              \
    }                                                                        \
  } while (0)

#define TEST_END()                                          \
  do {                                                      \
    if (t_fail) {                                           \
      fprintf(stderr, "%d failure(s)\n", t_fail);           \
      return 1;                                             \
    }                                                       \
    puts("OK");                                             \
    return 0;                                               \
  } while (0)

/* Склеивает a/b во внутренний кольцевой буфер (8 слотов). */
static const char *pj(const char *a, const char *b) {
  static char ring[8][8192];
  static int k;
  char *o = ring[k++ & 7];
  snprintf(o, 8192, "%s/%s", a, b);
  return o;
}

static void write_file(const char *path, size_t n) {
  int fd = open(path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
  if (fd < 0) { perror(path); exit(2); }
  char buf[4096];
  memset(buf, 'x', sizeof buf);
  while (n) {
    size_t k = n < sizeof buf ? n : sizeof buf;
    if (write(fd, buf, k) != (ssize_t)k) { perror("write"); exit(2); }
    n -= k;
  }
  close(fd);
}

static void mk_dir(const char *path) {
  if (mkdir(path, 0755) != 0) { perror(path); exit(2); }
}

/* Свежий каталог фикстуры. Только абсолютный путь: тесты удаляют внутри него,
 * и относительный путь зависел бы от cwd. */
static char *mk_tmp(void) {
  const char *base = getenv("TMPDIR");
  if (!base) base = "/tmp";
  if (base[0] != '/') {
    fprintf(stderr, "mk_tmp: TMPDIR must be an absolute path, got \"%s\"\n", base);
    exit(2);
  }
  static char p[4096];
  snprintf(p, sizeof p, "%s/ancdu-test-XXXXXX", base);
  if (!mkdtemp(p)) { perror("mkdtemp"); exit(2); }
  if (p[0] != '/' || strncmp(p, base, strlen(base)) != 0) {
    fprintf(stderr, "mk_tmp: created \"%s\" outside base \"%s\"\n", p, base);
    exit(2);
  }
  return p;
}

#include "rmtree.h"
static void rm_dir_tree_for_tests(const char *p) {
  if (rm_tree(p) != 0) fprintf(stderr, "cleanup failed: %s\n", p);
}
