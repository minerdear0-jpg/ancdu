#pragma once
/* Начало строки stderr, которой хелпер (--memfd) сообщает, что общую арену
 * открыть или отобразить нельзя (SELinux и т.п.). Только по этой строке
 * session_root.c повторяет скан через pipe-дамп; любой другой сбой (отказ su,
 * хелпер не найден или упал) — ST_FAILED без второго запуска, чтобы отказ
 * Magisk не вызвал повторный запрос root. */
#define ANCDU_MEMFD_UNAVAILABLE "ancdu: memfd unavailable"

/* Коды выхода хелпера (libancdu_scan.so) — единственное место, где они заданы:
 * cli.c их возвращает, session_root.c разбирает. */
enum ancdu_exit {
  ANCDU_EXIT_OK = 0,           /* скан: ST_DONE; --rm: путь удалён (или его уже не было) */
  ANCDU_EXIT_FAILED = 1,       /* скан не удался (в т.ч. ANCDU_MEMFD_UNAVAILABLE) */
  ANCDU_EXIT_USAGE = 2,        /* неверные аргументы, негодная memfd-арена */
  ANCDU_EXIT_FULL = 3,         /* скан: ST_FULL — арена заполнена */
  ANCDU_EXIT_CANCELLED = 4,    /* скан: ST_CANCELLED — EOF на stdin */
  ANCDU_EXIT_RM_PARTIAL = 5,   /* --rm: удалено не всё */
  ANCDU_EXIT_RM_STOPPED = 6,   /* --rm: остановлено через stdin (частично) */
  ANCDU_EXIT_RM_SYMLINK = 7,   /* --rm: ссылка или «.»/«..» в родителе — ничего не удалено */
  ANCDU_EXIT_RM_UNCHECKED = 8, /* --rm: родителя не открыть (EACCES, ENOTDIR…) — ничего не удалено */
  ANCDU_EXIT_RM_CHANGED = 9,   /* --rm --expect: вершина подменена после скана — ничего не удалено */
};
