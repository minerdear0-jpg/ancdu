#include "arena.h"
#include "csr.h"
#include "index.h"
#include "test.h"

#include <errno.h>

static uint32_t child(const arena *a, uint32_t p, const char *name) {
  for (uint32_t j = 0; j < a->child_count[p]; j++) {
    uint32_t x = a->order[a->child_start[p] + j];
    if (strcmp(arena_name(a, x), name) == 0) return x;
  }
  return ANCDU_NONE;
}

int main(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 1000, 1 << 20, "/storage/emulated/0", SRC_INDEX) == 0);
  index_builder *b = index_begin(&a);
  CHECK(b != NULL);
  CHECK(index_add(b, "DCIM/Camera/", "a.jpg", 5000) == 0);
  CHECK(index_add(b, "DCIM/Camera/", "b.jpg", 3000) == 0);
  CHECK(index_add(b, "Download/", "x.zip", 10) == 0);
  CHECK(index_add(b, "", "root.txt", 1) == 0);
  CHECK(index_add(b, "DCIM/", "c.png", 4096) == 0);
  CHECK(index_add(b, "DCIM/Camera/", "d.jpg", 1) == 0);   /* промах кэша last_dir */
  CHECK(index_add(b, "a//b/./", "z", 2) == 0);            /* пустые и "." компоненты */
  index_end(b);
  post_process(&a, 2);
  atomic_store(&a.h->state, ST_DONE);

  CHECK_EQ_U(atomic_load(&a.h->count), 13);
  CHECK_EQ_U(a.apparent[0], 12110);
  CHECK_EQ_U(a.disk[0], 32768);
  CHECK(arena_validate(&a) == 0);

  uint32_t dcim = child(&a, 0, "DCIM");
  uint32_t cam = child(&a, dcim, "Camera");
  CHECK(dcim != ANCDU_NONE && cam != ANCDU_NONE);
  CHECK(a.flags[dcim] & F_DIR);
  CHECK_EQ_U(a.child_count[cam], 3);
  uint32_t out[8];
  CHECK_EQ_U(csr_children(&a, dcim, SORT_SIZE, 0, out, 8), 2);
  CHECK_EQ_U(out[0], cam);                                 /* 16384 > 4096 */
  char buf[512];
  arena_path(&a, child(&a, cam, "d.jpg"), buf, sizeof buf);
  CHECK(strcmp(buf, "/storage/emulated/0/DCIM/Camera/d.jpg") == 0);
  uint32_t ad = child(&a, 0, "a");
  CHECK(ad != ANCDU_NONE && child(&a, ad, "b") != ANCDU_NONE);
  arena_unmap(&a);

  /* переполнение */
  CHECK(arena_alloc_anon(&a, 3, 1 << 20, "/s", SRC_INDEX) == 0);
  b = index_begin(&a);
  CHECK(index_add(b, "A/", "f", 1) == 0);
  CHECK(index_add(b, "B/", "g", 1) == -ENOSPC);
  index_end(b);
  arena_unmap(&a);
  TEST_END();
}
