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
 * prefix == NULL — удаление в процессе (rm_tree_ex),
 * иначе через helper --rm под prefix. Коды выхода хелпера: 0 — удалено, 5 — частично,
 * 6 — остановлено (частично). Итог: -EINTR — остановлено sess_delete_stop (частично,
 * в обоих режимах); -EPERM — root не получен или хелпер не запустился (ничего не удалено);
 * -ELOOP — хелпер отказал (выход 7): родитель пути проходит через симлинк, ничего не удалено;
 * выход 8 (родителя не проверить: EACCES, ENAMETOOLONG…) — -EPERM, ничего не удалено;
 * -EIO — частично (выход 5), хелпер убит сигналом или waitpid не удался (исход неизвестен).
 * -ESTALE — вершина не тот объект, что видел скан (st_dev/st_ino узла; в обоих режимах, хелперу —
 * --expect, его выход 9): ничего не удалено, узел помечен F_ERR (дерево устарело). Узлы без ino
 * (индекс, другая ФС) и sess_delete_media не сверяются.
 * В начале обнуляет счётчик; флаг стопа сбрасывает в КОНЦЕ — стоп, присланный до начала,
 * срабатывает: -EINTR сразу, ничего не тронуто, узел не помечен, хелпер/su не запускается. In-process — rm_default_threads(path) рабочих. */
int sess_delete(session *s, uint32_t node, const char *const *prefix, const char *helper);
/* Как sess_delete через helper --rm под prefix (обязателен), но удаляет не путь узла
 * /storage/emulated/<n>/X, а тот же файл без FUSE — /data/media/<n>/X (media_path).
 * Путь узла не сопоставляется — -EINVAL, ничего не запущено. Итог и дерево — как у sess_delete. */
int sess_delete_media(session *s, uint32_t node, const char *const *prefix, const char *helper);
/* /storage/emulated/<n>/X → /data/media/<n>/X в out (cap байт): 0, -ENAMETOOLONG или -EINVAL —
 * не тот префикс, <n> не из цифр, X пуст, компонент X пуст, «.» или «..» (в т.ч. «/» в конце). */
int media_path(const char *in, char *out, size_t cap);
/* Единственное исключение из «никаких параллельных вызовов на одной сессии»: эти две
 * функции трогают только атомики удаления (и под mu — stdin хелпера), их можно звать из
 * любого потока, пока идёт sess_delete. Ничего другого параллельно звать нельзя.
 * progress — сколько записей (файлов и каталогов) удалено текущим/последним sess_delete;
 * stop — просит остановить идущее или ещё не начатое удаление; флаг живёт до конца
 * ближайшего sess_delete (вызов после его конца остановил бы следующее — вызывающий
 * зовёт stop только пока его удаление не вернулось). */
uint64_t sess_delete_progress(session *s);
void sess_delete_stop(session *s);
/* Помечает узел F_ERR (удалён частично мимо sess_delete — массовым шагом MediaStore).
 * Трогает только flags узла; вызывать по тем же правилам, что sess_delete (не параллельно
 * с другими вызовами на сессии, кроме progress/stop). 0 или -EINVAL (нет арены, node 0 или
 * вне дерева). */
int sess_mark_err(session *s, uint32_t node);
void sess_free(session *s);
