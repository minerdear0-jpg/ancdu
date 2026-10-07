/* «Изменилось после скана»: вершина удаления — не тот объект (st_dev/st_ino), что видел скан —
 * отказ -ESTALE, ничего не тронуто. rm_tree_expect напрямую, sess_delete в процессе и через
 * хелпер --rm --expect (sh вместо su), кэш v3 хранит ino. Только абсолютные пути внутри
 * собственного mkdtemp; каждая цель проходит sandbox_guard. */
#include <errno.h>

#include "rmtree.h"
#include "session.h"
#include "test.h"

static const char *const SH[] = {"sh", "-c", NULL};
static char S[4096];

static rm_expect id_of(const char *p) {
  struct stat st;
  rm_expect w = {0, 0};
  if (lstat(p, &st) == 0) {
    w.dev = (uint64_t)st.st_dev;
    w.ino = (uint64_t)st.st_ino;
  }
  return w;
}

/* Каталог name: увезти в name.old и поставить на его место новый с файлом «new». */
static void replace_dir(const char *dir, const char *name) {
  char from[4300], aside[4300];
  snprintf(from, sizeof from, "%s/%s", dir, name);
  snprintf(aside, sizeof aside, "%s/%s.old", dir, name);
  sandbox_guard(S, from);
  sandbox_guard(S, aside);
  if (rename(from, aside) != 0) { perror("rename"); exit(2); }
  mk_dir(from);
  write_file(pj(from, "new"), 10);
}

static uint32_t find(const arena *a, const char *name) {
  uint64_t n = atomic_load(&a->h->count);
  for (uint64_t i = 1; i < n; i++)
    if (strcmp(arena_name(a, (uint32_t)i), name) == 0) return (uint32_t)i;
  return ANCDU_NONE;
}

static void direct(void) {
  char d[4200];
  snprintf(d, sizeof d, "%s/direct", S);
  mk_dir(d);

  /* тот же объект — удаляется как обычно */
  mk_dir(pj(d, "same"));
  write_file(pj(d, "same/f"), 1);
  rm_expect w = id_of(pj(d, "same"));
  sandbox_guard(S, pj(d, "same"));
  CHECK(rm_tree_expect(pj(d, "same"), 1, NULL, NULL, &w) == 0);
  CHECK(access(pj(d, "same"), F_OK) != 0);

  /* каталог подменён: отказ, новый и увезённый целы (последовательно и с потоками) */
  for (int th = 1; th <= 4; th += 3) {
    char nm[32];
    snprintf(nm, sizeof nm, "a%d", th);
    mk_dir(pj(d, nm));
    write_file(pj(pj(d, nm), "orig"), 1);
    w = id_of(pj(d, nm));
    replace_dir(d, nm);
    _Atomic uint64_t done = 0;
    sandbox_guard(S, pj(d, nm));
    CHECK(rm_tree_expect(pj(d, nm), th, &done, NULL, &w) == -ESTALE);
    CHECK_EQ_U(atomic_load(&done), 0);
    CHECK(access(pj(pj(d, nm), "new"), F_OK) == 0);
    char old[64];
    snprintf(old, sizeof old, "%s.old/orig", nm);
    CHECK(access(pj(d, old), F_OK) == 0);
  }

  /* файл подменён другим файлом; вершина стала ссылкой на чужое — тоже отказ */
  write_file(pj(d, "file"), 1);
  w = id_of(pj(d, "file"));
  CHECK(rename(pj(d, "file"), pj(d, "file.old")) == 0);
  write_file(pj(d, "file"), 2);
  sandbox_guard(S, pj(d, "file"));
  CHECK(rm_tree_expect(pj(d, "file"), 1, NULL, NULL, &w) == -ESTALE);
  CHECK(access(pj(d, "file"), F_OK) == 0);
  mk_dir(pj(d, "tgt"));
  write_file(pj(d, "tgt/keep"), 1);
  w = id_of(pj(d, "tgt"));
  CHECK(rename(pj(d, "tgt"), pj(d, "tgt2")) == 0);
  CHECK(symlink(pj(d, "tgt2"), pj(d, "tgt")) == 0);
  sandbox_guard(S, pj(d, "tgt"));
  CHECK(rm_tree_expect(pj(d, "tgt"), 1, NULL, NULL, &w) == -ESTALE);
  CHECK(access(pj(d, "tgt2/keep"), F_OK) == 0);

  /* ino 0 — сверки нет (индекс, кэш без ino) */
  rm_expect none = {w.dev, 0};
  CHECK(rm_tree_expect(pj(d, "file"), 1, NULL, NULL, &none) == 0);
  CHECK(access(pj(d, "file"), F_OK) != 0);

  sandbox_guard(S, d);
  CHECK(rm_tree(d) == 0);
}

/* sess_delete: в процессе (prefix NULL) и через хелпер (prefix sh); узел помечен F_ERR,
 * размеры дерева не тронуты. Пропавший узел (ENOENT) — удалён, как раньше. */
static void session_cases(const char *cache) {
  char R[4200];
  snprintf(R, sizeof R, "%s/tree", S);
  mk_dir(R);
  const char *names[] = {"inproc", "helper", "fromcache", "keepok"};
  for (int i = 0; i < 4; i++) {
    mk_dir(pj(R, names[i]));
    write_file(pj(pj(R, names[i]), "orig"), 4096);
  }
  write_file(pj(R, "vanishing"), 1);
  int err;
  session *s = sess_scan_start(R, 1, 2, &err);
  CHECK(s != NULL);
  CHECK_EQ_U(sess_wait(s), ST_DONE);
  arena *a = sess_arena(s);
  if (!a) return;
  CHECK(a->h->root_dev != 0);
  for (int i = 0; i < 4; i++) CHECK(a->ino[find(a, names[i])] == id_of(pj(R, names[i])).ino);
  CHECK(sess_save_cache(s, cache) == 0);

  uint64_t total = a->disk[0];
  uint32_t n = find(a, "inproc");
  replace_dir(R, "inproc");
  CHECK(sess_delete(s, n, NULL, NULL) == -ESTALE);
  CHECK(a->flags[n] & F_ERR);
  CHECK(!(a->flags[n] & F_DELETED));
  CHECK_EQ_U(sess_delete_progress(s), 0);
  CHECK(access(pj(R, "inproc/new"), F_OK) == 0);
  CHECK(access(pj(R, "inproc.old/orig"), F_OK) == 0);

  n = find(a, "helper");
  replace_dir(R, "helper");
  CHECK(sess_delete(s, n, SH, ANCDU_CLI) == -ESTALE);
  CHECK(a->flags[n] & F_ERR);
  CHECK(access(pj(R, "helper/new"), F_OK) == 0);
  CHECK(access(pj(R, "helper.old/orig"), F_OK) == 0);
  CHECK_EQ_U(a->disk[0], total);

  /* без подмены — удаляется (в процессе и через хелпер) */
  CHECK(sess_delete(s, find(a, "keepok"), SH, ANCDU_CLI) == 0);
  CHECK(access(pj(R, "keepok"), F_OK) != 0);

  /* пропал после скана — «уже удалён», как до сверки */
  sandbox_guard(S, pj(R, "vanishing"));
  CHECK(unlink(pj(R, "vanishing")) == 0);
  CHECK(sess_delete(s, find(a, "vanishing"), NULL, NULL) == 0);
  sess_free(s);

  /* кэш v3 хранит ino: удаление из открытого кэша тоже сверяет */
  session *c = sess_open_cache(cache, &err);
  CHECK(c != NULL && err == 0);
  arena *ca = c ? sess_arena(c) : NULL;
  if (ca) {
    n = find(ca, "fromcache");
    CHECK(ca->ino[n] != 0);
    replace_dir(R, "fromcache");
    CHECK(sess_delete(c, n, NULL, NULL) == -ESTALE);
    CHECK(access(pj(R, "fromcache/new"), F_OK) == 0);
  }
  sess_free(c);

  sandbox_guard(S, R);
  CHECK(rm_tree(R) == 0);
}

/* Кэш прежней версии (v2) не открывается: приложение его забывает и сканирует заново. */
static void old_cache(const char *cache) {
  int fd = open(cache, O_RDWR);
  CHECK(fd >= 0);
  char v2[8] = {'A', 'N', 'C', 'D', 'U', 0, 0, 2};
  uint32_t ver = 2;
  CHECK(pwrite(fd, v2, 8, 0) == 8);
  CHECK(pwrite(fd, &ver, 4, 8) == 4);
  close(fd);
  int err = 0;
  CHECK(sess_open_cache(cache, &err) == NULL);
  CHECK(err == -EINVAL);
}

int main(void) {
  alarm(60);
  snprintf(S, sizeof S, "%s", mk_tmp());
  if (S[0] != '/') { fprintf(stderr, "relative mk_tmp: %s\n", S); return 1; }
  char cache[4200];
  snprintf(cache, sizeof cache, "%s/tree.cache", S);
  direct();
  session_cases(cache);
  old_cache(cache);
  CHECK(rm_tree(S) == 0);
  CHECK(access(S, F_OK) != 0);
  TEST_END();
}
