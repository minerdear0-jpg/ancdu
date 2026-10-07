/* Глубокие деревья: rm_tree_ex не зависит от стека вызывающего (поток с малым стеком, как
 * Java-поток ancdu-io), граница RM_MAX_DEPTH (4096 — удаляется целиком, 4097 и 5000 —
 * отказ -ELOOP без падения), то же через sess_delete в процессе и через хелпер --rm.
 * Только абсолютные пути внутри собственного mkdtemp; каждая цель проходит sandbox_guard. */
#include <errno.h>
#include <pthread.h>

#include "rmtree.h"
#include "session.h"
#include "test.h"

static const char *const SH[] = {"sh", "-c", NULL};
static char S[4096]; /* песочница */

/* Цепочка top/d/d/…/d из levels каталогов под top (сам top — уровень 0) и файл в самом низу.
 * Путь длиннее PATH_MAX — только через fd. */
static void deep(const char *top, int levels) {
  sandbox_guard(S, top);
  mk_dir(top);
  int fd = open(top, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (fd < 0) { perror(top); exit(2); }
  for (int i = 0; i < levels; i++) {
    int n;
    if (mkdirat(fd, "d", 0755) != 0 || (n = openat(fd, "d", O_RDONLY | O_DIRECTORY | O_CLOEXEC)) < 0) {
      perror("deep");
      exit(2);
    }
    close(fd);
    fd = n;
  }
  int f = openat(fd, "leaf", O_WRONLY | O_CREAT | O_CLOEXEC, 0644);
  if (f < 0) { perror("leaf"); exit(2); }
  close(f);
  close(fd);
}

/* Уровней каталогов под top (0 — top пуст или его нет); -1 — top нет. */
static int depth_of(const char *top) {
  int fd = open(top, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
  if (fd < 0) return -1;
  int d = 0, n;
  while ((n = openat(fd, "d", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) >= 0) {
    close(fd);
    fd = n;
    d++;
  }
  close(fd);
  return d;
}

/* Уборка цепочки глубже, чем принимает rm_tree: каждые 2000 уровней хвост переносится
 * (renameat) в корень песочницы, затем куски удаляются rm_tree по абсолютному пути. */
static int cuts;
static void rm_deep(const char *top) {
  char cur[4200];
  snprintf(cur, sizeof cur, "%s", top);
  int sfd = open(S, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (sfd < 0) { perror(S); exit(2); }
  for (;;) {
    sandbox_guard(S, cur);
    int fd = open(cur, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW), i = 0, n;
    if (fd < 0) break;
    for (; i < 2000 && (n = openat(fd, "d", O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW)) >= 0; i++) {
      close(fd);
      fd = n;
    }
    char cut[64];
    snprintf(cut, sizeof cut, "cut%d", cuts++);
    int moved = i == 2000 && renameat(fd, "d", sfd, cut) == 0;
    close(fd);
    CHECK(rm_tree(cur) == 0);
    if (!moved) break;
    snprintf(cur, sizeof cur, "%s/%s", S, cut);
  }
  close(sfd);
}

/* Вызов на потоке со стеком 256 КБ (меньше, чем у ancdu-io): без своего стека rm_tree_ex
 * упал бы на глубине ~600–700. */
typedef struct {
  const char *path;
  session *s;
  uint32_t node;
  int threads;
  int r;
} call;

static void *call_main(void *p) {
  call *c = p;
  c->r = c->s ? sess_delete(c->s, c->node, NULL, NULL) : rm_tree_ex(c->path, c->threads, NULL, NULL);
  return NULL;
}

static int small_stack(call *c) {
  pthread_attr_t at;
  pthread_t th;
  pthread_attr_init(&at);
  pthread_attr_setstacksize(&at, 256u << 10);
  int e = pthread_create(&th, &at, call_main, c);
  pthread_attr_destroy(&at);
  if (e) { fprintf(stderr, "pthread_create: %d\n", e); exit(2); }
  pthread_join(th, NULL);
  return c->r;
}

static int rm_small(const char *path, int threads) {
  sandbox_guard(S, path);
  call c = {.path = path, .threads = threads};
  return small_stack(&c);
}

static uint32_t find(const arena *a, const char *name) {
  uint64_t n = atomic_load(&a->h->count);
  for (uint64_t i = 1; i < n; i++)
    if (a->parent[i] == 0 && strcmp(arena_name(a, (uint32_t)i), name) == 0) return (uint32_t)i;
  return ANCDU_NONE;
}

int main(void) {
  alarm(120);
  snprintf(S, sizeof S, "%s", mk_tmp());
  if (S[0] != '/') { fprintf(stderr, "relative mk_tmp: %s\n", S); return 1; }
  char p[4200];

  /* граница: 4096 уровней — целиком, последовательно и параллельно */
  for (int th = 1; th <= 4; th += 3) {
    snprintf(p, sizeof p, "%s/b4096_%d", S, th);
    deep(p, 4096);
    CHECK(depth_of(p) == 4096);
    CHECK(rm_small(p, th) == 0);
    CHECK(access(p, F_OK) != 0);
  }

  /* 4097 и 5000 — отказ на глубине, без падения; цепочка на месте */
  int over[] = {4097, 5000};
  for (size_t i = 0; i < sizeof over / sizeof *over; i++) {
    snprintf(p, sizeof p, "%s/o%d", S, over[i]);
    deep(p, over[i]);
    CHECK(rm_small(p, 1) == -ELOOP);
    CHECK(depth_of(p) == over[i]);
    rm_deep(p);
    CHECK(access(p, F_OK) != 0);
  }

  /* через сессию: в процессе (поток с малым стеком) и через хелпер --rm (sh вместо su) */
  char R[4200];
  snprintf(R, sizeof R, "%s/tree", S);
  mk_dir(R);
  const char *names[] = {"in4096", "in5000", "h4096", "h5000"};
  int lv[] = {4096, 5000, 4096, 5000};
  for (int i = 0; i < 4; i++) deep(pj(R, names[i]), lv[i]);
  int err;
  session *s = sess_scan_start(R, 1, 2, &err);
  CHECK(s != NULL);
  CHECK_EQ_U(sess_wait(s), ST_DONE);
  arena *a = sess_arena(s);
  if (a) {
    uint32_t n[4];
    for (int i = 0; i < 4; i++) {
      n[i] = find(a, names[i]);
      CHECK(n[i] != ANCDU_NONE);
      sandbox_guard(S, pj(R, names[i]));
    }
    call c0 = {.s = s, .node = n[0]};
    CHECK(small_stack(&c0) == 0);
    CHECK(access(pj(R, "in4096"), F_OK) != 0);
    call c1 = {.s = s, .node = n[1]};
    CHECK(small_stack(&c1) == -ELOOP);
    CHECK(a->flags[n[1]] & F_ERR);
    CHECK(depth_of(pj(R, "in5000")) == 5000);

    CHECK(sess_delete(s, n[2], SH, ANCDU_CLI) == 0);
    CHECK(access(pj(R, "h4096"), F_OK) != 0);
    CHECK(sess_delete(s, n[3], SH, ANCDU_CLI) == -EIO); /* выход 5: частично (отказ на глубине) */
    CHECK(a->flags[n[3]] & F_ERR);
    CHECK(depth_of(pj(R, "h5000")) == 5000);
  }
  sess_free(s);
  rm_deep(pj(R, "in5000"));
  rm_deep(pj(R, "h5000"));
  CHECK(rm_tree(R) == 0);

  CHECK(rm_tree(S) == 0);
  CHECK(access(S, F_OK) != 0);
  TEST_END();
}
