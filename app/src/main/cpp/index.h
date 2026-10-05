#pragma once
#include "arena.h"

typedef struct index_builder index_builder;

index_builder *index_begin(arena *a);
int index_add(index_builder *b, const char *rel_dir, const char *name, uint64_t size);
void index_end(index_builder *b);
