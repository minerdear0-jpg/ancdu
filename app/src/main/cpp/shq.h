#pragma once
#include <stddef.h>
/* Заключает s в одинарные кавычки для sh -c ( ' → '\'' ). Длина или -1. */
int shq(const char *s, char *out, size_t cap);
