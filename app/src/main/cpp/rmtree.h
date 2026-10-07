#pragma once
#include <stdatomic.h>
#include <stddef.h>
#include <stdint.h>

/* Рекурсивно удаляет path. Симлинки удаляются как ссылки, без перехода.
 * Не пересекает файловые системы: вершина-точка монтирования — отказ -EXDEV;
 * вложенные точки монтирования и bind-файлы пропускаются с -EXDEV (частичное удаление),
 * как и каталог, подменённый между проверкой и открытием.
 * Не-каталоги по d_type удаляются сразу (unlinkat без fstatat; EBUSY — bind-файл — -EXDEV),
 * каталоги и DT_UNKNOWN — с полной проверкой устройства и подмены.
 * Путь с пустым последним компонентом («/», «//», «») или оканчивающийся на «.»/«..», а также
 * относительный путь — отказ -EINVAL без каких-либо действий (см. rm_open_parent).
 * Продолжает после ошибок; возвращает 0 или первую ошибку (-errno).
 *
 * threads > 1 — параллельно: этот поток обходит каталоги, threads рабочих выполняют
 * unlinkat не-каталогов из ограниченной очереди; каталог удаляется после всех своих
 * детей. threads <= 1 — всё в вызывающем потоке. Значение по умолчанию —
 * rm_default_threads(path), его передаёт вызывающий.
 * done (может быть NULL) — +1 после каждого удачного unlink/rmdir (файлы и каталоги).
 * stop (может быть NULL) — стал ненулевым: обход и удаление прекращаются, уже удалённое
 * остаётся удалённым; если стоп помешал что-то удалить — -EINTR (частичное удаление).
 * Оба можно читать/писать из любого потока во время вызова.
 * Глубина: каталоги глубже 4096 уровней под path не открываются — -ELOOP (частичное удаление).
 * Обход идёт на собственном потоке со стеком 8 МБ (стек вызывающего не важен); поток не
 * создан — -errno pthread_create, ничего не тронуто. */
int rm_tree_ex(const char *path, int threads, _Atomic uint64_t *done, _Atomic int *stop);

/* Объект, который видел скан: st_dev/st_ino. ino == 0 — неизвестен, сверки нет. */
typedef struct {
  uint64_t dev, ino;
} rm_expect;

/* Как rm_tree_ex, но вершина path (fstatat без перехода по ссылке) должна быть объектом want,
 * иначе -ESTALE — «изменилось после скана», ничего не тронуто (want NULL или ino 0 — без
 * сверки). Сверка — в той же fstatat, по которой rm_tree_ex решает, что удалять; каталог
 * дальше открывается с проверкой st_dev/st_ino против неё же. Пути нет — -ENOENT, как всегда. */
int rm_tree_expect(const char *path, int threads, _Atomic uint64_t *done, _Atomic int *stop,
                   const rm_expect *want);

/* Рабочих для rm_tree_ex по умолчанию: 1 вне FUSE (на tmpfs/f2fs unlink в одном каталоге
 * упирается в блокировку inode каталога — потоки только мешают), на FUSE — min(6, ядра)
 * (задержка запроса к демону FUSE). Подбирается по замерам на устройстве. */
int rm_default_threads(const char *path);

/* Открывает каталог-родитель вершины path для удаления (O_PATH; закрывает вызывающий) и
 * копирует последний компонент в last (cap байт). Отказы — до любых обращений к ФС:
 * rm_tree_target (-EINVAL / -ENAMETOOLONG), относительный path — -EINVAL.
 * nofollow = 0 — родитель как его разрешает ядро (ссылки в родителе допустимы:
 * /data/user/0 → /data/data у cacheDir приложения); разрешается один раз, дальше всё
 * относительно fd.
 * nofollow = 1 (удаление под root) — ни один компонент родителя не ссылка: от «/»
 * openat2(RESOLVE_NO_SYMLINKS | RESOLVE_NO_MAGICLINKS), а где его нет (ядра 4.19/5.4,
 * seccomp-фильтр) — по компонентам openat(O_PATH | O_NOFOLLOW | O_DIRECTORY) от fd
 * предыдущего. Ссылка или компонент «.»/«..»/пустой — -ELOOP; нет компонента — -ENOENT;
 * иначе -errno (EACCES, ENOTDIR…). Подмена компонента после открытия уже не влияет:
 * удаление идёт относительно полученного fd (rm_tree_at). Возвращает fd или -errno. */
int rm_open_parent(const char *path, int nofollow, char *last, size_t cap);

/* Как rm_tree_expect, но вершина — запись name (один компонент, не «.»/«..») каталога pfd
 * (от rm_open_parent; не закрывается). Проверка точки монтирования — st_dev name против
 * fstat(pfd). */
int rm_tree_at(int pfd, const char *name, int threads, _Atomic uint64_t *done, _Atomic int *stop,
               const rm_expect *want);

/* rm_tree_ex(path, 1, NULL, NULL). */
int rm_tree(const char *path);

/* Проверка пути для rm_tree без обращений к ФС: копирует path без завершающих слешей
 * в buf (cap байт) и возвращает 0, либо -ENAMETOOLONG, либо -EINVAL — последний
 * компонент пуст («/», «//», «»), «.» или «..». */
int rm_tree_target(const char *path, char *buf, size_t cap);
