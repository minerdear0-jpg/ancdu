#include <errno.h>
#include <pthread.h>
#include <sched.h>
#include <signal.h>

#include <dirent.h>

#include "helper_proto.h"
#include "session.h"
#include "test.h"

static const char *const SH[] = {"sh", "-c", NULL};

static uint32_t find(const arena *a, const char *name) {
  uint64_t n = atomic_load(&a->h->count);
  for (uint64_t i = 0; i < n; i++)
    if (strcmp(arena_name(a, (uint32_t)i), name) == 0) return (uint32_t)i;
  return ANCDU_NONE;
}

static uint64_t root_total(session *s) {
  arena *a = sess_arena(s);
  return a ? a->disk[0] : 0;
}

/* Обёртка хелпера: каждый запуск дописывает строку в cnt; в режиме --memfd
 * печатает err_line в stderr и выходит с 1, иначе запускает настоящий хелпер. */
static void write_wrapper(const char *path, const char *cnt, const char *err_line) {
  FILE *f = fopen(path, "w");
  fprintf(f, "#!/bin/sh\necho x >> '%s'\n"
             "case \"$*\" in *--memfd*) echo '%s' >&2; exit 1;; esac\n"
             "exec '%s' \"$@\"\n", cnt, err_line, ANCDU_CLI);
  fclose(f);
  chmod(path, 0755);
}

/* Стоп удаления из другого потока: после delay_ms или когда прогресс дошёл до at. */
typedef struct {
  session *s;
  int delay_ms;
  uint64_t at;
  _Atomic int ready;
} stopper;

static void *stop_later(void *p) {
  stopper *x = p;
  atomic_store(&x->ready, 1);
  if (x->delay_ms) usleep((useconds_t)x->delay_ms * 1000);
  else
    while (sess_delete_progress(x->s) < x->at) sched_yield();
  sess_delete_stop(x->s);
  return NULL;
}

static uint64_t entries(const char *p) {
  struct stat st;
  if (lstat(p, &st) != 0) return 0;
  uint64_t n = 1;
  if (!S_ISDIR(st.st_mode)) return n;
  DIR *d = opendir(p);
  struct dirent *e;
  while (d && (e = readdir(d))) {
    if (!strcmp(e->d_name, ".") || !strcmp(e->d_name, "..")) continue;
    char c[4400];
    snprintf(c, sizeof c, "%s/%s", p, e->d_name);
    n += entries(c);
  }
  if (d) closedir(d);
  return n;
}

static int launches(const char *cnt) {
  FILE *f = fopen(cnt, "r");
  if (!f) return 0;
  int n = 0, c;
  while ((c = fgetc(f)) != EOF) n += c == '\n';
  fclose(f);
  return n;
}

int main(void) {
  alarm(60); /* любое зависание — провал */
  char T[4096], W[4096], WD[4096], CNT[4200];
  snprintf(T, sizeof T, "%s", mk_tmp());
  mk_dir(pj(T, "d1"));
  write_file(pj(T, "d1/f1"), 1000);
  write_file(pj(T, "it's\nodd"), 4096);
  mk_dir(pj(T, "gone"));
  write_file(pj(T, "gone/x"), 10);
  /* 7 записей: удаление через хелпер, прогресс из строк «progress N» */
  mk_dir(pj(T, "prog"));
  write_file(pj(T, "prog/a"), 1);
  write_file(pj(T, "prog/b"), 1);
  write_file(pj(T, "prog/c"), 1);
  mk_dir(pj(T, "prog/sub"));
  write_file(pj(T, "prog/sub/d"), 1);
  write_file(pj(T, "prog/sub/e"), 1);
  mk_dir(pj(T, "stopme"));
  write_file(pj(T, "stopme/keep"), 1);
  int err;

  session *ref = sess_scan_start(T, 1, 2, &err);
  CHECK_EQ_U(sess_wait(ref), ST_DONE);
  uint64_t want = root_total(ref);
  sess_free(ref);

  /* pipe */
  session *p = sess_root_start(SH, ANCDU_CLI, T, 1, 0, &err);
  CHECK(p != NULL && err == 0);
  CHECK_EQ_U(sess_wait(p), ST_DONE);
  CHECK_EQ_U(root_total(p), want);
  int64_t pr[6];
  sess_progress(p, pr, NULL, 0);
  CHECK_EQ_U(pr[5], 0);

  /* удаление через хелпер (prefix sh): имя с кавычкой и переводом строки */
  arena *a = sess_arena(p);
  uint32_t odd = find(a, "it's\nodd");
  uint64_t before = a->disk[0], odd_disk = a->disk[odd];
  CHECK(sess_delete(p, odd, SH, ANCDU_CLI) == 0);
  CHECK(access(pj(T, "it's\nodd"), F_OK) != 0);
  CHECK_EQ_U(a->disk[0], before - odd_disk);
  CHECK_EQ_U(sess_delete_progress(p), 1);

  uint32_t prog = find(a, "prog");
  CHECK(sess_delete(p, prog, SH, ANCDU_CLI) == 0);
  CHECK(access(pj(T, "prog"), F_OK) != 0);
  CHECK_EQ_U(sess_delete_progress(p), 7);

  /* стоп через stdin хелпера: обёртка ждёт 0,3 с, стоп — через 0,1 с; хелпер стартует
   * с закрытым stdin, выход 6 → -EINTR; ничего не удалено, дерево цело, узел помечен */
  {
    char SW[4096], SWS[4200];
    snprintf(SW, sizeof SW, "%s", mk_tmp());
    snprintf(SWS, sizeof SWS, "%s/slow.sh", SW);
    FILE *f = fopen(SWS, "w");
    fprintf(f, "#!/bin/sh\nsleep 0.3\nexec '%s' \"$@\"\n", ANCDU_CLI);
    fclose(f);
    chmod(SWS, 0755);
    uint32_t sm = find(a, "stopme");
    uint64_t total = a->disk[0];
    stopper x = {.s = p, .delay_ms = 100};
    pthread_t th;
    CHECK(pthread_create(&th, NULL, stop_later, &x) == 0);
    while (!atomic_load(&x.ready)) sched_yield();
    CHECK(sess_delete(p, sm, SH, SWS) == -EINTR);
    pthread_join(th, NULL);
    CHECK(access(pj(T, "stopme/keep"), F_OK) == 0);
    CHECK(a->flags[sm] & F_ERR);
    CHECK_EQ_U(a->disk[0], total);
    rm_dir_tree_for_tests(SW);
  }

  /* root не получен — хелпер не запускался, ничего не удалено: -EPERM (в UI «ничего не
   * удалено»). Убит сигналом — мог успеть удалить часть: -EIO, как выход 5. */
  {
    static const char *const DENY[] = {"sh", "-c", "exit 1", NULL};
    static const char *const NOSU[] = {"/nonexistent-ancdu-su", NULL};
    static const char *const KILLED[] = {"sh", "-c", "kill -KILL $$", NULL};
    uint32_t d1 = find(a, "d1");
    uint64_t total = a->disk[0];
    CHECK(sess_delete(p, d1, DENY, ANCDU_CLI) == -EPERM);
    CHECK(sess_delete(p, d1, NOSU, ANCDU_CLI) == -EPERM);
    CHECK(sess_delete(p, d1, SH, "/nonexistent-ancdu-helper") == -EPERM);
    CHECK(sess_delete(p, d1, KILLED, ANCDU_CLI) == -EIO);
    CHECK(access(pj(T, "d1/f1"), F_OK) == 0);
    CHECK(a->flags[d1] & F_ERR);
    CHECK_EQ_U(a->disk[0], total);
  }
  sess_free(p);

  /* memfd: эталон пересчитан после удаления */
  ref = sess_scan_start(T, 1, 2, &err);
  sess_wait(ref);
  want = root_total(ref);
  sess_free(ref);
  session *m = sess_root_start(SH, ANCDU_CLI, T, 1, 1, &err);
  CHECK(m != NULL);
  CHECK_EQ_U(sess_wait(m), ST_DONE);
  sess_progress(m, pr, NULL, 0);
  CHECK_EQ_U(pr[5], 1);
  CHECK_EQ_U(root_total(m), want);
  sess_free(m);

  /* хелпер сообщил «memfd недоступен» → откат на pipe (второй запуск) */
  snprintf(WD, sizeof WD, "%s", mk_tmp());
  snprintf(W, sizeof W, "%s/wrap.sh", WD);
  snprintf(CNT, sizeof CNT, "%s/count", WD);
  write_wrapper(W, CNT, ANCDU_MEMFD_UNAVAILABLE ": /proc/1/fd/3: Permission denied");
  session *fb = sess_root_start(SH, W, T, 1, 1, &err);
  CHECK(fb != NULL);
  CHECK_EQ_U(sess_wait(fb), ST_DONE);
  sess_progress(fb, pr, NULL, 0);
  CHECK_EQ_U(pr[5], 0);
  CHECK(sess_arena(fb) != NULL);
  CHECK_EQ_U(root_total(fb), want);
  CHECK_EQ_U(launches(CNT), 2);
  sess_free(fb);

  /* сбой без маркера (отказ su и т.п.) под memfd → FAILED, без второго запуска */
  unlink(CNT);
  write_wrapper(W, CNT, "Permission denied");
  session *nf = sess_root_start(SH, W, T, 1, 1, &err);
  CHECK(nf != NULL);
  CHECK_EQ_U(sess_wait(nf), ST_FAILED);
  CHECK(sess_arena(nf) == NULL);
  char eb[256];
  CHECK(sess_error(nf, eb, sizeof eb) > 0);
  CHECK(strstr(eb, "Permission denied") != NULL);
  CHECK_EQ_U(launches(CNT), 1);
  sess_free(nf);

  /* хелпер не запускается → FAILED с текстом (pipe и memfd) */
  for (int mf = 0; mf <= 1; mf++) {
    session *bad = sess_root_start(SH, "/nonexistent/helper", T, 1, mf, &err);
    CHECK(bad != NULL);
    CHECK_EQ_U(sess_wait(bad), ST_FAILED);
    CHECK(sess_error(bad, eb, sizeof eb) > 0);
    sess_free(bad);
  }

  /* удаление в процессе: каталог целиком */
  session *ip = sess_scan_start(T, 1, 2, &err);
  sess_wait(ip);
  a = sess_arena(ip);
  CHECK(sess_delete(ip, find(a, "gone"), NULL, NULL) == 0);
  CHECK(access(pj(T, "gone"), F_OK) != 0);
  CHECK_EQ_U(sess_delete_progress(ip), 2);
  CHECK(sess_delete(ip, 0, NULL, NULL) == -EINVAL); /* корень нельзя */

  /* частичное удаление: дерево не меняется, узел помечен */
  if (geteuid() != 0) {
    mk_dir(pj(T, "part"));
    mk_dir(pj(T, "part/locked"));
    write_file(pj(T, "part/locked/x"), 10);
    chmod(pj(T, "part/locked"), 0500);
    sess_free(ip);
    ip = sess_scan_start(T, 1, 2, &err);
    sess_wait(ip);
    a = sess_arena(ip);
    uint32_t part = find(a, "part");
    before = a->disk[0];
    CHECK(sess_delete(ip, part, NULL, NULL) < 0);
    CHECK(a->flags[part] & F_ERR);
    CHECK_EQ_U(a->disk[0], before);
    chmod(pj(T, "part/locked"), 0755);
  }
  sess_free(ip);

  /* отмена root-скана: процесс завершается, без зависания */
  char B[4096];
  snprintf(B, sizeof B, "%s", mk_tmp());
  for (int i = 0; i < 100; i++) {
    char d[4200];
    snprintf(d, sizeof d, "%s/d%d", B, i);
    mk_dir(d);
    for (int j = 0; j < 100; j++) {
      char fp[4300];
      snprintf(fp, sizeof fp, "%s/f%d", d, j);
      write_file(fp, 1);
    }
  }
  session *cx = sess_root_start(SH, ANCDU_CLI, B, 1, 0, &err);
  sess_cancel(cx);
  int st = sess_wait(cx);
  CHECK(st == ST_CANCELLED || st == ST_DONE);
  sess_free(cx);

  /* стоп удаления в процессе из другого потока (после 50 записей): -EINTR, удалённое
   * удалено, остальное на месте, дерево не меняется, узел помечен */
  char BG[4096];
  snprintf(BG, sizeof BG, "%s", mk_tmp());
  {
    mk_dir(pj(BG, "big"));
    for (int i = 0; i < 20000; i++) {
      char fp[4300];
      snprintf(fp, sizeof fp, "%s/big/f%d", BG, i);
      write_file(fp, 1);
    }
    session *ss = sess_scan_start(BG, 1, 2, &err);
    CHECK_EQ_U(sess_wait(ss), ST_DONE);
    arena *sa = sess_arena(ss);
    uint32_t big = find(sa, "big");
    uint64_t total = sa->disk[0], n = entries(pj(BG, "big"));
    stopper x = {.s = ss, .at = 50};
    pthread_t th;
    CHECK(pthread_create(&th, NULL, stop_later, &x) == 0);
    while (!atomic_load(&x.ready)) sched_yield();
    CHECK(sess_delete(ss, big, NULL, NULL) == -EINTR);
    pthread_join(th, NULL);
    uint64_t dn = sess_delete_progress(ss);
    CHECK(dn >= 50 && dn < n);
    CHECK_EQ_U(entries(pj(BG, "big")), n - dn);
    CHECK(sa->flags[big] & F_ERR);
    CHECK_EQ_U(sa->disk[0], total);
    sess_free(ss);
  }
  rm_dir_tree_for_tests(BG);

  rm_dir_tree_for_tests(B);
  rm_dir_tree_for_tests(T);
  rm_dir_tree_for_tests(WD);
  TEST_END();
}
