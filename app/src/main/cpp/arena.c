#include "arena.h"

#include <errno.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <sys/statvfs.h>
#include <time.h>
#include <unistd.h>

#define ALIGN64(x) (((x) + 63u) & ~(uint64_t)63u)

int64_t ancdu_now_ns(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/* Заполняет смещения h (если не NULL) и возвращает полный размер. */
static uint64_t layout(ancdu_hdr *h, uint64_t n, uint64_t names) {
  uint64_t o = ANCDU_HDR_SIZE;
#define PLACE(field, elem)                 \
  do {                                     \
    if (h) h->field = o;                   \
    o = ALIGN64(o + (uint64_t)(elem) * n); \
  } while (0)
  PLACE(off_parent, 4);
  PLACE(off_disk, 8);
  PLACE(off_apparent, 8);
  PLACE(off_items, 4);
  PLACE(off_name_off, 4);
  PLACE(off_name_len, 2);
  PLACE(off_flags, 1);
  PLACE(off_child_start, 4);
  PLACE(off_child_count, 4);
  PLACE(off_order, 4);
  PLACE(off_ino, 8);
#undef PLACE
  if (h) h->off_names = o;
  o = ALIGN64(o + names);
  if (h) h->total_size = o;
  return o;
}

size_t arena_bytes(uint64_t cap_nodes, uint64_t cap_names) {
  return (size_t)layout(NULL, cap_nodes, cap_names);
}

void arena_format(void *base, uint64_t cap_nodes, uint64_t cap_names,
                  const char *root, uint32_t source) {
  ancdu_hdr *h = base;
  memset(h, 0, ANCDU_HDR_SIZE);
  memcpy(h->magic, ANCDU_MAGIC, 8);
  h->version = ANCDU_VERSION;
  h->source = source;
  h->cap_nodes = cap_nodes;
  h->cap_names = cap_names;
  strncpy(h->root_path, root, sizeof h->root_path - 1);
  h->started_ns = ancdu_now_ns();
  layout(h, cap_nodes, cap_names);
}

int arena_attach(arena *a, void *base, size_t size) {
  ancdu_hdr *h = base;
  if (size < ANCDU_HDR_SIZE || memcmp(h->magic, ANCDU_MAGIC, 8) != 0 ||
      h->version != ANCDU_VERSION)
    return -EINVAL;
  /* Ёмкости читаются из заголовка один раз: проверка, раскладка и всё дальнейшее — по копии. */
  uint64_t cn = *(volatile uint64_t *)&h->cap_nodes, cm = *(volatile uint64_t *)&h->cap_names;
  if (cn >= ANCDU_NONE || cm > ANCDU_MAX_NAMES) return -EINVAL;
  /* Смещения берём из собственного расчёта, не из заголовка. */
  ancdu_hdr L;
  if (layout(&L, cn, cm) > size) return -EINVAL;
  if (atomic_load(&h->count) > cn || atomic_load(&h->names_used) > cm) return -EINVAL;
  char *b = base;
  memset(a, 0, sizeof *a);
  a->h = h;
  a->base = base;
  a->size = size;
  a->cap_nodes = cn;
  a->cap_names = cm;
  a->parent = (uint32_t *)(b + L.off_parent);
  a->disk = (uint64_t *)(b + L.off_disk);
  a->apparent = (uint64_t *)(b + L.off_apparent);
  a->items = (uint32_t *)(b + L.off_items);
  a->name_off = (uint32_t *)(b + L.off_name_off);
  a->name_len = (uint16_t *)(b + L.off_name_len);
  a->flags = (uint8_t *)(b + L.off_flags);
  a->child_start = (uint32_t *)(b + L.off_child_start);
  a->child_count = (uint32_t *)(b + L.off_child_count);
  a->order = (uint32_t *)(b + L.off_order);
  a->ino = (uint64_t *)(b + L.off_ino);
  a->names = b + L.off_names;
  return 0;
}

int arena_alloc_anon(arena *a, uint64_t cap_nodes, uint64_t cap_names,
                     const char *root, uint32_t source) {
  if (cap_nodes >= ANCDU_NONE || cap_names > ANCDU_MAX_NAMES) return -EINVAL;
  size_t size = arena_bytes(cap_nodes, cap_names);
  void *base = mmap(NULL, size, PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
  if (base == MAP_FAILED) return -errno;
  arena_format(base, cap_nodes, cap_names, root, source);
  int r = arena_attach(a, base, size);
  if (r) {
    munmap(base, size);
    return r;
  }
  a->owned = 1;
  return 0;
}

void arena_unmap(arena *a) {
  if (a->owned && a->base) munmap(a->base, a->size);
  memset(a, 0, sizeof *a);
}

/* Резервирует до want (не меньше need) единиц счётчика, не выходя за cap.
 * Возвращает взятое количество (0 — нет места). */
static uint64_t reserve(_Atomic uint64_t *ctr, uint64_t cap, uint64_t want,
                        uint64_t need, uint64_t *start) {
  uint64_t cur = atomic_load_explicit(ctr, memory_order_relaxed), take;
  do {
    if (cur > cap || cap - cur < need) return 0;
    take = cap - cur < want ? cap - cur : want;
  } while (!atomic_compare_exchange_weak_explicit(
      ctr, &cur, cur + take, memory_order_relaxed, memory_order_relaxed));
  *start = cur;
  return take;
}

uint32_t arena_new_node(arena *a, name_chunk *ck, uint32_t parent,
                        const char *name, size_t len, uint8_t flags) {
  if (len > ANCDU_MAX_NAME) len = ANCDU_MAX_NAME;
  uint64_t need = len + 1, start, idx;
  if (ck->end - ck->pos < need) {
    uint64_t want = need > ANCDU_NAME_CHUNK ? need : ANCDU_NAME_CHUNK;
    uint64_t got = reserve(&a->h->names_used, a->cap_names, want, need, &start);
    if (!got) goto full;
    ck->pos = start;
    ck->end = start + got;
  }
  if (!reserve(&a->h->count, a->cap_nodes, 1, 1, &idx)) goto full;
  a->parent[idx] = parent;
  a->disk[idx] = 0;
  a->apparent[idx] = 0;
  a->items[idx] = 1;
  a->name_off[idx] = (uint32_t)ck->pos;
  a->name_len[idx] = (uint16_t)len;
  a->flags[idx] = flags;
  a->child_start[idx] = 0;
  a->child_count[idx] = 0;
  a->ino[idx] = 0;
  memcpy(a->names + ck->pos, name, len);
  a->names[ck->pos + len] = 0;
  ck->pos += need;
  return (uint32_t)idx;
full:
  atomic_store(&a->h->cancel, ANCDU_CANCEL_FULL);
  return ANCDU_NONE;
}

int arena_path(const arena *a, uint32_t n, char *buf, size_t cap) {
  uint32_t chain[ANCDU_MAX_DEPTH];
  int d = 0;
  for (uint32_t x = n; x != 0; x = a->parent[x]) {
    if (x == ANCDU_NONE || d == ANCDU_MAX_DEPTH) return -EINVAL;
    chain[d++] = x;
  }
  size_t len = strnlen(a->h->root_path, sizeof a->h->root_path);
  if (len + 1 > cap) return -ENAMETOOLONG;
  memcpy(buf, a->h->root_path, len);
  for (int i = d - 1; i >= 0; i--) {
    uint32_t x = chain[i];
    size_t nl = a->name_len[x];
    int slash = len > 0 && buf[len - 1] == '/';
    if (len + !slash + nl + 1 > cap) return -ENAMETOOLONG;
    if (!slash) buf[len++] = '/';
    memcpy(buf + len, arena_name(a, x), nl);
    len += nl;
  }
  buf[len] = 0;
  return (int)len;
}

int arena_validate(const arena *a) {
  uint64_t n = atomic_load(&a->h->count), nu = atomic_load(&a->h->names_used);
  if (n > a->cap_nodes || nu > a->cap_names) return -EINVAL;
  if (n == 0) return 0;
  if (a->parent[0] != ANCDU_NONE) return -EINVAL;
  for (uint64_t i = 0; i < n; i++) {
    if (i && a->parent[i] >= i) return -EINVAL;
    uint64_t end = (uint64_t)a->name_off[i] + a->name_len[i];
    if (end + 1 > nu || a->names[end] != 0) return -EINVAL;
  }
  /* Дочерние массивы проверяются всегда: state берётся из того же
   * недоверенного файла, а до post_process массивы нулевые и проходят. */
  for (uint64_t i = 0; i < n; i++) {
    if ((uint64_t)a->child_start[i] + a->child_count[i] > n - 1) return -EINVAL;
    if (i + 1 < n && a->order[i] >= n) return -EINVAL;
  }
  return 0;
}

int arena_cur_path(const arena *a, char *out, size_t cap) {
  for (int tries = 0; tries < 8; tries++) {
    uint32_t s1 = atomic_load_explicit(&a->h->cur_seq, memory_order_acquire);
    if (s1 & 1) continue;
    size_t n = strnlen(a->h->cur_path, sizeof a->h->cur_path);
    if (n >= cap) n = cap - 1;
    memcpy(out, a->h->cur_path, n);
    out[n] = 0;
    atomic_thread_fence(memory_order_acquire);
    if (atomic_load_explicit(&a->h->cur_seq, memory_order_relaxed) == s1)
      return (int)n;
  }
  out[0] = 0;
  return 0;
}

uint64_t arena_cap_hint(const char *path) {
  struct statvfs s;
  uint64_t used = 0;
  if (statvfs(path, &s) == 0 && s.f_files > s.f_ffree) used = s.f_files - s.f_ffree;
  if (used == 0 && statvfs("/data", &s) == 0 && s.f_files > s.f_ffree)
    used = s.f_files - s.f_ffree;
  if (used == 0) used = 16u << 20; /* btrfs/FUSE могут не сообщать inode */
  uint64_t cap = used + used / 4 + 65536;
  return cap >= ANCDU_NONE ? ANCDU_NONE - 1 : cap;
}

uint64_t arena_names_hint(uint64_t cap_nodes) {
  uint64_t v = cap_nodes * 40 + (64u << 20);
  return v > ANCDU_MAX_NAMES ? ANCDU_MAX_NAMES : v;
}

#define ANCDU_MAX_FILE (64ull << 30)

static int write_all(int fd, const void *p, size_t n) {
  const char *c = p;
  while (n) {
    ssize_t w = write(fd, c, n);
    if (w < 0) {
      if (errno == EINTR) continue;
      return -errno;
    }
    c += w;
    n -= (size_t)w;
  }
  return 0;
}

static int read_all(int fd, void *p, size_t n) {
  char *c = p;
  while (n) {
    ssize_t r = read(fd, c, n);
    if (r < 0) {
      if (errno == EINTR) continue;
      return -errno;
    }
    if (r == 0) return -EIO;
    c += r;
    n -= (size_t)r;
  }
  return 0;
}

/* Дописывает нули до off, затем данные. */
static int put(int fd, uint64_t *pos, uint64_t off, const void *data, size_t len) {
  static const char zeros[64];
  while (*pos < off) {
    size_t k = off - *pos < sizeof zeros ? (size_t)(off - *pos) : sizeof zeros;
    int r = write_all(fd, zeros, k);
    if (r) return r;
    *pos += k;
  }
  int r = write_all(fd, data, len);
  if (r) return r;
  *pos += len;
  return 0;
}

int arena_write(const arena *a, int fd) {
  uint64_t n = atomic_load(&a->h->count), nu = atomic_load(&a->h->names_used);
  char hb[ANCDU_HDR_SIZE];
  memcpy(hb, a->h, ANCDU_HDR_SIZE);
  ancdu_hdr *h = (ancdu_hdr *)hb;
  h->cap_nodes = n;
  h->cap_names = nu;
  uint64_t total = layout(h, n, nu);
  uint64_t pos = 0;
  int r;
#define PUT(field, ptr, elem) \
  if ((r = put(fd, &pos, h->field, ptr, (size_t)(n * (elem))))) return r
  if ((r = put(fd, &pos, 0, hb, ANCDU_HDR_SIZE))) return r;
  PUT(off_parent, a->parent, 4);
  PUT(off_disk, a->disk, 8);
  PUT(off_apparent, a->apparent, 8);
  PUT(off_items, a->items, 4);
  PUT(off_name_off, a->name_off, 4);
  PUT(off_name_len, a->name_len, 2);
  PUT(off_flags, a->flags, 1);
  PUT(off_child_start, a->child_start, 4);
  PUT(off_child_count, a->child_count, 4);
  PUT(off_order, a->order, 4);
  PUT(off_ino, a->ino, 8);
#undef PUT
  if ((r = put(fd, &pos, h->off_names, a->names, (size_t)nu))) return r;
  return put(fd, &pos, total, "", 0);
}

int arena_read_stream(arena *out, int fd) {
  char hb[ANCDU_HDR_SIZE];
  int r = read_all(fd, hb, sizeof hb);
  if (r) return r;
  ancdu_hdr *h = (ancdu_hdr *)hb;
  if (memcmp(h->magic, ANCDU_MAGIC, 8) != 0 || h->version != ANCDU_VERSION ||
      h->cap_nodes >= ANCDU_NONE || h->cap_names > ANCDU_MAX_NAMES)
    return -EINVAL;
  uint64_t size = layout(NULL, h->cap_nodes, h->cap_names);
  if (size > ANCDU_MAX_FILE) return -EINVAL;
  void *base = mmap(NULL, (size_t)size, PROT_READ | PROT_WRITE,
                    MAP_PRIVATE | MAP_ANONYMOUS | MAP_NORESERVE, -1, 0);
  if (base == MAP_FAILED) return -errno;
  memcpy(base, hb, sizeof hb);
  r = read_all(fd, (char *)base + ANCDU_HDR_SIZE, (size_t)(size - ANCDU_HDR_SIZE));
  if (!r) r = arena_attach(out, base, (size_t)size);
  if (!r && atomic_load(&out->h->count) == 0) r = -EINVAL; /* нет даже корня */
  if (!r) r = arena_validate(out);
  if (r) {
    munmap(base, (size_t)size);
    memset(out, 0, sizeof *out);
    return r;
  }
  out->owned = 1;
  return 0;
}

int arena_save_file(const arena *a, const char *path) {
  char tmp[4096];
  if (snprintf(tmp, sizeof tmp, "%s.tmp", path) >= (int)sizeof tmp) return -ENAMETOOLONG;
  int fd = open(tmp, O_WRONLY | O_CREAT | O_TRUNC | O_CLOEXEC, 0600);
  if (fd < 0) return -errno;
  int r = arena_write(a, fd);
  if (!r && fsync(fd) != 0) r = -errno;
  if (close(fd) != 0 && !r) r = -errno;
  if (!r && rename(tmp, path) != 0) r = -errno;
  if (r) unlink(tmp);
  return r;
}

int arena_open_file(arena *out, const char *path) {
  int fd = open(path, O_RDONLY | O_CLOEXEC);
  if (fd < 0) return -errno;
  struct stat st;
  if (fstat(fd, &st) != 0) {
    int e = -errno;
    close(fd);
    return e;
  }
  if (st.st_size < (off_t)ANCDU_HDR_SIZE || (uint64_t)st.st_size > ANCDU_MAX_FILE) {
    close(fd);
    return -EINVAL;
  }
  void *base = mmap(NULL, (size_t)st.st_size, PROT_READ | PROT_WRITE, MAP_PRIVATE, fd, 0);
  close(fd);
  if (base == MAP_FAILED) return -errno;
  int r = arena_attach(out, base, (size_t)st.st_size);
  if (!r && atomic_load(&out->h->count) == 0) r = -EINVAL; /* нет даже корня */
  if (!r) r = arena_validate(out);
  if (r) {
    munmap(base, (size_t)st.st_size);
    memset(out, 0, sizeof *out);
    return r;
  }
  out->owned = 1;
  return 0;
}
