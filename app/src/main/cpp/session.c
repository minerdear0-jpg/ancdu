#include "session.h"

#include <errno.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include "csr.h"
#include "session_priv.h"

session *sess_alloc(void) {
  session *s = calloc(1, sizeof *s);
  if (!s) return NULL;
  pthread_mutex_init(&s->mu, NULL);
  s->pid = -1;
  s->in_fd = s->out_fd = s->err_fd = s->mem_fd = s->del_in = -1;
  s->started_ns = ancdu_now_ns();
  atomic_store(&s->state, ST_RUNNING);
  return s;
}

void sess_set_error(session *s, const char *fmt, ...) {
  va_list ap;
  va_start(ap, fmt);
  pthread_mutex_lock(&s->mu);
  vsnprintf(s->err, sizeof s->err, fmt, ap);
  pthread_mutex_unlock(&s->mu);
  va_end(ap);
}

void sess_finish(session *s, int state) {
  atomic_store(&s->finished_ns, ancdu_now_ns());
  arena *a = atomic_load(&s->ap);
  if (a) atomic_store_explicit(&a->h->state, (uint32_t)state, memory_order_release);
  atomic_store_explicit(&s->state, state, memory_order_release);
}

static void *scan_thread(void *p) {
  session *s = p;
  int st = scan_run(&s->a, &s->opts);
  if ((st == ST_DONE || st == ST_FULL) && post_process(&s->a, s->opts.threads) != 0) {
    sess_set_error(s, "%s", "invalid tree");
    st = ST_FAILED;
  } else if (st == ST_FAILED) {
    sess_set_error(s, "cannot open %s", s->a.h->root_path);
  }
  sess_finish(s, st);
  return NULL;
}

session *sess_scan_start(const char *root, int one_fs, int threads, int *err) {
  session *s = sess_alloc();
  if (!s) { *err = -ENOMEM; return NULL; }
  uint64_t cap = arena_cap_hint(root);
  int r = arena_alloc_anon(&s->a, cap, arena_names_hint(cap), root, SRC_SCAN);
  if (r) { sess_free(s); *err = r; return NULL; }
  atomic_store(&s->ap, &s->a);
  s->opts.one_fs = one_fs;
  s->opts.threads = threads > 0 ? threads : scan_default_threads(root);
  if (pthread_create(&s->th, NULL, scan_thread, s) != 0) {
    sess_free(s);
    *err = -EAGAIN;
    return NULL;
  }
  s->have_thread = 1;
  *err = 0;
  return s;
}

session *sess_index_begin(const char *root, uint64_t cap_nodes, int *err) {
  session *s = sess_alloc();
  if (!s) { *err = -ENOMEM; return NULL; }
  int r = arena_alloc_anon(&s->a, cap_nodes, arena_names_hint(cap_nodes), root, SRC_INDEX);
  if (r) { sess_free(s); *err = r; return NULL; }
  atomic_store(&s->ap, &s->a);
  s->ib = index_begin(&s->a);
  if (!s->ib) { sess_free(s); *err = -ENOMEM; return NULL; }
  *err = 0;
  return s;
}

int sess_index_add(session *s, const char *rel_dir, const char *name, uint64_t size) {
  return s->ib ? index_add(s->ib, rel_dir, name, size) : -EINVAL;
}

int sess_index_finish(session *s) {
  if (!s->ib) return -EINVAL;
  index_end(s->ib);
  s->ib = NULL;
  if (post_process(&s->a, scan_default_threads("/")) != 0) {
    sess_set_error(s, "%s", "invalid tree");
    sess_finish(s, ST_FAILED);
    return -EINVAL;
  }
  sess_finish(s, atomic_load(&s->a.h->cancel) == ANCDU_CANCEL_FULL ? ST_FULL : ST_DONE);
  return 0;
}

session *sess_open_cache(const char *path, int *err) {
  session *s = sess_alloc();
  if (!s) { *err = -ENOMEM; return NULL; }
  int r = arena_open_file(&s->a, path);
  if (r) { sess_free(s); *err = r; return NULL; }
  uint32_t st = atomic_load(&s->a.h->state);
  if (st != ST_DONE && st != ST_FULL) {
    arena_unmap(&s->a);
    sess_free(s);
    *err = -EINVAL;
    return NULL;
  }
  atomic_store(&s->ap, &s->a);
  atomic_store(&s->finished_ns, s->started_ns);
  atomic_store(&s->state, (int)st);
  *err = 0;
  return s;
}

int sess_save_cache(session *s, const char *path) {
  arena *a = sess_arena(s);
  return a ? arena_save_file(a, path) : -EBUSY;
}

void sess_progress(session *s, int64_t out[6], char *path, size_t cap) {
  int st = atomic_load_explicit(&s->state, memory_order_acquire);
  arena *a = atomic_load(&s->ap);
  out[0] = st;
  if (a) {
    out[1] = (int64_t)atomic_load(&a->h->files);
    out[2] = (int64_t)atomic_load(&a->h->bytes);
    out[3] = (int64_t)atomic_load(&a->h->errors);
    if (path && cap) arena_cur_path(a, path, cap);
  } else {
    out[1] = (int64_t)atomic_load(&s->p_files);
    out[2] = (int64_t)atomic_load(&s->p_bytes);
    out[3] = (int64_t)atomic_load(&s->p_errors);
    if (path && cap) {
      pthread_mutex_lock(&s->mu);
      snprintf(path, cap, "%s", s->cur);
      pthread_mutex_unlock(&s->mu);
    }
  }
  int64_t end = st == ST_RUNNING ? ancdu_now_ns() : atomic_load(&s->finished_ns);
  out[4] = (end - s->started_ns) / 1000000;
  out[5] = atomic_load(&s->used_memfd);
}

int sess_live_top(session *s, uint32_t *nodes, uint64_t *disk, int cap) {
  arena *a = atomic_load(&s->ap);
  if (!a) return 0;
  uint32_t n = atomic_load(&a->h->live_count);
  int k = 0;
  for (uint32_t i = 1; i <= n && k < cap; i++) {
    nodes[k] = i == ANCDU_LIVE_SLOTS - 1 ? ANCDU_NONE : i;
    disk[k++] = atomic_load_explicit(&a->h->live_disk[i], memory_order_relaxed);
  }
  return k;
}

const char *sess_live_name(session *s, uint32_t node, size_t *len) {
  arena *a = atomic_load(&s->ap);
  if (!a || node == 0 || node > atomic_load(&a->h->live_count)) return NULL;
  *len = a->name_len[node];
  return arena_name(a, node);
}

int sess_error(session *s, char *out, size_t cap) {
  pthread_mutex_lock(&s->mu);
  int n = snprintf(out, cap, "%s", s->err);
  pthread_mutex_unlock(&s->mu);
  return n;
}

void sess_cancel(session *s) {
  if (atomic_load(&s->state) != ST_RUNNING) return;
  atomic_store(&s->cancelled, 1);
  arena *a = atomic_load(&s->ap);
  if (a) atomic_store(&a->h->cancel, ANCDU_CANCEL_USER);
  pthread_mutex_lock(&s->mu);
  if (s->in_fd >= 0) { /* root-хелпер видит EOF на stdin и отменяет скан */
    close(s->in_fd);
    s->in_fd = -1;
  }
  pthread_mutex_unlock(&s->mu);
}

uint64_t sess_delete_progress(session *s) { return atomic_load(&s->del_done); }

void sess_delete_stop(session *s) {
  atomic_store(&s->del_stop, 1);
  pthread_mutex_lock(&s->mu);
  if (s->del_in >= 0) { /* хелпер --rm видит EOF на stdin и останавливается */
    close(s->del_in);
    s->del_in = -1;
  }
  pthread_mutex_unlock(&s->mu);
}

int sess_mark_err(session *s, uint32_t node) {
  arena *a = sess_arena(s);
  if (!a || node == 0 || node >= atomic_load(&a->h->count)) return -EINVAL;
  a->flags[node] |= F_ERR;
  return 0;
}

int sess_wait(session *s) {
  if (s->have_thread) {
    pthread_join(s->th, NULL);
    s->have_thread = 0;
  }
  return atomic_load(&s->state);
}

arena *sess_arena(session *s) {
  int st = atomic_load_explicit(&s->state, memory_order_acquire);
  if (st != ST_DONE && st != ST_FULL) return NULL;
  return atomic_load(&s->ap);
}

void sess_free(session *s) {
  if (!s) return;
  sess_cancel(s);
  sess_wait(s);
  if (s->have_err_th) pthread_join(s->err_th, NULL);
  if (s->ib) index_end(s->ib);
  pthread_mutex_lock(&s->mu);
  if (s->in_fd >= 0) close(s->in_fd);
  if (s->del_in >= 0) close(s->del_in);
  pthread_mutex_unlock(&s->mu);
  if (s->out_fd >= 0) close(s->out_fd);
  if (s->err_fd >= 0) close(s->err_fd);
  if (s->mem_fd >= 0) close(s->mem_fd);
  arena_unmap(&s->a);
  arena_unmap(&s->mem);
  for (int i = 0; i < 4; i++) free(s->pfx[i]);
  pthread_mutex_destroy(&s->mu);
  free(s);
}
