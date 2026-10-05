#pragma once
#include "arena.h"

typedef struct {
  int one_fs;  /* 1 — не переходить на другие ФС (как du -x) */
  int threads; /* <= 0 — scan_default_threads(root) */
} scan_opts;

/* Сканирует a->h->root_path в пустую арену. Возвращает ST_DONE,
 * ST_CANCELLED, ST_FULL или ST_FAILED. Не делает post_process и не
 * публикует h->state — это обязанность вызывающего. */
int scan_run(arena *a, const scan_opts *o);

/* Прямой доступ — все онлайн-ядра (≤ 16); FUSE — не больше 6. */
int scan_default_threads(const char *root);
