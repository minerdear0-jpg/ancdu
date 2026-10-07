#include "scan.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <sys/syscall.h>
#include <unistd.h>

#define DENTS_BUF (64 * 1024)
#define PUSH_BATCH 64
#define HL_SHARDS 64
#define FUSE_SUPER_MAGIC 0x65735546
#define CUR_PATH_NS 50000000LL

struct linux_dirent64 {
  uint64_t d_ino;
  int64_t d_off;
  unsigned short d_reclen;
  unsigned char d_type;
  char d_name[];
};

typedef struct {
  uint32_t node;
  uint32_t slot; /* ребёнок корня, к которому относится поддерево */
  char *path;
} work;

typedef struct {
  pthread_mutex_t mu;
  uint64_t *keys; /* пары (dev, ino); ino == 0 — пусто */
  size_t cap, used;
} hl_shard;

typedef struct {
  arena *a;
  const scan_opts *o;
  dev_t root_dev;
  pthread_mutex_t mu;
  pthread_cond_t cv;
  work *stack;
  size_t len, cap;
  int active;
  int failed;
  hl_shard hl[HL_SHARDS];
} scan_ctx;

static uint64_t mix(uint64_t x) {
  x ^= x >> 33;
  x *= 0xff51afd7ed558ccdULL;
  x ^= x >> 33;
  x *= 0xc4ceb9fe1a85ec53ULL;
  x ^= x >> 33;
  return x;
}

static void hl_put(uint64_t *keys, size_t cap, uint64_t h, uint64_t dev, uint64_t ino) {
  size_t m = cap - 1, i = (h / HL_SHARDS) & m;
  while (keys[i * 2 + 1] != 0) i = (i + 1) & m;
  keys[i * 2] = dev;
  keys[i * 2 + 1] = ino;
}

/* 1 — впервые, 0 — уже видели, -1 — нет памяти. */
static int hl_insert(scan_ctx *c, uint64_t dev, uint64_t ino) {
  uint64_t h = mix(dev * 0x9E3779B97F4A7C15ULL ^ ino);
  hl_shard *s = &c->hl[h % HL_SHARDS];
  int r = 1;
  pthread_mutex_lock(&s->mu);
  if ((s->used + 1) * 2 > s->cap) {
    size_t nc = s->cap ? s->cap * 2 : 256;
    uint64_t *nk = calloc(nc * 2, sizeof(uint64_t));
    if (!nk) {
      pthread_mutex_unlock(&s->mu);
      return -1;
    }
    for (size_t i = 0; i < s->cap; i++)
      if (s->keys[i * 2 + 1])
        hl_put(nk, nc, mix(s->keys[i * 2] * 0x9E3779B97F4A7C15ULL ^ s->keys[i * 2 + 1]),
               s->keys[i * 2], s->keys[i * 2 + 1]);
    free(s->keys);
    s->keys = nk;
    s->cap = nc;
  }
  size_t m = s->cap - 1, i = (h / HL_SHARDS) & m;
  for (;; i = (i + 1) & m) {
    uint64_t *k = &s->keys[i * 2];
    if (k[1] == 0) {
      k[0] = dev;
      k[1] = ino;
      s->used++;
      break;
    }
    if (k[0] == dev && k[1] == ino) {
      r = 0;
      break;
    }
  }
  pthread_mutex_unlock(&s->mu);
  return r;
}

static int push_all(scan_ctx *c, work *w, size_t n) {
  pthread_mutex_lock(&c->mu);
  if (c->len + n > c->cap) {
    size_t nc = c->cap ? c->cap : 1024;
    while (nc < c->len + n) nc *= 2;
    work *ns = realloc(c->stack, nc * sizeof *ns);
    if (!ns) {
      c->failed = 1;
      atomic_store(&c->a->h->cancel, ANCDU_CANCEL_USER);
      pthread_cond_broadcast(&c->cv);
      pthread_mutex_unlock(&c->mu);
      for (size_t i = 0; i < n; i++) free(w[i].path);
      return -1;
    }
    c->stack = ns;
    c->cap = nc;
  }
  memcpy(c->stack + c->len, w, n * sizeof *w);
  c->len += n;
  if (n == 1)
    pthread_cond_signal(&c->cv);
  else
    pthread_cond_broadcast(&c->cv);
  pthread_mutex_unlock(&c->mu);
  return 0;
}

static int skip_path(const char *p) {
  return !strcmp(p, "/proc") || !strcmp(p, "/sys") || !strcmp(p, "/dev");
}

static char *join(const char *dir, const char *name, size_t nl) {
  size_t dl = strlen(dir);
  int slash = dl > 0 && dir[dl - 1] == '/';
  char *p = malloc(dl + !slash + nl + 1);
  if (!p) return NULL;
  memcpy(p, dir, dl);
  if (!slash) p[dl++] = '/';
  memcpy(p + dl, name, nl);
  p[dl + nl] = 0;
  return p;
}

/* Не чаще раза в 50 мс; единственный писатель захватывает seqlock CAS-ом. */
static void note_path(arena *a, const char *path) {
  ancdu_hdr *h = a->h;
  int64_t now = ancdu_now_ns();
  if (now - atomic_load_explicit(&h->cur_ns, memory_order_relaxed) < CUR_PATH_NS) return;
  uint32_t s = atomic_load_explicit(&h->cur_seq, memory_order_relaxed);
  if ((s & 1) || !atomic_compare_exchange_strong(&h->cur_seq, &s, s + 1)) return;
  atomic_store_explicit(&h->cur_ns, now, memory_order_relaxed);
  size_t l = strlen(path), max = sizeof h->cur_path - 1;
  if (l > max) {
    path += l - max; /* хвост пути информативнее начала */
    l = max;
  }
  memcpy(h->cur_path, path, l);
  h->cur_path[l] = 0;
  atomic_store_explicit(&h->cur_seq, s + 2, memory_order_release);
}

static void mark_err(arena *a, uint32_t idx) {
  a->flags[idx] |= F_ERR;
  atomic_fetch_add_explicit(&a->h->errors, 1, memory_order_relaxed);
}

static void set_sizes(scan_ctx *c, uint32_t idx, const struct stat *st, uint32_t slot) {
  arena *a = c->a;
  if (!S_ISDIR(st->st_mode) && st->st_nlink > 1 &&
      hl_insert(c, (uint64_t)st->st_dev, (uint64_t)st->st_ino) == 0) {
    a->flags[idx] |= F_HLDUP;
    return;
  }
  uint64_t d = (uint64_t)st->st_blocks * 512;
  a->disk[idx] = d;
  /* Как GNU du --apparent-size: размер самих каталогов не учитывается. */
  a->apparent[idx] = S_ISDIR(st->st_mode) ? 0 : (uint64_t)st->st_size;
  atomic_fetch_add_explicit(&a->h->bytes, d, memory_order_relaxed);
  if (slot)
    atomic_fetch_add_explicit(
        &a->h->live_disk[slot < ANCDU_LIVE_SLOTS ? slot : ANCDU_LIVE_SLOTS - 1], d,
        memory_order_relaxed);
}

static void process_dir(scan_ctx *c, const work *w, char *buf, name_chunk *ck) {
  arena *a = c->a;
  note_path(a, w->path);
  int fd = open(w->path, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOATIME);
  if (fd < 0 && errno == EPERM) fd = open(w->path, O_RDONLY | O_DIRECTORY | O_CLOEXEC);
  if (fd < 0) {
    mark_err(a, w->node);
    return;
  }
  work batch[PUSH_BATCH];
  size_t nb = 0;
  for (;;) {
    long n = syscall(SYS_getdents64, fd, buf, DENTS_BUF);
    if (n < 0) {
      mark_err(a, w->node);
      break;
    }
    if (n == 0) break;
    for (long off = 0; off < n;) {
      struct linux_dirent64 *e = (struct linux_dirent64 *)(buf + off);
      off += e->d_reclen;
      const char *nm = e->d_name;
      if (nm[0] == '.' && (nm[1] == 0 || (nm[1] == '.' && nm[2] == 0))) continue;
      if (atomic_load_explicit(&a->h->cancel, memory_order_relaxed)) goto out;
      size_t nl = strlen(nm);
      struct stat st;
      int sr = fstatat(fd, nm, &st, AT_SYMLINK_NOFOLLOW);
      uint8_t fl = 0;
      if (sr == 0) {
        if (S_ISDIR(st.st_mode))
          fl = F_DIR;
        else if (S_ISLNK(st.st_mode))
          fl = F_SYMLINK;
      } else if (e->d_type == DT_DIR) {
        fl = F_DIR;
      }
      uint32_t idx = arena_new_node(a, ck, w->node, nm, nl, fl);
      if (idx == ANCDU_NONE) goto out;
      atomic_fetch_add_explicit(&a->h->files, 1, memory_order_relaxed);
      uint32_t slot = w->node == 0 ? idx : w->slot;
      if (sr != 0) {
        mark_err(a, idx);
        continue;
      }
      if (c->o->one_fs && st.st_dev != c->root_dev) {
        a->flags[idx] |= F_OTHERFS;
        continue;
      }
      /* Идентичность для удаления: только на ФС корня (dev = root_dev). */
      if (st.st_dev == c->root_dev) a->ino[idx] = (uint64_t)st.st_ino;
      set_sizes(c, idx, &st, slot);
      if (fl & F_DIR) {
        char *p = join(w->path, nm, nl);
        if (!p) {
          mark_err(a, idx);
          continue;
        }
        if (skip_path(p)) {
          free(p);
          continue;
        }
        batch[nb++] = (work){idx, slot, p};
        if (nb == PUSH_BATCH) {
          push_all(c, batch, nb);
          nb = 0;
        }
      }
    }
  }
out:
  if (nb) push_all(c, batch, nb);
  close(fd);
}

static void *worker(void *p) {
  scan_ctx *c = p;
  char *buf = malloc(DENTS_BUF);
  name_chunk ck = {0, 0};
  for (;;) {
    pthread_mutex_lock(&c->mu);
    while (c->len == 0 && c->active > 0 && !atomic_load(&c->a->h->cancel))
      pthread_cond_wait(&c->cv, &c->mu);
    if (c->len == 0 || atomic_load(&c->a->h->cancel)) {
      pthread_cond_broadcast(&c->cv);
      pthread_mutex_unlock(&c->mu);
      break;
    }
    work w = c->stack[--c->len];
    c->active++;
    pthread_mutex_unlock(&c->mu);
    if (buf)
      process_dir(c, &w, buf, &ck);
    else
      mark_err(c->a, w.node);
    free(w.path);
    pthread_mutex_lock(&c->mu);
    if (--c->active == 0) pthread_cond_broadcast(&c->cv);
    pthread_mutex_unlock(&c->mu);
  }
  free(buf);
  return NULL;
}

int scan_default_threads(const char *root) {
  long n = sysconf(_SC_NPROCESSORS_ONLN);
  if (n < 1) n = 1;
  if (n > 16) n = 16;
  struct statfs sf;
  /* Замер на DUT через FUSE: t4 292 мс, t6 155-190, t8 ~190 — потолок 6. */
  if (statfs(root, &sf) == 0 && (unsigned long)sf.f_type == FUSE_SUPER_MAGIC && n > 6) n = 6;
  return (int)n;
}

int scan_run(arena *a, const scan_opts *o) {
  ancdu_hdr *h = a->h;
  const char *root = h->root_path;
  struct stat st;
  /* Корень разыменовывается (/sdcard — симлинк); внутри симлинки не обходятся. */
  if (stat(root, &st) != 0 || !S_ISDIR(st.st_mode)) return ST_FAILED;

  scan_ctx c;
  memset(&c, 0, sizeof c);
  c.a = a;
  c.o = o;
  c.root_dev = st.st_dev;
  h->root_dev = (uint64_t)st.st_dev;
  pthread_mutex_init(&c.mu, NULL);
  pthread_cond_init(&c.cv, NULL);
  for (int i = 0; i < HL_SHARDS; i++) pthread_mutex_init(&c.hl[i].mu, NULL);

  int result = ST_DONE;
  name_chunk ck = {0, 0};
  if (arena_new_node(a, &ck, ANCDU_NONE, "", 0, F_DIR) == ANCDU_NONE) {
    result = ST_FULL;
    goto done;
  }
  a->ino[0] = (uint64_t)st.st_ino;
  set_sizes(&c, 0, &st, 0);

  /* Корень читает один поток: его дети получают индексы 1..k подряд. */
  char *buf = malloc(DENTS_BUF);
  work w0 = {0, 0, strdup(root)};
  if (!buf || !w0.path) {
    free(buf);
    free(w0.path);
    result = ST_FAILED;
    goto done;
  }
  process_dir(&c, &w0, buf, &ck);
  free(w0.path);
  free(buf);
  uint64_t k = atomic_load(&h->count) - 1;
  atomic_store(&h->live_count,
               (uint32_t)(k < ANCDU_LIVE_SLOTS - 1 ? k : ANCDU_LIVE_SLOTS - 1));

  int nt = o->threads > 0 ? o->threads : scan_default_threads(root);
  if (nt > 64) nt = 64;
  pthread_t th[64];
  int started = 0;
  for (int i = 0; i < nt; i++)
    if (pthread_create(&th[started], NULL, worker, &c) == 0) started++;
  if (started == 0) worker(&c);
  for (int i = 0; i < started; i++) pthread_join(th[i], NULL);

done:
  for (size_t i = 0; i < c.len; i++) free(c.stack[i].path);
  free(c.stack);
  for (int i = 0; i < HL_SHARDS; i++) {
    free(c.hl[i].keys);
    pthread_mutex_destroy(&c.hl[i].mu);
  }
  pthread_cond_destroy(&c.cv);
  pthread_mutex_destroy(&c.mu);
  atomic_store(&h->finished_ns, ancdu_now_ns());
  if (result != ST_DONE) return result;
  if (c.failed) return ST_FAILED;
  uint32_t cancel = atomic_load(&h->cancel);
  if (cancel == ANCDU_CANCEL_FULL) return ST_FULL;
  if (cancel == ANCDU_CANCEL_USER) return ST_CANCELLED;
  return ST_DONE;
}
