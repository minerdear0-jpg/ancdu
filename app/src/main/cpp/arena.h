#pragma once
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

/* v3: ino[] узлов и root_dev (сверка «изменилось после скана»). Кэш v2 не открывается
 * (-EINVAL) — приложение забывает его и сканирует заново. */
#define ANCDU_MAGIC "ANCDU\0\0\3"
#define ANCDU_VERSION 3u
#define ANCDU_HDR_SIZE 4096u
#define ANCDU_NONE 0xFFFFFFFFu
#define ANCDU_LIVE_SLOTS 256u
#define ANCDU_NAME_CHUNK 65536u
#define ANCDU_MAX_NAMES 0xFFFFFFF0ull
#define ANCDU_MAX_NAME 4095u
#define ANCDU_MAX_DEPTH 8192

enum { ST_RUNNING = 0, ST_DONE = 1, ST_FAILED = 2, ST_CANCELLED = 3, ST_FULL = 4 };
enum { SRC_SCAN = 0, SRC_INDEX = 1 };
enum { F_DIR = 1, F_ERR = 2, F_HLDUP = 4, F_OTHERFS = 8, F_SYMLINK = 16, F_DELETED = 32 };
enum { ANCDU_CANCEL_USER = 1, ANCDU_CANCEL_FULL = 2 };

/* Заголовок отображения. Лежит в общей памяти между процессами (memfd),
 * поэтому только lock-free атомики фиксированного размера. */
typedef struct {
  char magic[8];
  uint32_t version;
  _Atomic uint32_t state;   /* ST_* ; публикуется с release */
  uint32_t source;          /* SRC_* */
  _Atomic uint32_t cancel;  /* ANCDU_CANCEL_* */
  uint64_t cap_nodes;
  uint64_t cap_names;
  _Atomic uint64_t count;
  _Atomic uint64_t names_used;
  _Atomic uint64_t files;
  _Atomic uint64_t bytes;
  _Atomic uint64_t errors;
  int64_t started_ns;
  _Atomic int64_t finished_ns;
  _Atomic int64_t cur_ns;
  _Atomic uint32_t cur_seq;  /* нечётное — идёт запись cur_path */
  _Atomic uint32_t live_count;
  char root_path[1024];
  char cur_path[512];
  _Atomic uint64_t live_disk[ANCDU_LIVE_SLOTS];
  uint64_t root_dev; /* st_dev корня скана; 0 — неизвестно (индекс) */
  uint64_t off_parent, off_disk, off_apparent, off_items, off_name_off,
      off_name_len, off_flags, off_child_start, off_child_count, off_order,
      off_ino, off_names, total_size;
} ancdu_hdr;

_Static_assert(sizeof(ancdu_hdr) <= ANCDU_HDR_SIZE, "header must fit 4 KiB");

typedef struct {
  ancdu_hdr *h;
  uint32_t *parent;
  uint64_t *disk;
  uint64_t *apparent;
  uint32_t *items;
  uint32_t *name_off;
  uint16_t *name_len;
  uint8_t *flags;
  uint32_t *child_start;
  uint32_t *child_count;
  uint32_t *order;
  /* st_ino узла на устройстве root_dev; 0 — неизвестно (индекс, другая ФС, ошибка stat):
   * тогда удаление не сверяет объект, как до v3. */
  uint64_t *ino;
  char *names;
  void *base;
  size_t size;
  /* Ёмкости, проверенные при attach. Заголовок может лежать в общей памяти (memfd, его пишет
   * другой процесс): после attach ёмкости из него не перечитываются. */
  uint64_t cap_nodes, cap_names;
  int owned; /* 1 — arena_unmap делает munmap */
} arena;

/* Чанк пула имён, свой у каждого потока. Начальное значение {0, 0}. */
typedef struct {
  uint64_t pos, end;
} name_chunk;

size_t arena_bytes(uint64_t cap_nodes, uint64_t cap_names);
void arena_format(void *base, uint64_t cap_nodes, uint64_t cap_names,
                  const char *root, uint32_t source);
int arena_attach(arena *a, void *base, size_t size);
int arena_alloc_anon(arena *a, uint64_t cap_nodes, uint64_t cap_names,
                     const char *root, uint32_t source);
void arena_unmap(arena *a);

/* Новый узел; ANCDU_NONE при переполнении (и cancel = ANCDU_CANCEL_FULL). */
uint32_t arena_new_node(arena *a, name_chunk *ck, uint32_t parent,
                        const char *name, size_t len, uint8_t flags);

static inline const char *arena_name(const arena *a, uint32_t n) {
  return a->names + a->name_off[n];
}

/* Полный путь узла; длина или -ENAMETOOLONG / -EINVAL. */
int arena_path(const arena *a, uint32_t n, char *buf, size_t cap);
/* O(n) проверка инвариантов (count/names_used в пределах ёмкостей attach, parent < ребёнка,
 * имена, дочерние массивы); 0 или -EINVAL. */
int arena_validate(const arena *a);
/* Согласованная копия текущего пути скана (seqlock); длина. */
int arena_cur_path(const arena *a, char *out, size_t cap);

uint64_t arena_cap_hint(const char *path);
uint64_t arena_names_hint(uint64_t cap_nodes);
int64_t ancdu_now_ns(void);

/* ---- сериализация (формат = раскладка арены с ёмкостями = факту) ---- */
int arena_write(const arena *a, int fd);
int arena_read_stream(arena *out, int fd);
int arena_save_file(const arena *a, const char *path);
int arena_open_file(arena *out, const char *path);
