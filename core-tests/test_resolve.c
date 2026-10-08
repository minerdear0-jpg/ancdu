/* csr_child_named и csr_resolve: ребёнок по байтам имени и путь по цепочке имён от корня (браузер
 * после смерти процесса, курсор «вы были здесь»). Ничего не удаляет и не создаёт на диске:
 * только синтетические арены в памяти (csr_remove — флаг в арене). */
#include "arena.h"
#include "csr.h"
#include "test.h"

static uint32_t N(arena *a, name_chunk *ck, uint32_t p, const char *nm, size_t len, uint8_t fl) {
  uint32_t i = arena_new_node(a, ck, p, nm, len, fl);
  a->disk[i] = 4096;
  a->apparent[i] = 4096;
  return i;
}

#define S(s) s, sizeof(s) - 1

/*
 * root
 *  ├ DCIM/      └ Camera/  └ v.mp4
 *  ├ Download/  ├ a.txt    └ ab/ (каталог; имя — продолжение «a»)
 *  ├ file       (файл с тем же именем, что ищем как каталог)
 *  ├ \xff\xfe/  (невалидный UTF-8)  └ x
 *  └ \xfe/      └ y
 * Индексы: root0 DCIM1 Download2 file3 ff4 fe5 Camera6 v7 a8 ab9 x10 y11
 */
static void build(arena *a) {
  CHECK(arena_alloc_anon(a, 64, 4096, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(a, &ck, ANCDU_NONE, "", 0, F_DIR);
  N(a, &ck, 0, S("DCIM"), F_DIR);
  N(a, &ck, 0, S("Download"), F_DIR);
  N(a, &ck, 0, S("file"), 0);
  N(a, &ck, 0, S("\xff\xfe"), F_DIR);
  N(a, &ck, 0, S("\xfe"), F_DIR);
  N(a, &ck, 1, S("Camera"), F_DIR);
  N(a, &ck, 6, S("v.mp4"), 0);
  N(a, &ck, 2, S("a.txt"), 0);
  N(a, &ck, 2, S("ab"), F_DIR);
  N(a, &ck, 4, S("x"), 0);
  N(a, &ck, 5, S("y"), 0);
  CHECK(post_process(a, 1) == 0);
}

static void test_child_named(void) {
  arena a;
  build(&a);
  CHECK_EQ_U(csr_child_named(&a, 0, S("DCIM"), 0), 1);
  CHECK_EQ_U(csr_child_named(&a, 0, S("DCIM"), 1), 1);
  CHECK_EQ_U(csr_child_named(&a, 2, S("a.txt"), 0), 8);
  /* файл — не каталог */
  CHECK_EQ_U(csr_child_named(&a, 2, S("a.txt"), 1), ANCDU_NONE);
  CHECK_EQ_U(csr_child_named(&a, 0, S("file"), 1), ANCDU_NONE);
  /* только точное совпадение байтов: ни префикс, ни продолжение, ни регистр */
  CHECK_EQ_U(csr_child_named(&a, 2, S("a"), 0), ANCDU_NONE);
  CHECK_EQ_U(csr_child_named(&a, 2, S("a.tx"), 0), ANCDU_NONE);
  CHECK_EQ_U(csr_child_named(&a, 2, S("a.txt2"), 0), ANCDU_NONE);
  CHECK_EQ_U(csr_child_named(&a, 0, S("dcim"), 0), ANCDU_NONE);
  /* невалидный UTF-8: разные байты — разные имена (как строки оба были бы «�») */
  CHECK_EQ_U(csr_child_named(&a, 0, S("\xff\xfe"), 0), 4);
  CHECK_EQ_U(csr_child_named(&a, 0, S("\xfe"), 0), 5);
  CHECK_EQ_U(csr_child_named(&a, 0, S("\xef\xbf\xbd"), 0), ANCDU_NONE);
  /* пустое имя, узел вне дерева, ребёнок не того родителя */
  CHECK_EQ_U(csr_child_named(&a, 0, "", 0, 0), ANCDU_NONE);
  CHECK_EQ_U(csr_child_named(&a, 99, S("DCIM"), 0), ANCDU_NONE);
  CHECK_EQ_U(csr_child_named(&a, 0, S("Camera"), 0), ANCDU_NONE);
  /* у файла детей нет */
  CHECK_EQ_U(csr_child_named(&a, 3, S("x"), 0), ANCDU_NONE);
  /* удалённый не находится */
  CHECK(csr_remove(&a, 8) == 0);
  CHECK_EQ_U(csr_child_named(&a, 2, S("a.txt"), 0), ANCDU_NONE);
  CHECK_EQ_U(csr_child_named(&a, 2, S("ab"), 0), 9);
  arena_unmap(&a);
}

static void test_resolve(void) {
  arena a;
  build(&a);
  uint32_t d = 77;
  /* пустая цепочка — корень, глубина 0 */
  CHECK_EQ_U(csr_resolve(&a, "", 0, 1, &d), 0);
  CHECK_EQ_U(d, 0);
  /* весь путь */
  CHECK_EQ_U(csr_resolve(&a, S("DCIM\0Camera"), 1, &d), 6);
  CHECK_EQ_U(d, 2);
  CHECK_EQ_U(csr_resolve(&a, S("DCIM\0Camera\0v.mp4"), 0, &d), 7);
  CHECK_EQ_U(d, 3);
  /* последний — файл, а нужен каталог: самый глубокий предок */
  CHECK_EQ_U(csr_resolve(&a, S("DCIM\0Camera\0v.mp4"), 1, &d), 6);
  CHECK_EQ_U(d, 2);
  /* файл посреди пути не проходится и без dir_only */
  CHECK_EQ_U(csr_resolve(&a, S("file\0x"), 0, &d), 0);
  CHECK_EQ_U(d, 0);
  /* частично: папки нет — предок и сколько найдено */
  CHECK_EQ_U(csr_resolve(&a, S("DCIM\0Screenshots\0x"), 1, &d), 1);
  CHECK_EQ_U(d, 1);
  CHECK_EQ_U(csr_resolve(&a, S("gone"), 1, &d), 0);
  CHECK_EQ_U(d, 0);
  /* байты невалидного UTF-8 */
  CHECK_EQ_U(csr_resolve(&a, S("\xff\xfe\0x"), 0, &d), 10);
  CHECK_EQ_U(d, 2);
  CHECK_EQ_U(csr_resolve(&a, S("\xfe\0y"), 0, &d), 11);
  CHECK_EQ_U(d, 2);
  CHECK_EQ_U(csr_resolve(&a, S("\xfe\0x"), 0, &d), 5);
  CHECK_EQ_U(d, 1);
  /* пустое имя посреди цепочки («a\0\0b») или в конце — дальше не ищется */
  CHECK_EQ_U(csr_resolve(&a, S("DCIM\0\0Camera"), 1, &d), 1);
  CHECK_EQ_U(d, 1);
  CHECK_EQ_U(csr_resolve(&a, S("DCIM\0"), 1, &d), 1);
  CHECK_EQ_U(d, 1);
  CHECK_EQ_U(csr_resolve(&a, S("\0DCIM"), 1, &d), 0);
  CHECK_EQ_U(d, 0);
  /* depth = NULL допустим */
  CHECK_EQ_U(csr_resolve(&a, S("Download\0ab"), 1, NULL), 9);
  /* удалённая папка: путь обрывается на ней */
  CHECK(csr_remove(&a, 6) == 0);
  CHECK_EQ_U(csr_resolve(&a, S("DCIM\0Camera\0v.mp4"), 0, &d), 1);
  CHECK_EQ_U(d, 1);
  arena_unmap(&a);
}

/* Длинная цепочка: 1000 уровней по 1 имени, плюс имя предельной длины в конце. */
static void test_deep(void) {
  arena a;
  CHECK(arena_alloc_anon(&a, 2048, 1 << 20, "/r", SRC_SCAN) == 0);
  name_chunk ck = {0, 0};
  N(&a, &ck, ANCDU_NONE, "", 0, F_DIR);
  uint32_t p = 0;
  static char chain[1000 * 3 + ANCDU_MAX_NAME + 8];
  size_t len = 0;
  for (int i = 0; i < 1000; i++) {
    char nm[3] = {(char)('a' + i % 26), (char)('a' + i / 26 % 26), 0};
    p = N(&a, &ck, p, nm, 2, F_DIR);
    memcpy(chain + len, nm, 2);
    len += 2;
    chain[len++] = 0;
  }
  static char longname[ANCDU_MAX_NAME];
  memset(longname, 'z', sizeof longname);
  uint32_t leaf = N(&a, &ck, p, longname, sizeof longname, 0);
  memcpy(chain + len, longname, sizeof longname);
  len += sizeof longname;
  CHECK(post_process(&a, 2) == 0);
  uint32_t d = 0;
  CHECK_EQ_U(csr_resolve(&a, chain, len, 0, &d), leaf);
  CHECK_EQ_U(d, 1001);
  /* без последнего имени — его папка */
  CHECK_EQ_U(csr_resolve(&a, chain, len - sizeof longname - 1, 1, &d), p);
  CHECK_EQ_U(d, 1000);
  /* имя длиннее предела не совпадает ни с чем */
  static char toolong[ANCDU_MAX_NAME + 1];
  memset(toolong, 'z', sizeof toolong);
  CHECK_EQ_U(csr_child_named(&a, p, toolong, sizeof toolong, 0), ANCDU_NONE);
  arena_unmap(&a);
}

int main(void) {
  test_child_named();
  test_resolve();
  test_deep();
  TEST_END();
}
