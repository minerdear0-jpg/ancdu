#include <errno.h>
#include <fcntl.h>
#include <signal.h>
#include <spawn.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <unistd.h>

#include "csr.h"
#include "helper_proto.h"
#include "rmtree.h"
#include "session_priv.h"
#include "shq.h"

extern char **environ;

/* prefix... cmd; для NULL-указателей поток направляется в /dev/null.
 * Возвращает pid или -errno. */
static pid_t spawn_cmd(char *const *prefix, const char *cmd, int *in_w, int *out_r,
                       int *err_r) {
  const char *argv[8];
  int n = 0;
  while (prefix[n] && n < 6) {
    argv[n] = prefix[n];
    n++;
  }
  argv[n++] = cmd;
  argv[n] = NULL;
  int pin[2] = {-1, -1}, pout[2] = {-1, -1}, perr[2] = {-1, -1};
  if ((in_w && pipe2(pin, O_CLOEXEC)) || (out_r && pipe2(pout, O_CLOEXEC)) ||
      (err_r && pipe2(perr, O_CLOEXEC))) {
    int e = -errno;
    for (int i = 0; i < 2; i++) {
      if (pin[i] >= 0) close(pin[i]);
      if (pout[i] >= 0) close(pout[i]);
      if (perr[i] >= 0) close(perr[i]);
    }
    return e;
  }
  posix_spawn_file_actions_t fa;
  posix_spawn_file_actions_init(&fa);
  if (in_w) posix_spawn_file_actions_adddup2(&fa, pin[0], 0);
  else posix_spawn_file_actions_addopen(&fa, 0, "/dev/null", O_RDONLY, 0);
  if (out_r) posix_spawn_file_actions_adddup2(&fa, pout[1], 1);
  else posix_spawn_file_actions_addopen(&fa, 1, "/dev/null", O_WRONLY, 0);
  if (err_r) posix_spawn_file_actions_adddup2(&fa, perr[1], 2);
  else posix_spawn_file_actions_addopen(&fa, 2, "/dev/null", O_WRONLY, 0);
  pid_t pid;
  int r = posix_spawnp(&pid, argv[0], &fa, NULL, (char *const *)argv, environ);
  posix_spawn_file_actions_destroy(&fa);
  if (pin[0] >= 0) close(pin[0]);
  if (pout[1] >= 0) close(pout[1]);
  if (perr[1] >= 0) close(perr[1]);
  if (r) {
    if (pin[1] >= 0) close(pin[1]);
    if (pout[0] >= 0) close(pout[0]);
    if (perr[0] >= 0) close(perr[0]);
    return -r;
  }
  if (in_w) *in_w = pin[1];
  if (out_r) *out_r = pout[0];
  if (err_r) *err_r = perr[0];
  return pid;
}

/* Код выхода процесса, 128+сигнал или -errno, если waitpid не удался
 * (сбой ожидания никогда не выдаётся за успешный выход). */
static int exit_status(pid_t pid) {
  int ws = 0;
  pid_t r;
  while ((r = waitpid(pid, &ws, 0)) < 0 && errno == EINTR) {
  }
  if (r < 0) return -errno;
  if (WIFEXITED(ws)) return WEXITSTATUS(ws);
  if (WIFSIGNALED(ws)) return 128 + WTERMSIG(ws);
  return -ECHILD;
}

static void handle_line(session *s, char *l) {
  unsigned long long f, b, e;
  int off = 0;
  if (sscanf(l, "P %llu %llu %llu %n", &f, &b, &e, &off) == 3) {
    atomic_store(&s->p_files, f);
    atomic_store(&s->p_bytes, b);
    atomic_store(&s->p_errors, e);
    pthread_mutex_lock(&s->mu);
    snprintf(s->cur, sizeof s->cur, "%s", off ? l + off : "");
    pthread_mutex_unlock(&s->mu);
  } else if (l[0]) {
    if (!strncmp(l, ANCDU_MEMFD_UNAVAILABLE, sizeof ANCDU_MEMFD_UNAVAILABLE - 1))
      atomic_store(&s->memfd_unavail, 1);
    sess_set_error(s, "%s", l);
  }
}

static void *err_reader(void *p) {
  session *s = p;
  char buf[4096];
  size_t len = 0;
  for (;;) {
    ssize_t r = read(s->err_fd, buf + len, sizeof buf - 1 - len);
    if (r <= 0) break;
    len += (size_t)r;
    char *nl;
    while ((nl = memchr(buf, '\n', len))) {
      *nl = 0;
      handle_line(s, buf);
      size_t used = (size_t)(nl - buf) + 1;
      memmove(buf, nl + 1, len - used);
      len -= used;
    }
    if (len == sizeof buf - 1) len = 0; /* слишком длинная строка — отбросить */
  }
  if (len) {
    buf[len] = 0;
    handle_line(s, buf);
  }
  return NULL;
}

/* Ждёт процесс и поток stderr; закрывает дескрипторы процесса. */
static int reap(session *s) {
  if (s->out_fd >= 0) { close(s->out_fd); s->out_fd = -1; }
  int code = exit_status(s->pid);
  s->pid = -1;
  if (s->have_err_th) { pthread_join(s->err_th, NULL); s->have_err_th = 0; }
  if (s->err_fd >= 0) { close(s->err_fd); s->err_fd = -1; }
  pthread_mutex_lock(&s->mu);
  if (s->in_fd >= 0) { close(s->in_fd); s->in_fd = -1; }
  pthread_mutex_unlock(&s->mu);
  return code;
}

static int build_cmd(session *s, const char *mode, char *cmd, size_t cap) {
  char qh[2100], qr[2100];
  if (shq(s->helper, qh, sizeof qh) < 0 || shq(s->root, qr, sizeof qr) < 0) return -ENAMETOOLONG;
  int n = snprintf(cmd, cap, "%s %s --watch-stdin --root %s%s", qh, mode, qr,
                   s->one_fs ? "" : " --cross-fs");
  return n < (int)cap ? 0 : -ENAMETOOLONG;
}

static int launch(session *s, const char *cmd) {
  int in_w, out_r, err_r;
  pid_t pid = spawn_cmd(s->pfx, cmd, &in_w, &out_r, &err_r);
  if (pid < 0) return (int)pid;
  s->pid = pid;
  pthread_mutex_lock(&s->mu);
  s->in_fd = in_w;
  pthread_mutex_unlock(&s->mu);
  s->out_fd = out_r;
  s->err_fd = err_r;
  if (pthread_create(&s->err_th, NULL, err_reader, s) == 0) s->have_err_th = 1;
  if (atomic_load(&s->cancelled)) { /* отменили до запуска */
    pthread_mutex_lock(&s->mu);
    if (s->in_fd >= 0) close(s->in_fd);
    s->in_fd = -1;
    pthread_mutex_unlock(&s->mu);
  }
  return 0;
}

static int start_pipe(session *s) {
  char cmd[4400];
  int r = build_cmd(s, "--dump", cmd, sizeof cmd);
  return r ? r : launch(s, cmd);
}

static int start_memfd(session *s) {
  uint64_t cap = arena_cap_hint(s->root), names = arena_names_hint(cap);
  size_t size = arena_bytes(cap, names);
  int fd = memfd_create("ancdu", MFD_CLOEXEC);
  if (fd < 0) return -errno;
  if (ftruncate(fd, (off_t)size) != 0) { int e = -errno; close(fd); return e; }
  void *base = mmap(NULL, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
  if (base == MAP_FAILED) { int e = -errno; close(fd); return e; }
  arena_format(base, cap, names, s->root, SRC_SCAN);
  arena_attach(&s->mem, base, size);
  s->mem.owned = 1;
  s->mem_fd = fd;
  atomic_store(&s->ap, &s->mem);
  atomic_store(&s->used_memfd, 1);
  char mode[96], cmd[4400];
  snprintf(mode, sizeof mode, "--memfd /proc/%d/fd/%d", (int)getpid(), fd);
  int r = build_cmd(s, mode, cmd, sizeof cmd);
  return r ? r : launch(s, cmd);
}

static void finish_pipe(session *s) {
  arena tmp;
  int r = arena_read_stream(&tmp, s->out_fd);
  int code = reap(s);
  if (r == 0) {
    s->a = tmp;
    atomic_store(&s->ap, &s->a);
    uint32_t st = atomic_load(&s->a.h->state);
    sess_finish(s, st == ST_RUNNING ? ST_FAILED : (int)st);
    return;
  }
  char eb[8];
  if (sess_error(s, eb, sizeof eb) == 0) sess_set_error(s, "helper failed (exit %d)", code);
  sess_finish(s, atomic_load(&s->cancelled) ? ST_CANCELLED : ST_FAILED);
}

static void *pipe_thread(void *p) {
  finish_pipe(p);
  return NULL;
}

static void *memfd_thread(void *p) {
  session *s = p;
  int code = reap(s);
  int st = (int)atomic_load_explicit(&s->mem.h->state, memory_order_acquire);
  if ((code == 0 || code == 3 || code == 4) && st != ST_RUNNING) {
    sess_finish(s, st);
    return NULL;
  }
  /* Повтор через pipe — только если хелпер сам сообщил, что memfd недоступен
   * (SELinux и т.п.). Отказ su, отсутствие или падение хелпера — FAILED без
   * второго запуска: иначе отказ Magisk вызвал бы второй запрос root. */
  if (atomic_load(&s->memfd_unavail) && atomic_load(&s->mem.h->count) == 0 &&
      !atomic_load(&s->cancelled)) {
    /* memfd-отображение остаётся до sess_free: его мог читать поток UI. */
    atomic_store(&s->ap, NULL);
    atomic_store(&s->used_memfd, 0);
    sess_set_error(s, "%s", "");
    int r = start_pipe(s);
    if (r == 0) {
      finish_pipe(s);
      return NULL;
    }
    sess_set_error(s, "helper failed to start: %s", strerror(-r));
  }
  char eb[8];
  if (sess_error(s, eb, sizeof eb) == 0) sess_set_error(s, "helper failed (exit %d)", code);
  sess_finish(s, atomic_load(&s->cancelled) ? ST_CANCELLED : ST_FAILED);
  return NULL;
}

session *sess_root_start(const char *const *prefix, const char *helper, const char *root,
                         int one_fs, int use_memfd, int *err) {
  session *s = sess_alloc();
  if (!s) { *err = -ENOMEM; return NULL; }
  for (int i = 0; i < 3 && prefix[i]; i++) s->pfx[i] = strdup(prefix[i]);
  snprintf(s->helper, sizeof s->helper, "%s", helper);
  snprintf(s->root, sizeof s->root, "%s", root);
  s->one_fs = one_fs;
  int r = use_memfd ? start_memfd(s) : start_pipe(s);
  if (r) { sess_free(s); *err = r; return NULL; }
  if (pthread_create(&s->th, NULL, use_memfd ? memfd_thread : pipe_thread, s) != 0) {
    sess_cancel(s);
    reap(s);
    sess_free(s);
    *err = -EAGAIN;
    return NULL;
  }
  s->have_thread = 1;
  *err = 0;
  return s;
}

/* Строки «progress N» от хелпера --rm — в del_done; прочие строки игнорируются.
 * Читает до EOF (хелпер завершился). */
static void read_rm_progress(session *s, int fd) {
  char buf[512];
  size_t len = 0;
  for (;;) {
    ssize_t r = read(fd, buf + len, sizeof buf - 1 - len);
    if (r < 0 && errno == EINTR) continue;
    if (r <= 0) break;
    len += (size_t)r;
    char *nl;
    while ((nl = memchr(buf, '\n', len))) {
      *nl = 0;
      unsigned long long n;
      if (sscanf(buf, "progress %llu", &n) == 1) atomic_store(&s->del_done, n);
      size_t used = (size_t)(nl - buf) + 1;
      memmove(buf, nl + 1, len - used);
      len -= used;
    }
    if (len == sizeof buf - 1) len = 0; /* слишком длинная строка — отбросить */
  }
}

int sess_delete(session *s, uint32_t node, const char *const *prefix, const char *helper) {
  arena *a = sess_arena(s);
  if (!a || node == 0 || node >= atomic_load(&a->h->count)) return -EINVAL;
  enum { PCAP = 65536 };
  char *path = malloc(PCAP);
  if (!path) return -ENOMEM;
  int r = arena_path(a, node, path, PCAP);
  if (r < 0) { free(path); return r; }
  atomic_store(&s->del_done, 0);
  atomic_store(&s->del_stop, 0);
  int gone;
  if (!prefix) {
    r = rm_tree_ex(path, scan_default_threads(path), &s->del_done, &s->del_stop);
    struct stat st;
    gone = r == 0 || (lstat(path, &st) != 0 && errno == ENOENT);
  } else {
    size_t qcap = (size_t)PCAP * 4 + 2200;
    char *cmd = malloc(qcap * 2);
    if (!cmd) { free(path); return -ENOMEM; }
    char *qp = cmd + qcap;
    char qh[2100];
    if (shq(helper, qh, sizeof qh) < 0 || shq(path, qp, qcap) < 0) {
      free(cmd);
      free(path);
      return -ENAMETOOLONG;
    }
    snprintf(cmd, qcap, "%s --rm %s --watch-stdin", qh, qp);
    char *pfx[4] = {0};
    for (int i = 0; i < 3 && prefix[i]; i++) pfx[i] = (char *)prefix[i];
    int in_w = -1, err_r = -1;
    pid_t pid = spawn_cmd(pfx, cmd, &in_w, NULL, &err_r);
    int code = -EPERM;
    if (pid >= 0) {
      pthread_mutex_lock(&s->mu);
      s->del_in = in_w;
      if (atomic_load(&s->del_stop)) { /* стоп пришёл до запуска */
        close(s->del_in);
        s->del_in = -1;
      }
      pthread_mutex_unlock(&s->mu);
      read_rm_progress(s, err_r);
      close(err_r);
      code = exit_status(pid);
      pthread_mutex_lock(&s->mu);
      if (s->del_in >= 0) close(s->del_in);
      s->del_in = -1;
      pthread_mutex_unlock(&s->mu);
    }
    /* P7: exit_status не выдаёт сбой ожидания за 0. Коды:
     * -EPERM — хелпер до rm_tree не дошёл, ничего не удалено: su не запустился (pid < 0),
     * отказал или хелпер не нашёлся/не стартовал (выход не 0, 5, 6, < 128).
     * -EINTR — выход 6: остановлен через stdin (sess_delete_stop), удалено частично.
     * -EIO — могло удалиться частично: выход 5 (rm_tree не всё), убит сигналом (≥ 128)
     * или waitpid не удался (code < 0) — исход неизвестен. */
    free(cmd);
    gone = code == 0;
    if (gone) r = 0;
    else if (pid < 0) r = -EPERM;
    else if (code == 6) r = -EINTR;
    else if (code < 0 || code == 5 || code >= 128) r = -EIO;
    else r = -EPERM;
  }
  free(path);
  if (gone) {
    csr_remove(a, node);
    return 0;
  }
  a->flags[node] |= F_ERR;
  return r ? r : -EIO;
}
