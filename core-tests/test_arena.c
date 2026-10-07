#include "arena.h"
#include "test.h"

#include <errno.h>

static void test_nodes_and_paths(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 100, 4096, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  uint32_t root = arena_new_node(&a, &ck, ANCDU_NONE, "", 0, F_DIR);
  uint32_t d = arena_new_node(&a, &ck, root, "a", 1, F_DIR);
  uint32_t f = arena_new_node(&a, &ck, d, "b.txt", 5, 0);
  CHECK_EQ_U(root, 0);
  CHECK_EQ_U(d, 1);
  CHECK_EQ_U(f, 2);
  CHECK_EQ_U(atomic_load(&a.h->count), 3);
  CHECK_EQ_U(a.items[f], 1);
  CHECK(strcmp(arena_name(&a, f), "b.txt") == 0);
  char buf[256];
  CHECK(arena_path(&a, f, buf, sizeof buf) == 10);
  CHECK(strcmp(buf, "/r/a/b.txt") == 0);
  CHECK(arena_path(&a, 0, buf, sizeof buf) == 2);
  CHECK(strcmp(buf, "/r") == 0);
  CHECK(arena_path(&a, f, buf, 5) == -ENAMETOOLONG);
  CHECK(arena_validate(&a) == 0);
  arena_unmap(&a);

  arena s;
  CHECK(arena_alloc_anon(&s, 10, 4096, "/", SRC_SCAN) == 0);
  ck = (name_chunk){0, 0};
  arena_new_node(&s, &ck, ANCDU_NONE, "", 0, F_DIR);
  uint32_t x = arena_new_node(&s, &ck, 0, "x", 1, 0);
  CHECK(arena_path(&s, x, buf, sizeof buf) == 2);
  CHECK(strcmp(buf, "/x") == 0);
  arena_unmap(&s);
}

static void test_capacity_full(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 2, 4096, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  CHECK(arena_new_node(&a, &ck, ANCDU_NONE, "", 0, F_DIR) == 0);
  CHECK(arena_new_node(&a, &ck, 0, "a", 1, 0) == 1);
  CHECK(arena_new_node(&a, &ck, 0, "b", 1, 0) == ANCDU_NONE);
  CHECK_EQ_U(atomic_load(&a.h->count), 2);
  CHECK_EQ_U(atomic_load(&a.h->cancel), ANCDU_CANCEL_FULL);
  CHECK(arena_validate(&a) == 0);
  arena_unmap(&a);

  /* Пул имён: 8 байт вмещают "" + "abc" (1 + 4), но не ещё "defg" (5). */
  CHECK(arena_alloc_anon(&a, 100, 8, "/r", SRC_SCAN) == 0);
  ck = (name_chunk){0, 0};
  CHECK(arena_new_node(&a, &ck, ANCDU_NONE, "", 0, F_DIR) == 0);
  CHECK(arena_new_node(&a, &ck, 0, "abc", 3, 0) == 1);
  CHECK(arena_new_node(&a, &ck, 0, "defg", 4, 0) == ANCDU_NONE);
  CHECK(atomic_load(&a.h->names_used) <= 8);
  CHECK(arena_validate(&a) == 0);
  arena_unmap(&a);
}

static void test_attach_rejects_garbage(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 10, 4096, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  arena_new_node(&a, &ck, ANCDU_NONE, "", 0, F_DIR);
  arena_new_node(&a, &ck, 0, "a", 1, 0);
  arena b;
  CHECK(arena_attach(&b, a.base, a.size) == 0);
  CHECK(arena_attach(&b, a.base, ANCDU_HDR_SIZE) == -EINVAL); /* меньше раскладки */
  a.parent[1] = 5; /* родитель >= ребёнка */
  CHECK(arena_validate(&a) == -EINVAL);
  a.parent[1] = 0;
  a.h->magic[0] = 'X';
  CHECK(arena_attach(&b, a.base, a.size) == -EINVAL);
  a.h->magic[0] = 'A';
  arena_unmap(&a);
}

/* Ёмкости копируются в arena при attach: общий заголовок (memfd, его пишет другой процесс)
 * после attach не перечитывается — раздутый cap_nodes/cap_names в нём не даёт писать за
 * отображение; count/names_used за ёмкостью — validate отказывает. */
static void test_caps_not_reread(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 2, 4096, "/r", SRC_SCAN) == 0);
  CHECK_EQ_U(a.cap_nodes, 2);
  CHECK_EQ_U(a.cap_names, 4096);
  a.h->cap_nodes = 1u << 30;
  a.h->cap_names = 1ull << 32;
  name_chunk ck = {0, 0};
  CHECK(arena_new_node(&a, &ck, ANCDU_NONE, "", 0, F_DIR) == 0);
  CHECK(arena_new_node(&a, &ck, 0, "a", 1, 0) == 1);
  CHECK(arena_new_node(&a, &ck, 0, "b", 1, 0) == ANCDU_NONE);
  CHECK_EQ_U(atomic_load(&a.h->count), 2);
  CHECK(arena_validate(&a) == 0);
  atomic_store(&a.h->count, 3); /* «чужой» процесс записал count за ёмкость */
  CHECK(arena_validate(&a) == -EINVAL);
  atomic_store(&a.h->count, 2);
  atomic_store(&a.h->names_used, 4097);
  CHECK(arena_validate(&a) == -EINVAL);
  arena_unmap(&a);
}

static void test_hints(void) {
  uint64_t cap = arena_cap_hint("/");
  CHECK(cap > 65536);
  CHECK(cap < ANCDU_NONE);
  CHECK(arena_names_hint(cap) <= ANCDU_MAX_NAMES);
  char buf[16];
  arena a;
  CHECK(arena_alloc_anon(&a, 4, 4096, "/r", SRC_SCAN) == 0);
  CHECK(arena_cur_path(&a, buf, sizeof buf) == 0);
  CHECK(buf[0] == 0);
  arena_unmap(&a);
}

int main(void) {
  test_nodes_and_paths();
  test_capacity_full();
  test_attach_rejects_garbage();
  test_caps_not_reread();
  test_hints();
  TEST_END();
}
