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
  char d[4200], q[4300];
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

  /* якорь: d — «корень скана». dev вершины не совпал (want.dev = st_dev + 1), ino корня совпал —
   * ФС перемонтирована, сверка только ino: та же вершина удаляется, подменённая — отказ.
   * ino корня не совпал — сверки нет вовсе: подменённая удаляется (как до v3). */
  rm_expect root = id_of(d);
  mk_dir(pj(d, "re"));
  w = id_of(pj(d, "re"));
  w.dev += 1;
  w.anchor = d;
  w.anchor_ino = root.ino;
  sandbox_guard(S, pj(d, "re"));
  CHECK(rm_tree_expect(pj(d, "re"), 1, NULL, NULL, &w) == 0);
  CHECK(access(pj(d, "re"), F_OK) != 0);
  mk_dir(pj(d, "rr"));
  write_file(pj(d, "rr/orig"), 1);
  w = id_of(pj(d, "rr"));
  replace_dir(d, "rr");
  w.anchor = d;
  w.anchor_ino = root.ino;
  CHECK(rm_tree_expect(pj(d, "rr"), 1, NULL, NULL, &w) == -ESTALE); /* полная сверка */
  w.dev += 1;
  CHECK(rm_tree_expect(pj(d, "rr"), 1, NULL, NULL, &w) == -ESTALE); /* только ino */
  CHECK(access(pj(d, "rr/new"), F_OK) == 0);
  w.anchor_ino = root.ino + 1000003;
  CHECK(rm_tree_expect(pj(d, "rr"), 1, NULL, NULL, &w) == 0); /* корень другой — без сверки */
  CHECK(access(pj(d, "rr"), F_OK) != 0);
  CHECK(access(pj(d, "rr.old/orig"), F_OK) == 0);
  w.anchor_ino = root.ino;
  snprintf(q, sizeof q, "%s/no-such-root", d);
  w.anchor = q; /* корня нет — тоже без сверки */
  mk_dir(pj(d, "rn"));
  CHECK(rm_tree_expect(pj(d, "rn"), 1, NULL, NULL, &w) == 0);

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
  const char *names[] = {"inproc", "helper", "fromcache", "keepok", "keepin",
                         "dev_in", "dev_h", "devok_in", "devok_h", "gone_in", "gone_h"};
  enum { NN = sizeof names / sizeof *names };
  for (int i = 0; i < NN; i++) {
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
  for (int i = 0; i < NN; i++) CHECK(a->ino[find(a, names[i])] == id_of(pj(R, names[i])).ino);
  CHECK(a->ino[0] == id_of(R).ino);
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

  /* без подмены — удаляется: через хелпер и в процессе */
  CHECK(sess_delete(s, find(a, "keepok"), SH, ANCDU_CLI) == 0);
  CHECK(access(pj(R, "keepok"), F_OK) != 0);
  CHECK(sess_delete(s, find(a, "keepin"), NULL, NULL) == 0);
  CHECK(access(pj(R, "keepin"), F_OK) != 0);

  /* пропал после скана — «уже удалён», как до сверки */
  sandbox_guard(S, pj(R, "vanishing"));
  CHECK(unlink(pj(R, "vanishing")) == 0);
  CHECK(sess_delete(s, find(a, "vanishing"), NULL, NULL) == 0);

  /* st_dev сменился (перезагрузка/перемонтирование: дерево из кэша), ino корня тот же —
   * сверка только по ino: подмена по-прежнему отказ, без подмены — удаляется */
  a->h->root_dev += 1;
  n = find(a, "dev_in");
  replace_dir(R, "dev_in");
  CHECK(sess_delete(s, n, NULL, NULL) == -ESTALE);
  CHECK(access(pj(R, "dev_in/new"), F_OK) == 0);
  n = find(a, "dev_h");
  replace_dir(R, "dev_h");
  CHECK(sess_delete(s, n, SH, ANCDU_CLI) == -ESTALE);
  CHECK(access(pj(R, "dev_h/new"), F_OK) == 0);
  CHECK(sess_delete(s, find(a, "devok_in"), NULL, NULL) == 0);
  CHECK(access(pj(R, "devok_in"), F_OK) != 0);
  CHECK(sess_delete(s, find(a, "devok_h"), SH, ANCDU_CLI) == 0);
  CHECK(access(pj(R, "devok_h"), F_OK) != 0);
  a->h->root_dev -= 1;

  /* корень скана другой (ino[0] не совпал) — сверки нет, отказа не бывает: удаляется даже
   * подменённый (поведение до сверки) — в процессе и через хелпер */
  a->ino[0] += 1000003;
  n = find(a, "gone_in");
  replace_dir(R, "gone_in");
  sandbox_guard(S, pj(R, "gone_in"));
  CHECK(sess_delete(s, n, NULL, NULL) == 0);
  CHECK(access(pj(R, "gone_in"), F_OK) != 0);
  n = find(a, "gone_h");
  replace_dir(R, "gone_h");
  sandbox_guard(S, pj(R, "gone_h"));
  CHECK(sess_delete(s, n, SH, ANCDU_CLI) == 0);
  CHECK(access(pj(R, "gone_h"), F_OK) != 0);
  a->ino[0] -= 1000003;
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
