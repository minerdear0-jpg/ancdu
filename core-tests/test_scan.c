#include "arena.h"
#include "csr.h"
#include "rmtree.h"
#include "scan.h"
#include "test.h"

#include <errno.h>
#include <ftw.h>
#include <pthread.h>

/* ---------- эталон: nftw без следования симлинкам ---------- */
static uint64_t ref_disk, ref_app, ref_n;
static struct { dev_t d; ino_t i; } ref_seen[1024];
static int ref_nseen;

static int ref_cb(const char *p, const struct stat *st, int fl, struct FTW *f) {
  (void)p; (void)f;
  ref_n++;
  if (fl == FTW_NS) return 0;
  if (!S_ISDIR(st->st_mode) && st->st_nlink > 1) {
    for (int k = 0; k < ref_nseen; k++)
      if (ref_seen[k].d == st->st_dev && ref_seen[k].i == st->st_ino) return 0;
    ref_seen[ref_nseen].d = st->st_dev;
    ref_seen[ref_nseen++].i = st->st_ino;
  }
  ref_disk += (uint64_t)st->st_blocks * 512;
  ref_app += (uint64_t)st->st_size;
  return 0;
}

static void reference(const char *root) {
  ref_disk = ref_app = ref_n = 0;
  ref_nseen = 0;
  nftw(root, ref_cb, 64, FTW_PHYS | FTW_MOUNT);
}

/* ---------- фикстура ---------- */
static const char *fixture(void) {
  const char *T = mk_tmp();
  mk_dir(pj(T, "d1"));
  write_file(pj(T, "d1/f1"), 1000);
  write_file(pj(T, "d1/f2"), 5000);
  mk_dir(pj(T, "d1/sub"));
  write_file(pj(T, "d1/sub/f3"), 1);
  mk_dir(pj(T, "d2"));
  write_file(pj(T, "top.bin"), 100000);
  CHECK(link(pj(T, "d1/f2"), pj(T, "d2/hl")) == 0);
  CHECK(symlink("d1", pj(T, "lnk")) == 0);
  write_file(pj(T, "\xff\xfe.bin"), 7);                 /* невалидный UTF-8 */
  write_file(pj(T, "new\nline"), 3);                    /* перевод строки в имени */
  mk_dir(pj(T, "noperm"));
  write_file(pj(T, "noperm/x"), 10);
  chmod(pj(T, "noperm"), 0);
  return T;
}

static uint32_t find(const arena *a, const char *name) {
  uint64_t n = atomic_load(&a->h->count);
  for (uint64_t i = 0; i < n; i++)
    if (strcmp(arena_name(a, (uint32_t)i), name) == 0) return (uint32_t)i;
  return ANCDU_NONE;
}

static void scan_new(arena *a, const char *root, int threads, int *st) {
  CHECK(arena_alloc_anon(a, 1 << 16, 1 << 22, root, SRC_SCAN) == 0);
  scan_opts o = {.one_fs = 1, .threads = threads};
  *st = scan_run(a, &o);
}

static void test_matches_reference(const char *T, int threads) {
  arena a;
  int st;
  scan_new(&a, T, threads, &st);
  CHECK_EQ_U(st, ST_DONE);
  post_process(&a, threads);
  reference(T);
  CHECK_EQ_U(atomic_load(&a.h->count), ref_n);
  CHECK_EQ_U(a.disk[0], ref_disk);
  CHECK_EQ_U(a.apparent[0], ref_app);
  CHECK_EQ_U(a.items[0], ref_n);
  CHECK(arena_validate(&a) == 0);

  /* хардлинк учтён один раз */
  uint32_t f2 = find(&a, "f2"), hl = find(&a, "hl");
  CHECK(((a.flags[f2] | a.flags[hl]) & F_HLDUP) != 0);
  CHECK(((a.flags[f2] & a.flags[hl]) & F_HLDUP) == 0);

  /* симлинк — узел, не каталог */
  uint32_t ln = find(&a, "lnk");
  CHECK(a.flags[ln] & F_SYMLINK);
  CHECK_EQ_U(a.child_count[ln], 0);

  /* нечитаемый каталог */
  if (geteuid() != 0) {
    CHECK(a.flags[find(&a, "noperm")] & F_ERR);
    CHECK(atomic_load(&a.h->errors) >= 1);
  }

  /* байты имён и пути сохраняются как есть */
  char buf[8192];
  uint32_t u = find(&a, "\xff\xfe.bin");
  CHECK(u != ANCDU_NONE);
  CHECK(arena_path(&a, u, buf, sizeof buf) > 0);
  CHECK(strcmp(buf, pj(T, "\xff\xfe.bin")) == 0);
  CHECK(find(&a, "new\nline") != ANCDU_NONE);
  CHECK(arena_path(&a, find(&a, "f3"), buf, sizeof buf) > 0);
  CHECK(strcmp(buf, pj(T, "d1/sub/f3")) == 0);

  /* живые итоги: сумма слотов + собственный размер корня = итог корня */
  uint64_t live = 0;
  for (unsigned s = 1; s < ANCDU_LIVE_SLOTS; s++) live += atomic_load(&a.h->live_disk[s]);
  struct stat rs;
  lstat(T, &rs);
  CHECK_EQ_U(live + (uint64_t)rs.st_blocks * 512, a.disk[0]);
  CHECK_EQ_U(atomic_load(&a.h->live_count), a.child_count[0]);
  arena_unmap(&a);
}

static void test_edge_states(const char *T) {
  arena a;
  int st;
  /* отмена до старта */
  CHECK(arena_alloc_anon(&a, 1 << 16, 1 << 22, T, SRC_SCAN) == 0);
  atomic_store(&a.h->cancel, ANCDU_CANCEL_USER);
  scan_opts o = {1, 2};
  CHECK_EQ_U(scan_run(&a, &o), ST_CANCELLED);
  arena_unmap(&a);
  /* переполнение */
  CHECK(arena_alloc_anon(&a, 5, 1 << 20, T, SRC_SCAN) == 0);
  CHECK_EQ_U(scan_run(&a, &o), ST_FULL);
  CHECK_EQ_U(atomic_load(&a.h->count), 5);
  arena_unmap(&a);
  /* корень не существует / не каталог */
  scan_new(&a, pj(T, "missing"), 2, &st);
  CHECK_EQ_U(st, ST_FAILED);
  arena_unmap(&a);
  scan_new(&a, pj(T, "top.bin"), 2, &st);
  CHECK_EQ_U(st, ST_FAILED);
  arena_unmap(&a);
}

/* Путь длиннее PATH_MAX: open() даёт ENAMETOOLONG, скан не падает. */
static void test_long_path(void) {
  const char *T = mk_tmp();
  char name[201];
  memset(name, 'n', 200);
  name[200] = 0;
  int fd = open(T, O_RDONLY | O_DIRECTORY);
  for (int i = 0; i < 25; i++) { /* 25 * 201 > 4096 */
    CHECK(mkdirat(fd, name, 0755) == 0);
    int nfd = openat(fd, name, O_RDONLY | O_DIRECTORY);
    close(fd);
    fd = nfd;
  }
  close(fd);
  arena a;
  int st;
  scan_new(&a, T, 2, &st);
  CHECK_EQ_U(st, ST_DONE);
  CHECK(atomic_load(&a.h->errors) >= 1);
  arena_unmap(&a);
  CHECK(rm_tree(T) == 0);
}

/* Отмена из другого потока во время скана. */
typedef struct { arena *a; int st; } bg;
static void *bg_scan(void *p) {
  bg *b = p;
  scan_opts o = {1, 4};
  b->st = scan_run(b->a, &o);
  return NULL;
}
static void test_cancel_midway(void) {
  const char *T = mk_tmp();
  char d[4096];
  for (int i = 0; i < 200; i++) {
    snprintf(d, sizeof d, "%s/d%d", T, i);
    mk_dir(d);
    for (int j = 0; j < 100; j++) {
      char f[4200];
      snprintf(f, sizeof f, "%s/f%d", d, j);
      write_file(f, 1);
    }
  }
  arena a;
  CHECK(arena_alloc_anon(&a, 1 << 16, 1 << 22, T, SRC_SCAN) == 0);
  bg b = {&a, -1};
  pthread_t th;
  pthread_create(&th, NULL, bg_scan, &b);
  usleep(1000);
  atomic_store(&a.h->cancel, ANCDU_CANCEL_USER);
  pthread_join(th, NULL);
  CHECK(b.st == ST_CANCELLED || b.st == ST_DONE);
  arena_unmap(&a);
  CHECK(rm_tree(T) == 0);
}

int main(void) {
  const char *T = fixture();
  char root[4096];
  snprintf(root, sizeof root, "%s", T);
  test_matches_reference(root, 1);
  test_matches_reference(root, 4);
  test_edge_states(root);
  chmod(pj(root, "noperm"), 0755);
  CHECK(rm_tree(root) == 0);
  test_long_path();
  test_cancel_midway();
  CHECK(scan_default_threads("/") >= 1);
  TEST_END();
}
