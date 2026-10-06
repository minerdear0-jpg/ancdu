#pragma once
#include <pthread.h>
#include <sys/types.h>

#include "index.h"
#include "scan.h"
#include "session.h"

struct session {
  _Atomic int state;
  _Atomic int cancelled;
  arena a;                /* итоговая арена: скан, индекс, кэш, pipe-дамп */
  arena mem;              /* общая memfd-арена root-скана */
  _Atomic(arena *) ap;    /* какая арена сейчас видна (NULL — ещё нет) */
  pthread_t th;
  int have_thread;
  scan_opts opts;
  index_builder *ib;
  int64_t started_ns;
  _Atomic int64_t finished_ns;
  /* root */
  char *pfx[4];
  char helper[1024];
  char root[1024];
  int one_fs;
  pid_t pid;
  int in_fd, out_fd, err_fd, mem_fd;
  _Atomic int used_memfd;
  pthread_t err_th;
  int have_err_th;
  _Atomic uint64_t p_files, p_bytes, p_errors;
  pthread_mutex_t mu; /* cur, err, in_fd */
  char cur[512];
  char err[1024];
};

session *sess_alloc(void);
void sess_set_error(session *s, const char *fmt, ...);
void sess_finish(session *s, int state);
