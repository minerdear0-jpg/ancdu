#pragma once
/* Начало строки stderr, которой хелпер (--memfd) сообщает, что общую арену
 * открыть или отобразить нельзя (SELinux и т.п.). Только по этой строке
 * session_root.c повторяет скан через pipe-дамп; любой другой сбой (отказ su,
 * хелпер не найден или упал) — ST_FAILED без второго запуска, чтобы отказ
 * Magisk не вызвал повторный запрос root. */
#define ANCDU_MEMFD_UNAVAILABLE "ancdu: memfd unavailable"
