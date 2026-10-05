#include "arena.h"
#include "csr.h"
#include "test.h"

#include <errno.h>
#include <pthread.h>

static void build(arena *a) {
  CHECK(arena_alloc_anon(a, 1000, 1 << 20, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  arena_new_node(a, &ck, ANCDU_NONE, "", 0, F_DIR);
  for (int i = 1; i < 300; i++) {
    char nm[32];
    snprintf(nm, sizeof nm, "n%d", i);
    uint32_t p = (uint32_t)(i < 10 ? 0 : (i % 9) + 1); /* 1..9 — каталоги */
    uint32_t x = arena_new_node(a, &ck, p, nm, strlen(nm), i < 10 ? F_DIR : 0);
    a->disk[x] = (uint64_t)i * 4096;
    a->apparent[x] = (uint64_t)i * 1000;
  }
  post_process(a, 2);
  atomic_store(&a->h->state, ST_DONE);
}

static void same(const arena *x, const arena *y) {
  uint64_t n = atomic_load(&x->h->count);
  CHECK_EQ_U(atomic_load(&y->h->count), n);
  CHECK_EQ_U(atomic_load(&y->h->state), ST_DONE);
  CHECK(strcmp(x->h->root_path, y->h->root_path) == 0);
  for (uint64_t i = 0; i < n; i++) {
    CHECK_EQ_U(y->parent[i], x->parent[i]);
    CHECK_EQ_U(y->disk[i], x->disk[i]);
    CHECK_EQ_U(y->apparent[i], x->apparent[i]);
    CHECK_EQ_U(y->items[i], x->items[i]);
    CHECK_EQ_U(y->child_start[i], x->child_start[i]);
    CHECK_EQ_U(y->child_count[i], x->child_count[i]);
    CHECK(strcmp(arena_name(y, (uint32_t)i), arena_name(x, (uint32_t)i)) == 0);
    if (i + 1 < n) CHECK_EQ_U(y->order[i], x->order[i]);
  }
}

typedef struct { const arena *a; int fd; } wjob;
static void *writer(void *p) {
  wjob *j = p;
  arena_write(j->a, j->fd);
  close(j->fd);
  return NULL;
}

int main(void) {
  arena a, b;
  build(&a);

  /* через pipe: проверяет частичные чтения */
  int pfd[2];
  CHECK(pipe(pfd) == 0);
  wjob j = {&a, pfd[1]};
  pthread_t th;
  pthread_create(&th, NULL, writer, &j);
  CHECK(arena_read_stream(&b, pfd[0]) == 0);
  pthread_join(th, NULL);
  close(pfd[0]);
  same(&a, &b);
  CHECK(b.size < a.size); /* компактный: ёмкости = фактические */
  arena_unmap(&b);

  /* через файл кэша */
  const char *T = mk_tmp();
  const char *path = pj(T, "last.ancdu");
  CHECK(arena_save_file(&a, path) == 0);
  CHECK(access(pj(T, "last.ancdu.tmp"), F_OK) != 0);
  CHECK(arena_open_file(&b, path) == 0);
  same(&a, &b);
  CHECK(csr_remove(&b, 5) == 0); /* MAP_PRIVATE: правки не трогают файл */
  arena_unmap(&b);
  CHECK(arena_open_file(&b, path) == 0);
  CHECK((b.flags[5] & F_DELETED) == 0);
  arena_unmap(&b);

  /* обрезанный файл */
  struct stat st;
  stat(path, &st);
  CHECK(truncate(path, st.st_size / 2) == 0);
  CHECK(arena_open_file(&b, path) == -EINVAL);
  int fd = open(path, O_RDONLY);
  CHECK(arena_read_stream(&b, fd) == -EIO);
  close(fd);

  /* испорченный родитель */
  CHECK(arena_save_file(&a, path) == 0);
  fd = open(path, O_RDWR);
  uint32_t bad = 250;
  /* parent[3] в компактном файле: смещение массива parent = заголовок */
  CHECK(pwrite(fd, &bad, 4, ANCDU_HDR_SIZE + 3 * 4) == 4);
  close(fd);
  CHECK(arena_open_file(&b, path) == -EINVAL);

  /* мусор вместо заголовка */
  write_file(path, 10000);
  CHECK(arena_open_file(&b, path) == -EINVAL);
  CHECK(arena_open_file(&b, pj(T, "missing")) == -ENOENT);

  unlink(path);
  rmdir(T);
  arena_unmap(&a);
  TEST_END();
}
