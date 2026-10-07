#include "rmtree.h"

#include <dirent.h>
#include <errno.h>
#include <fcntl.h>
#include <limits.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/statfs.h>
#include <unistd.h>

#define RM_MAX_DEPTH 4096
/* Стек потока удаления. Рекурсия rm_at↔walk берёт ~350–450 Б на уровень (с ASan больше),
 * на RM_MAX_DEPTH это ~1,5–2 МБ, а у вызывающего (Java-поток ancdu-io) ~1 МБ: обход идёт
 * на собственном потоке с этим стеком, вызывающий его ждёт. */
#define RM_STACK_SIZE (8u << 20)
#define RM_MAX_THREADS 64
/* Очередь мала намеренно: каталог с заданиями в очереди держит открытый fd. */
#define RM_QUEUE 256

/* Каталог дерева. Открыт (d), пока его не удалят: по dirfd(d) работают задания детей.
 * refs = 1 (обход ещё читает каталог) + задания-не-каталоги в полёте + живые подкаталоги.
 * Последний release удаляет каталог (rmdir) и отпускает родителя — пост-порядок. */
typedef struct rdir {
  struct rdir *parent;
  DIR *d;
  int pfd;    /* fd родителя (AT_FDCWD у вершины): родитель открыт, пока жив ребёнок */
  char *name; /* имя в родителе; у вершины — путь (не освобождается) */
  int own_name;
  _Atomic int refs;
} rdir;

typedef struct {
  rdir *dir;
  char name[NAME_MAX + 1];
} rm_job;

typedef struct {
  dev_t dev; /* устройство вершины: всё на другом — -EXDEV, частичное удаление */
  const rm_expect *want; /* ожидаемый объект вершины (NULL или ino 0 — без сверки) */
  _Atomic uint64_t *done;
  _Atomic int *stop;
  _Atomic int err;  /* первая ошибка (-errno) */
  _Atomic int intr; /* стоп помешал что-то удалить */
  int workers;      /* 0 — задания выполняет сам обход */
  pthread_mutex_t mu;
  pthread_cond_t not_empty, not_full;
  rm_job *q;
  unsigned head, len;
  int closed;
} rm_ctx;

static int stopped(rm_ctx *c) {
  if (c->stop && atomic_load_explicit(c->stop, memory_order_relaxed)) {
    atomic_store(&c->intr, 1);
    return 1;
  }
  return 0;
}

static void set_err(rm_ctx *c, int e) {
  int z = 0;
  if (e) atomic_compare_exchange_strong(&c->err, &z, e);
}

static void count(rm_ctx *c) {
  if (c->done) atomic_fetch_add_explicit(c->done, 1, memory_order_relaxed);
}

/* Снять ссылку; последняя удаляет каталог (кроме стопа) и отпускает родителя. */
static void release(rm_ctx *c, rdir *x) {
  while (x && atomic_fetch_sub_explicit(&x->refs, 1, memory_order_acq_rel) == 1) {
    rdir *p = x->parent;
    if (!stopped(c)) {
      if (unlinkat(x->pfd, x->name, AT_REMOVEDIR) == 0) count(c);
      else set_err(c, -errno);
    }
    closedir(x->d);
    if (x->own_name) free(x->name);
    free(x);
    x = p;
  }
}

/* Не каталог: unlinkat. EBUSY — точка монтирования (bind-файл с другой ФС): -EXDEV,
 * пропуск. Подмена на каталог после readdir — EISDIR, ошибка без спуска.
 * Снимает ссылку задания на x. */
static void unlink_entry(rm_ctx *c, rdir *x, const char *name) {
  if (!stopped(c)) {
    if (unlinkat(dirfd(x->d), name, 0) == 0) count(c);
    else set_err(c, errno == EBUSY ? -EXDEV : -errno);
  }
  release(c, x);
}

static void *worker(void *p) {
  rm_ctx *c = p;
  rm_job j;
  for (;;) {
    pthread_mutex_lock(&c->mu);
    while (!c->len && !c->closed) pthread_cond_wait(&c->not_empty, &c->mu);
    if (!c->len) {
      pthread_mutex_unlock(&c->mu);
      return NULL;
    }
    j = c->q[c->head];
    c->head = (c->head + 1) % RM_QUEUE;
    c->len--;
    pthread_cond_signal(&c->not_full);
    pthread_mutex_unlock(&c->mu);
    unlink_entry(c, j.dir, j.name);
  }
}

/* Задание: удалить не-каталог name в x. Ссылку на x вызывающий уже взял — она уходит
 * в задание. Без рабочих — выполняется сразу. */
static void submit(rm_ctx *c, rdir *x, const char *name) {
  size_t n = strlen(name);
  if (!c->workers || n > NAME_MAX) {
    unlink_entry(c, x, name);
    return;
  }
  pthread_mutex_lock(&c->mu);
  while (c->len == RM_QUEUE) pthread_cond_wait(&c->not_full, &c->mu);
  rm_job *j = &c->q[(c->head + c->len) % RM_QUEUE];
  j->dir = x;
  memcpy(j->name, name, n + 1);
  c->len++;
  pthread_cond_signal(&c->not_empty);
  pthread_mutex_unlock(&c->mu);
}

static void walk(rm_ctx *c, rdir *x, int depth);

/* Полная проверка записи name каталога dfd (parent — его rdir, NULL у вершины; ссылка
 * вызывающего на parent переходит сюда): чужое устройство — -EXDEV; не каталог —
 * unlinkat; каталог — openat, сверка st_dev/st_ino с fstatat (подмена между ними —
 * пропуск, как чужая ФС) и обход. */
static void rm_at(rm_ctx *c, rdir *parent, int dfd, const char *name, int depth) {
  struct stat st;
  int e = 0;
  if (fstatat(dfd, name, &st, AT_SYMLINK_NOFOLLOW) != 0) {
    e = -errno;
  } else if (!parent && c->want && c->want->ino &&
             ((uint64_t)st.st_dev != c->want->dev || (uint64_t)st.st_ino != c->want->ino)) {
    e = -ESTALE; /* вершина — не тот объект, что видел скан: ничего не трогаем */
  } else if (st.st_dev != c->dev) {
    e = -EXDEV;
  } else if (!S_ISDIR(st.st_mode)) {
    if (parent) {
      submit(c, parent, name);
      return;
    }
    if (unlinkat(dfd, name, 0) == 0) count(c);
    else e = errno == EBUSY ? -EXDEV : -errno;
  } else if (depth > RM_MAX_DEPTH) {
    e = -ELOOP;
  } else {
    int fd = openat(dfd, name, O_RDONLY | O_DIRECTORY | O_CLOEXEC | O_NOFOLLOW);
    struct stat ost;
    DIR *d = NULL;
    if (fd < 0) e = -errno;
    else if (fstat(fd, &ost) != 0 || ost.st_dev != st.st_dev || ost.st_ino != st.st_ino)
      e = -EXDEV;
    else if (!(d = fdopendir(fd))) e = -errno;
    rdir *x = d ? calloc(1, sizeof *x) : NULL;
    char *nm = x && parent ? strdup(name) : NULL;
    if (d && (!x || (parent && !nm))) e = -ENOMEM;
    if (!e) {
      x->parent = parent; /* ссылка на parent теперь у x */
      x->d = d;
      x->pfd = dfd;
      x->name = parent ? nm : (char *)name;
      x->own_name = parent != NULL;
      atomic_init(&x->refs, 1);
      walk(c, x, depth);
      return;
    }
    free(nm);
    free(x);
    if (d) closedir(d);
    else if (fd >= 0) close(fd);
  }
  set_err(c, e);
  if (parent) release(c, parent);
}

static void walk(rm_ctx *c, rdir *x, int depth) {
  int fd = dirfd(x->d);
  struct dirent *e;
  while ((e = readdir(x->d))) {
    const char *n = e->d_name;
    if (n[0] == '.' && (n[1] == 0 || (n[1] == '.' && n[2] == 0))) continue;
    if (stopped(c)) break;
    atomic_fetch_add_explicit(&x->refs, 1, memory_order_relaxed);
    switch (e->d_type) {
      /* Не каталог по d_type: сразу unlinkat, без fstatat. */
      case DT_REG: case DT_LNK: case DT_FIFO: case DT_SOCK: case DT_CHR: case DT_BLK:
        submit(c, x, n);
        break;
      default: /* DT_DIR, DT_UNKNOWN — полные проверки */
        rm_at(c, x, fd, n, depth + 1);
    }
  }
  release(c, x); /* каталог дочитан */
}

int rm_tree_target(const char *path, char *buf, size_t cap) {
  /* "link/" разыменовывается даже с AT_SYMLINK_NOFOLLOW/O_NOFOLLOW —
   * срезаем завершающие слеши, чтобы удалялась сама ссылка. */
  size_t n = strlen(path);
  while (n > 1 && path[n - 1] == '/') n--;
  if (n >= cap) return -ENAMETOOLONG;
  memcpy(buf, path, n);
  buf[n] = 0;
  /* Последний компонент «.» или «..» обходит проверку точки монтирования в rm_tree:
   * у «/mnt/point/.» родитель — сама «/mnt/point», устройство то же. Пустой («/» или
   * одни слеши, «») — корень ФС: родителя нет, удалять нечего и нельзя. */
  const char *slash = strrchr(buf, '/');
  const char *last = slash ? slash + 1 : buf;
  if (!*last || strcmp(last, ".") == 0 || strcmp(last, "..") == 0) return -EINVAL;
  return 0;
}

static int rm_tree_run(const char *path, int threads, _Atomic uint64_t *done,
                       _Atomic int *stop, const rm_expect *want) {
  /* Отказ до любых lstat/open/unlink. */
  char buf[PATH_MAX];
  int v = rm_tree_target(path, buf, sizeof buf);
  if (v) return v;
  const char *slash = strrchr(buf, '/');
  struct stat st, pst;
  if (lstat(buf, &st) != 0) return -errno;
  /* Вершина — точка монтирования (или bind-файл): её устройство отличается от родителя. */
  char parent[PATH_MAX];
  if (!slash) {
    strcpy(parent, ".");
  } else if (slash == buf) {
    strcpy(parent, "/");
  } else {
    memcpy(parent, buf, (size_t)(slash - buf));
    parent[slash - buf] = 0;
  }
  if (stat(parent, &pst) != 0) return -errno;
  if (st.st_dev != pst.st_dev) return -EXDEV;

  rm_ctx c;
  memset(&c, 0, sizeof c);
  c.dev = st.st_dev;
  c.want = want;
  c.done = done;
  c.stop = stop;
  if (stopped(&c)) return -EINTR;
  pthread_t th[RM_MAX_THREADS];
  int nt = S_ISDIR(st.st_mode) ? threads : 0;
  if (nt > RM_MAX_THREADS) nt = RM_MAX_THREADS;
  if (nt > 1 && (c.q = malloc(sizeof(rm_job) * RM_QUEUE))) {
    pthread_mutex_init(&c.mu, NULL);
    pthread_cond_init(&c.not_empty, NULL);
    pthread_cond_init(&c.not_full, NULL);
    while (c.workers < nt && pthread_create(&th[c.workers], NULL, worker, &c) == 0) c.workers++;
  }
  rm_at(&c, NULL, AT_FDCWD, buf, 0);
  if (c.q) {
    pthread_mutex_lock(&c.mu);
    c.closed = 1;
    pthread_cond_broadcast(&c.not_empty);
    pthread_mutex_unlock(&c.mu);
    /* После join все задания выполнены и последний release вершины прошёл. */
    for (int i = 0; i < c.workers; i++) pthread_join(th[i], NULL);
    pthread_cond_destroy(&c.not_full);
    pthread_cond_destroy(&c.not_empty);
    pthread_mutex_destroy(&c.mu);
    free(c.q);
  }
  if (atomic_load(&c.intr)) return -EINTR;
  return atomic_load(&c.err);
}

typedef struct {
  const char *path;
  int threads;
  _Atomic uint64_t *done;
  _Atomic int *stop;
  const rm_expect *want;
  int result;
} rm_call;

static void *rm_call_main(void *p) {
  rm_call *k = p;
  k->result = rm_tree_run(k->path, k->threads, k->done, k->stop, k->want);
  return NULL;
}

/* fn(arg) на новом потоке со стеком RM_STACK_SIZE; ждёт его. 0 или -errno (поток не создан —
 * fn не вызывалась, ничего не тронуто). */
static int on_big_stack(void *(*fn)(void *), void *arg) {
  pthread_attr_t at;
  pthread_t th;
  int e = pthread_attr_init(&at);
  if (e) return -e;
  e = pthread_attr_setstacksize(&at, RM_STACK_SIZE);
  if (!e) e = pthread_create(&th, &at, fn, arg);
  pthread_attr_destroy(&at);
  if (e) return -e;
  pthread_join(th, NULL);
  return 0;
}

int rm_tree_expect(const char *path, int threads, _Atomic uint64_t *done, _Atomic int *stop,
                   const rm_expect *want) {
  rm_call k = {path, threads, done, stop, want, 0};
  int e = on_big_stack(rm_call_main, &k);
  return e ? e : k.result;
}

int rm_tree_ex(const char *path, int threads, _Atomic uint64_t *done, _Atomic int *stop) {
  return rm_tree_expect(path, threads, done, stop, NULL);
}

int rm_tree(const char *path) { return rm_tree_ex(path, 1, NULL, NULL); }

#define RM_FUSE_SUPER_MAGIC 0x65735546

int rm_default_threads(const char *path) {
  struct statfs sf;
  if (statfs(path, &sf) != 0 || (unsigned long)sf.f_type != RM_FUSE_SUPER_MAGIC) return 1;
  long n = sysconf(_SC_NPROCESSORS_ONLN);
  return n < 1 ? 1 : n > 6 ? 6 : (int)n;
}

int rm_parent_real(const char *path) {
  char buf[PATH_MAX], real[PATH_MAX];
  int v = rm_tree_target(path, buf, sizeof buf);
  if (v) return v;
  if (buf[0] != '/') return -EINVAL;
  char *slash = strrchr(buf, '/');
  if (slash == buf) return 0; /* родитель — «/» */
  *slash = 0;
  if (!realpath(buf, real)) return -errno;
  return strcmp(real, buf) == 0 ? 0 : -ELOOP;
}
