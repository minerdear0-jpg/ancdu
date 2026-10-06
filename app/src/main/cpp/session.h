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
 * prefix == NULL — удаление в процессе (rm_tree_ex, scan_default_threads(path) рабочих),
 * иначе через helper --rm под prefix. Коды выхода хелпера: 0 — удалено, 5 — частично,
 * 6 — остановлено (частично). Итог: -EINTR — остановлено sess_delete_stop (частично,
 * в обоих режимах); -EPERM — root не получен или хелпер не запустился (ничего не удалено);
 * -EIO — частично (выход 5), хелпер убит сигналом или waitpid не удался (исход неизвестен).
 * В начале обнуляет счётчик и флаг стопа удаления. */
int sess_delete(session *s, uint32_t node, const char *const *prefix, const char *helper);
/* Единственное исключение из «никаких параллельных вызовов на одной сессии»: эти две
 * функции трогают только атомики удаления (и под mu — stdin хелпера), их можно звать из
 * любого потока, пока идёт sess_delete. Ничего другого параллельно звать нельзя.
 * progress — сколько записей (файлов и каталогов) удалено текущим/последним sess_delete;
 * stop — просит остановить идущее удаление (вне удаления — сбрасывается следующим). */
uint64_t sess_delete_progress(session *s);
void sess_delete_stop(session *s);
void sess_free(session *s);
