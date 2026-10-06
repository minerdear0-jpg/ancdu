#pragma once
#include "arena.h"

/* Сессия держит одно дерево: скан в процессе, root-скан, индекс или кэш. */
typedef struct session session;
/* У сессии один владелец: sess_wait и sess_free вызываются из одного потока
 * и не должны гоняться друг с другом (параллельных ожидающих нет). */

session *sess_scan_start(const char *root, int one_fs, int threads, int *err);
/* prefix — {"su","-c",NULL} на устройстве, {"sh","-c",NULL} в тестах. */
session *sess_root_start(const char *const *prefix, const char *helper, const char *root,
                         int one_fs, int use_memfd, int *err);
session *sess_index_begin(const char *root, uint64_t cap_nodes, int *err);
int sess_index_add(session *s, const char *rel_dir, const char *name, uint64_t size);
int sess_index_finish(session *s);
session *sess_open_cache(const char *path, int *err);
int sess_save_cache(session *s, const char *path);

/* out: [0] state ST_*, [1] files, [2] bytes, [3] errors, [4] elapsed ms,
 *      [5] 1 — root-скан идёт через memfd. path может быть NULL. */
void sess_progress(session *s, int64_t out[6], char *path, size_t cap);
/* Живые итоги детей корня; узел ANCDU_NONE — слот «прочее». */
int sess_live_top(session *s, uint32_t *nodes, uint64_t *disk, int cap);
/* Имя ребёнка корня 1..live_count — доступно и во время скана; иначе NULL. */
const char *sess_live_name(session *s, uint32_t node, size_t *len);
/* Копия текста ошибки; длина (0 — ошибки нет). */
int sess_error(session *s, char *out, size_t cap);
void sess_cancel(session *s);
/* Ждёт завершения фонового потока; возвращает state. */
int sess_wait(session *s);
/* Арена только в ST_DONE / ST_FULL, иначе NULL. */
arena *sess_arena(session *s);
/* 0 — путь удалён целиком, узел убран из дерева; иначе <0, узел помечен F_ERR.
 * prefix == NULL — удаление в процессе, иначе через helper --rm под prefix; тогда -EPERM —
 * root не получен или хелпер не запустился (ничего не удалено), -EIO — возможно частично. */
int sess_delete(session *s, uint32_t node, const char *const *prefix, const char *helper);
void sess_free(session *s);
